package com.woowangthon.backend.activity;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.AiClient;
import com.woowangthon.backend.ai.LinkSuggestionRequest;
import com.woowangthon.backend.ai.LinkSuggestionResponse;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.entrance.EntranceService;
import com.woowangthon.backend.experience.Experience;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ETC docs/contracts/experience-api.md 「이어짐 제안 받기」 · 「이어짐 정하기」(S-35).
 * BE 는 후보만 날짜로 고르고, 이어져 보이는지는 AI 가 내용으로 고른다 — 날짜 순서만으로 잇지 않는다.
 * AI 호출은 트랜잭션 밖에서 한다.
 */
@Service
public class ActivityLinkService {

    private static final Logger log = LoggerFactory.getLogger(ActivityLinkService.class);

    private static final int MAX_CANDIDATES = 20;
    private static final int MAX_SUGGESTIONS = 3;

    // 합치기로 같은 쌍이 둘이 되면 남길 순서 — 다시 제안하지 않게 REJECTED 부터(계약 「활동 합치기」)
    private static final Comparator<ActivityLink> KEEP_FIRST = Comparator
            .comparingInt((ActivityLink link) -> switch (link.getStatus()) {
                case REJECTED -> 0;
                case CONFIRMED -> 1;
                case SUGGESTED -> 2;
            })
            .thenComparing(ActivityLink::getCreatedAt)
            .thenComparing(ActivityLink::getId);

    private final ActivityLinkRepository links;
    private final ActivityRepository activities;
    private final AiClient ai;
    private final UsageLimiter limiter;
    private final TransactionTemplate tx;

    public ActivityLinkService(ActivityLinkRepository links, ActivityRepository activities, AiClient ai,
            UsageLimiter limiter, TransactionTemplate tx) {
        this.links = links;
        this.activities = activities;
        this.ai = ai;
        this.limiter = limiter;
        this.tx = tx;
    }

    /** 후보가 있으면 한도를 쓰고 AI 에 묻는다. 실패하면 아무것도 저장하지 않고 오류로 돌려준다(대회 규칙 2). */
    public ActivityResponses.Links suggest(long activityId) {
        long userId = CurrentUser.id();

        LinkSuggestionRequest request = tx.execute(status -> {
            Activity from = find(activityId, userId);
            if (from.getNote() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "NOTE_REQUIRED", "이 활동에 계기가 없습니다");
            }
            List<Activity> candidates = candidates(from);
            if (candidates.isEmpty()) {
                return null;
            }
            limiter.consume(userId);
            return new LinkSuggestionRequest(part(from), candidates.stream().map(ActivityLinkService::part).toList());
        });
        if (request == null) {
            return new ActivityResponses.Links(List.of());
        }

        List<LinkSuggestionResponse.Suggestion> suggestions;
        try {
            LinkSuggestionResponse response = ai.linkSuggestions(request);
            suggestions = check(response, request);
            log.info("이어짐 제안 — 활동 {}, 후보 {}개, 제안 {}개, 모델 {}", activityId, request.candidates().size(),
                    suggestions.size(), response.model());
        } catch (AiCallException e) {
            log.warn("이어짐 제안 실패 — 활동 {}: {} {}", activityId, e.code(), e.getMessage());
            throw EntranceService.toApi(e);
        }
        if (suggestions.isEmpty()) {
            return new ActivityResponses.Links(List.of());
        }

