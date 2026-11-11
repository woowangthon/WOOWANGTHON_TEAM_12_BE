package com.woowangthon.backend.experience;

import com.woowangthon.backend.account.UsageLimiter;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.StarRequest;
import com.woowangthon.backend.ai.StarResponse;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 경험 STAR 칸(experience-api.md 「STAR 칸 — 정리 · 빈칸 답하기 · 칸 고치기」 · S-95 · S-96). AI 는 어느 글이 어느 칸인지만 나누고,
 * 칸 글이 같은 경험의 근거 · 보완에 정말 적혀 있는지는 여기서 한 번 더 확인한다(S-04 와 같다). AI 호출은 트랜잭션 밖(StarRunner).
 */
@Service
public class StarService {

    private static final Logger log = LoggerFactory.getLogger(StarService.class);

    static final int MAX_TEXT_LENGTH = 1000;
    static final int MAX_QUESTION_LENGTH = 80;

    private final ActivityRepository activities;
    private final ExperienceRepository experiences;
    private final SupplementRepository supplements;
    private final UsageLimiter limiter;

    public StarService(ActivityRepository activities, ExperienceRepository experiences,
            SupplementRepository supplements, UsageLimiter limiter) {
        this.activities = activities;
        this.experiences = experiences;
        this.supplements = supplements;
        this.limiter = limiter;
    }

    public record AnswerRequest(String answer) {
    }

    public record EditRequest(String text) {
    }

    /**
     * 「STAR 로 정리」 — 활동의 경험 전부를 정리 중으로 두고 분석 1회를 센다(S-15 · 미결정 31). 돌릴 경험 ID 묶음(/star 한 번에 30개까지)을
     * 돌려준다. AI 는 이 트랜잭션이 끝난 뒤 부른다.
     */
    @Transactional
    public List<List<Long>> start(long activityId) {
        long userId = CurrentUser.id();
        // 두 번 눌러도 한 번만 돌게 활동을 잠근다
        Activity activity = activities.findOwnedForUpdate(activityId, userId)
                .orElseThrow(() -> ApiException.notFound("ACTIVITY_NOT_FOUND", "활동 " + activityId + " 이(가) 없습니다"));
        List<Experience> list = activity.getExperiences();
        if (list.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_EXPERIENCES", "경험이 없는 활동입니다");
        }
        if (list.stream().anyMatch(e -> e.getStarStatus() == StarStatus.ANALYZING)) {
            throw new ApiException(HttpStatus.CONFLICT, "STAR_ANALYZING", "이 활동의 STAR 를 정리하는 중입니다");
        }
        limiter.consume(userId);
        list.forEach(Experience::startStar);
        experiences.flush();
        return chunks(list.stream().map(Experience::getId).toList());
    }