        return tx.execute(status -> {
            find(activityId, userId);
            // AI 를 기다리는 새 지워진 활동 · 그새 생긴 쌍은 건너뛴다
            return new ActivityResponses.Links(suggestions.stream()
                    .filter(s -> activities.findByIdAndUserId(s.toActivityId(), userId).isPresent())
                    .map(s -> links.insertSuggested(activityId, s.toActivityId(), s.reason()))
                    .flatMap(Optional::stream)
                    .map(id -> links.findById(id).orElseThrow())
                    .map(ActivityResponses.Link::from)
                    .toList());
        });
    }

    /** 같은 상태면 그대로 200, 이미 정한 걸 다른 상태로는 못 바꾼다(계약 「이어짐 정하기」). */
    @Transactional
    public ActivityResponses.Link decide(long linkId, PatchBody body) {
        String value = body.string("status").map(PatchBody.Field::value).orElse(null);
        LinkStatus decided;
        if ("CONFIRMED".equals(value)) {
            decided = LinkStatus.CONFIRMED;
        } else if ("REJECTED".equals(value)) {
            decided = LinkStatus.REJECTED;
        } else {
            throw ApiException.invalidRequest("status 는 CONFIRMED · REJECTED 중 하나여야 합니다");
        }
        ActivityLink link = links.findOwned(linkId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("LINK_NOT_FOUND", "이어짐 " + linkId + " 이(가) 없습니다"));
        if (link.getStatus() != decided) {
            if (link.getStatus() != LinkStatus.SUGGESTED) {
                throw new ApiException(HttpStatus.CONFLICT, "LINK_ALREADY_DECIDED", "이미 정한 이어짐입니다");
            }
            link.decide(decided);
        }
        return ActivityResponses.Link.from(link);
    }

    /** 활동 세부의 links — from 이든 to 든, REJECTED 는 빼고 createdAt 순. */
    List<ActivityResponses.Link> visibleOn(Activity activity) {
        return links.findTouching(activity.getId()).stream()
                .filter(l -> l.getStatus() != LinkStatus.REJECTED)
                .map(ActivityResponses.Link::from)
                .toList();
    }

    /**
     * 활동 합치기 — gone 에 걸린 이어짐을 keep 으로 옮긴다(계약 「활동 합치기」 links). 자기 자신을 가리키면 지우고,
     * 같은 쌍이 둘이 되면 하나만 남긴다. Hibernate 는 UPDATE 를 DELETE 보다 먼저 내보내므로, 유일 제약에 걸리지 않게 지운 뒤 옮긴다.
     */
    void moveOnMerge(Activity gone, Activity keep) {
        for (ActivityLink link : links.findTouching(gone.getId())) {
            Long fromId = swapped(link.getFromActivity(), gone, keep);
            Long toId = swapped(link.getToActivity(), gone, keep);
            if (fromId.equals(toId)) {
                links.delete(link);
                continue;
            }
            Optional<ActivityLink> twin = links.findByFromActivityIdAndToActivityId(fromId, toId);
            if (twin.isPresent() && KEEP_FIRST.compare(twin.get(), link) < 0) {
                links.delete(link);
                continue;
            }
            twin.ifPresent(links::delete);
            links.flush();
            link.replace(gone, keep);
        }
        links.flush();
    }

    private static Long swapped(Activity activity, Activity gone, Activity keep) {
        return activity.getId().equals(gone.getId()) ? keep.getId() : activity.getId();
    }

    // 계약 「후보 고르기」 — 뒤에 시작한 활동(문자열 비교, 같으면 포함) · 이미 있는 쌍은 상태와 방향 상관없이 빼기 · 가까운 순 20개.
    // 타임라인이 이미 start · id 오름차순이라 앞에서 자르면 가까운 순이다
    private List<Activity> candidates(Activity from) {
        String start = from.getPeriod().start();
        if (start == null) {
            return List.of();
        }
        Set<Long> linked = new HashSet<>();
        for (ActivityLink link : links.findTouching(from.getId())) {
            linked.add(link.getFromActivity().getId());
            linked.add(link.getToActivity().getId());
        }
        return activities.findTimeline(from.getUserId()).stream()
                .filter(a -> !a.getId().equals(from.getId()) && !linked.contains(a.getId()))
                .filter(a -> a.getPeriod().start() != null && a.getPeriod().start().compareTo(start) >= 0)
                .limit(MAX_CANDIDATES)
                .toList();
    }

    private static LinkSuggestionRequest.ActivityPart part(Activity activity) {
        return new LinkSuggestionRequest.ActivityPart(activity.getId(), activity.getName(), activity.getPeriod(),
                activity.getNote(), activity.getExperiences().stream()
                        .map(e -> new LinkSuggestionRequest.ExperiencePart(e.getId(), e.getContent()))
                        .toList());
    }

    /** experience-api.md 「이어짐 제안 받기」 다시 확인 — 벗어나면 고쳐 쓰지 않고 AI_FAILED(대회 규칙 2). */
    private static List<LinkSuggestionResponse.Suggestion> check(LinkSuggestionResponse response,
            LinkSuggestionRequest sent) {
        List<LinkSuggestionResponse.Suggestion> suggestions = response.suggestions();
        if (suggestions == null) {
            throw AiCallException.invalidResponse("suggestions 가 없습니다");
        }
        if (suggestions.size() > MAX_SUGGESTIONS) {
            throw AiCallException.invalidResponse("suggestions 는 " + MAX_SUGGESTIONS + "개까지입니다");
        }
        Set<Long> candidates = sent.candidates().stream()
                .map(LinkSuggestionRequest.ActivityPart::id)
                .collect(Collectors.toSet());
        Set<Long> seen = new HashSet<>();
        for (LinkSuggestionResponse.Suggestion s : suggestions) {
            if (!candidates.contains(s.toActivityId())) {
                throw AiCallException.invalidResponse(
                        "suggestions[].toActivityId " + s.toActivityId() + " 은(는) 보낸 후보가 아닙니다");
            }
            if (!seen.add(s.toActivityId())) {
                throw AiCallException.invalidResponse("suggestions 에 같은 활동이 두 번 있습니다: " + s.toActivityId());
            }
            if (s.reason() == null || s.reason().isBlank()) {
                throw AiCallException.invalidResponse("suggestions[].reason 이 비었습니다");
            }
        }
        return suggestions;
    }

    private Activity find(long activityId, long userId) {
        return activities.findByIdAndUserId(activityId, userId)
                .orElseThrow(() -> ApiException.notFound("ACTIVITY_NOT_FOUND", "활동 " + activityId + " 이(가) 없습니다"));
    }
}