    /** 경험 ID 를 /star 한 번에 보낼 수 있는 만큼씩 나눈다(ai-api.md — 1 ~ 30개). */
    public static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> groups = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += StarRequest.MAX_EXPERIENCES) {
            groups.add(List.copyOf(ids.subList(i, Math.min(ids.size(), i + StarRequest.MAX_EXPERIENCES))));
        }
        return groups;
    }

    /** 아직 정리 중인 경험만 싣는다 — 그새 지워졌으면 빠진다. 하나도 없으면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<StarRequest> request(List<Long> experienceIds) {
        List<StarRequest.ExperiencePart> parts = experiences.findAllById(experienceIds).stream()
                .filter(e -> e.getStarStatus() == StarStatus.ANALYZING)
                .map(StarService::part)
                .toList();
        return parts.isEmpty() ? Optional.empty() : Optional.of(new StarRequest(parts));
    }

    // 근거는 원본에서 찾은 것만(matched: true), 보완은 사용자가 정한 것만(ai-api.md 「POST /star」)
    private static StarRequest.ExperiencePart part(Experience e) {
        return new StarRequest.ExperiencePart(e.getId(), e.getContent(), e.getActor(), e.getCompetencies(),
                e.getCitations().stream()
                        .filter(c -> Boolean.TRUE.equals(c.getMatched()))
                        .map(c -> new StarRequest.CitationPart(c.getId(), c.getQuote()))
                        .toList(),
                e.getConfirmedSupplements().stream()
                        .map(s -> new StarRequest.SupplementPart(s.getId(), s.getQuestion(), s.getAnswer()))
                        .toList());
    }

    /**
     * AI 응답을 칸으로 저장한다. 경험이 빠지거나 겹치거나 보내지 않은 id 면 응답 전체 위반(AiCallException) — 아무것도 남기지 않는다.
     * 칸 단위 위반은 그 칸만 빈칸으로(질문은 남긴다) 저장하고 수를 로그에 남긴다. 그새 정리 중이 아니게 된 경험은 건드리지 않는다.
     */
    @Transactional
    public void complete(StarRequest request, StarResponse result) {
        Map<Long, StarRequest.ExperiencePart> sent = new HashMap<>();
        request.experiences().forEach(p -> sent.put(p.id(), p));
        if (result.experiences() == null) {
            throw AiCallException.invalidResponse("experiences 가 없습니다");
        }
        Set<Long> seen = new HashSet<>();
        for (StarResponse.ExperienceResult r : result.experiences()) {
            if (r == null || r.id() == null || !sent.containsKey(r.id()) || !seen.add(r.id())) {
                throw AiCallException.invalidResponse("experiences 에 보내지 않았거나 겹친 경험이 있습니다: "
                        + (r == null ? null : r.id()));
            }
        }
        if (seen.size() != sent.size()) {
            throw AiCallException.invalidResponse("experiences 에 빠진 경험이 있습니다");
        }

        int violations = 0;
        for (StarResponse.ExperienceResult r : result.experiences()) {
            Experience experience = experiences.findById(r.id()).orElse(null);
            if (experience == null || experience.getStarStatus() != StarStatus.ANALYZING) {
                continue;
            }
            Checked checked = slots(sent.get(r.id()), r.slots());
            violations += checked.violations();
            experience.completeStar(checked.slots());
        }
        experiences.flush();
        if (violations > 0) {
            log.warn("STAR 응답에서 규칙을 어긴 칸 {}개를 빈칸으로 저장했습니다(경험 {}개)", violations, seen.size());
        }
    }

    private record Checked(List<StarSlot> slots, int violations) {
    }

    private static Checked slots(StarRequest.ExperiencePart part, List<StarResponse.SlotResult> results) {
        Map<StarSlotName, StarResponse.SlotResult> byName = new EnumMap<>(StarSlotName.class);
        Set<StarSlotName> duplicated = new HashSet<>();
        int violations = 0;
        for (StarResponse.SlotResult r : results == null ? List.<StarResponse.SlotResult>of() : results) {
            Optional<StarSlotName> name = r == null ? Optional.empty() : StarSlotName.parse(r.slot());
            if (name.isEmpty()) {
                violations++;
            } else if (byName.putIfAbsent(name.get(), r) != null) {
                duplicated.add(name.get());
            }
        }
        List<StarSlot> slots = new ArrayList<>();
        for (StarSlotName name : StarSlotName.values()) {
            StarResponse.SlotResult r = byName.get(name);
            if (r == null || duplicated.contains(name)) {
                violations++;
                slots.add(StarSlot.empty(name, null));
                continue;
            }
            StarSlot checked = slot(part, name, r);
            if (checked == null) {
                violations++;
                slots.add(StarSlot.empty(name, question(r.question())));
            } else {
                slots.add(checked);
            }
        }
        return new Checked(slots, violations);
    }

    /** 규칙에 맞으면 칸, 어기면 null. 빈칸의 질문이 80자를 넘으면 질문만 버린다(위반으로 세지 않는다). */
    private static StarSlot slot(StarRequest.ExperiencePart part, StarSlotName name, StarResponse.SlotResult r) {
        if (r.sourceType() == null && r.sourceId() == null) {
            if (r.text() != null && !r.text().isBlank()) {
                return null;
            }
            return StarSlot.empty(name, question(r.question()));
        }
        if (r.sourceType() == null || r.sourceId() == null || r.text() == null || r.text().isBlank()) {
            return null;
        }
        StarSourceType type;
        try {
            type = StarSourceType.valueOf(r.sourceType());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String source = sourceText(part, type, r.sourceId());
        if (source == null || !StarText.contains(source, r.text())) {
            return null;
        }
        // 수행 주체가 나로 정해지지 않았으면 T(내 몫)를 원문 근거로 채우지 않는다(S-06 · ai-api 「T 와 수행 주체」)
        if (name == StarSlotName.T && type == StarSourceType.CITATION && part.actor() != Actor.ME) {
            return null;
        }
        return StarSlot.filled(name, r.text().strip(), type, r.sourceId());
    }

    // 같은 경험에 보낸 근거 · 보완의 글 — 없으면 null
    private static String sourceText(StarRequest.ExperiencePart part, StarSourceType type, long id) {
        return switch (type) {
            case CITATION -> part.citations().stream().filter(c -> c.id() == id).map(StarRequest.CitationPart::quote)
                    .findFirst().orElse(null);
            case SUPPLEMENT -> part.supplements().stream().filter(s -> s.id() == id)
                    .map(StarRequest.SupplementPart::answer).findFirst().orElse(null);
        };
    }

    private static String question(String value) {
        if (value == null || value.isBlank() || value.strip().length() > MAX_QUESTION_LENGTH) {
            return null;
        }
        return value.strip();
    }

    /** 정리 실패 — 그 경험들 중 아직 정리 중인 것만 FAILED(앞 칸은 그대로). 자료 분석은 성공 그대로다. */
    @Transactional
    public void fail(List<Long> experienceIds) {
        experiences.findAllById(experienceIds).stream()
                .filter(e -> e.getStarStatus() == StarStatus.ANALYZING)
                .forEach(Experience::failStar);
    }

    /** 서버가 꺼지며 끊긴 정리를 실패로 돌린다 — 「다시 정리」를 누를 수 있게. 몇 개인지 돌려준다. */
    @Transactional
    public int failInterrupted() {
        return experiences.failInterruptedStar();
    }

    /**
     * 빈칸에 답하기 — 그 경험에 보완을 만들고(question = 칸 질문, 없으면 칸 이름) 칸이 그 보완을 가리킨다.
     * AI 를 부르지 않고 횟수를 세지 않는다. 경험 본문 · 근거 · 출처 상태는 그대로다(S-23).
     */
    @Transactional
    public StarView.WithExperience answer(long experienceId, String slotValue, AnswerRequest request) {
        String answer = text(request == null ? null : request.answer());
        if (answer.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_TEXT", "answer 가 비었습니다");
        }
        StarSlotName name = slotName(slotValue);
        Experience experience = lockReady(experienceId);
        StarSlot slot = slotOf(experience, name);
        if (isFilled(experience, slot)) {
            throw new ApiException(HttpStatus.CONFLICT, "STAR_SLOT_FILLED", "이미 찬 칸입니다 — 고치기는 PUT 으로");
        }
        fillWithNewSupplement(experience, slot, slot.getQuestion() == null ? name.label() : slot.getQuestion(),
                answer);
        experiences.flush();
        return StarView.WithExperience.from(experience);
    }

    /**
     * 칸 고치기(S-96) — 빈 글이면 칸만 비운다(보완은 남는다). 빈칸이면 답하기와 같고, 근거 칸이면 근거는 그대로 두고 새 보완으로 덮고,
     * 보완 칸이면 그 보완의 답을 고친다(보완 고치기와 같은 효과).
     */
    @Transactional
    public StarView.WithExperience edit(long experienceId, String slotValue, EditRequest request) {
        String text = text(request == null ? null : request.text());
        StarSlotName name = slotName(slotValue);
        Experience experience = lockReady(experienceId);
        StarSlot slot = slotOf(experience, name);

        if (text.isBlank()) {
            experience.replaceStarSlot(slot.cleared());
        } else if (!isFilled(experience, slot)) {
            fillWithNewSupplement(experience, slot, slot.getQuestion() == null ? name.label() : slot.getQuestion(),
                    text);
        } else if (slot.getSourceType() == StarSourceType.CITATION) {
            fillWithNewSupplement(experience, slot, name.label(), text);
        } else {
            Supplement supplement = experience.getSupplements().stream()
                    .filter(s -> s.getId().equals(slot.getSourceId())).findFirst().orElseThrow();
            String before = supplement.getAnswer();
            supplement.changeAnswer(text);
            experience.onSupplementChanged(supplement.getId(), before, text, name);
            experience.replaceStarSlot(slot.pointingTo(supplement, text));
        }
        experiences.flush();
        return StarView.WithExperience.from(experience);
    }

    private void fillWithNewSupplement(Experience experience, StarSlot slot, String question, String answer) {
        Supplement supplement = supplements.saveAndFlush(new Supplement(experience, null, null, question, answer));
        // 같은 요청 · 같은 영속성 컨텍스트에서 경험을 다시 읽어도 보이게
        if (Hibernate.isInitialized(experience.getSupplements())) {
            experience.getSupplements().add(supplement);
        }
        experience.replaceStarSlot(slot.pointingTo(supplement, answer));
    }

    private Experience lockReady(long experienceId) {
        Experience experience = experiences.findOwnedForUpdate(experienceId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("EXPERIENCE_NOT_FOUND",
                        "경험 " + experienceId + " 이(가) 없습니다"));
        if (!experience.hasStarSlots()) {
            throw new ApiException(HttpStatus.CONFLICT, "STAR_NOT_READY", "STAR 칸이 아직 없습니다");
        }
        return experience;
    }

    private static StarSlot slotOf(Experience experience, StarSlotName name) {
        return experience.getStarSlots().stream().filter(s -> s.getSlot() == name).findFirst()
                .orElse(StarSlot.empty(name, null));
    }

    // 가리키던 근거 · 보완이 사라진 칸은 빈칸이다(응답과 같은 규칙)
    private static boolean isFilled(Experience experience, StarSlot slot) {
        return slot.isFilled() && StarView.sourceExists(experience, slot);
    }

    // 한도는 String.length()(UTF-16) 기준 — FE(JS length)와 같은 숫자
    private static String text(String value) {
        if (value == null) {
            throw ApiException.invalidRequest("글이 없습니다");
        }
        if (value.length() > MAX_TEXT_LENGTH) {
            throw ApiException.invalidRequest("글은 " + MAX_TEXT_LENGTH + "자까지입니다");
        }
        return value;
    }

    private static StarSlotName slotName(String value) {
        return StarSlotName.parse(value)
                .orElseThrow(() -> ApiException.invalidRequest("slot 은 S · T · A · R 중 하나여야 합니다: " + value));
    }
}
