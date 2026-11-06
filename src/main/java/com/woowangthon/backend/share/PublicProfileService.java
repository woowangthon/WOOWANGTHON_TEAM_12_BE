package com.woowangthon.backend.share;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import com.woowangthon.backend.experience.CitationRepository;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.share.PublicProfileResponses.PublicActivity;
import com.woowangthon.backend.share.PublicProfileResponses.PublicExperience;
import com.woowangthon.backend.share.PublicProfileResponses.PublicProfileView;
import com.woowangthon.backend.share.PublicProfileResponses.Settings;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공개 프로필 링크(S-65 · S-66, 계약 share-api.md). AI 를 부르지 않고 하루 한도에 세지 않는다. */
@Service
public class PublicProfileService {

    private static final int TOKEN_BYTES = 16;
    // TOKEN_BYTES 를 패딩 없는 base64url 로 적은 모양
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final int MAX_TITLE = 100;

    private final SecureRandom random = new SecureRandom();
    private final PublicProfileRepository profiles;
    private final ExperienceRepository experiences;
    private final CitationRepository citations;

    public PublicProfileService(PublicProfileRepository profiles, ExperienceRepository experiences,
            CitationRepository citations) {
        this.profiles = profiles;
        this.experiences = experiences;
        this.citations = citations;
    }

    @Transactional(readOnly = true)
    public Settings settings() {
        long userId = CurrentUser.id();
        return settings(userId, profiles.findById(userId).orElse(null));
    }

    /** 제목만 바꾼다 — 링크가 꺼져 있어도 되고 주소는 그대로다. */
    @Transactional
    public Settings rename(PatchBody body) {
        String title = parseTitle(body);
        long userId = CurrentUser.id();
        PublicProfile profile = lockedProfile(userId);
        profile.rename(title);
        return settings(userId, profile);
    }

    /** 언제나 새 토큰 — 꺼져 있었으면 켜고 켜져 있었으면 바꾼다. 실릴 경험이 없으면 아무것도 바꾸지 않는다. */
    @Transactional
    public Settings enableLink() {
        long userId = CurrentUser.id();
        if (experiences.countVisible(userId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_SHARED_EXPERIENCES", "공개할 경험을 먼저 골라 주세요");
        }
        PublicProfile profile = lockedProfile(userId);
        profile.replaceToken(newToken());
        return settings(userId, profile);
    }

    /** 제목 · 고른 경험은 남는다. 꺼져 있어도 같은 결과다. */
    @Transactional
    public void disableLink() {
        profiles.findForUpdate(CurrentUser.id()).ifPresent(PublicProfile::disableLink);
    }

    /** 공개 읽기 — 요청 때마다 지금 값으로 만든다. 꺼진 · 바꾼 · 없는 · 형식이 틀린 주소는 응답이 전부 같다(S-66). */
    @Transactional(readOnly = true)
    public PublicProfileView read(String token) {
        if (!TOKEN.matcher(token).matches()) {
            throw notFound();
        }
        PublicProfile profile = profiles.findByToken(token).orElseThrow(PublicProfileService::notFound);

        List<Experience> visible = experiences.findVisible(profile.getUserId());
        Map<Long, List<String>> sourceNames = sourceNames(visible);
        Map<Activity, List<PublicExperience>> byActivity = new LinkedHashMap<>();
        for (Experience e : visible) {
            byActivity.computeIfAbsent(e.getActivity(), a -> new ArrayList<>())
                    .add(PublicExperience.of(e, sourceNames.getOrDefault(e.getId(), List.of())));
        }
        return new PublicProfileView(profile.getTitle(),
                byActivity.entrySet().stream().map(row -> PublicActivity.of(row.getKey(), row.getValue())).toList());
    }

    // 원본에서 찾은 근거의 파일 이름만, 처음 나온 순서로. 폴더 이름도 비공개 기록이라 마지막 / 뒤만 싣는다
    private Map<Long, List<String>> sourceNames(List<Experience> visible) {
        if (visible.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> names = new HashMap<>();
        for (CitationRepository.MatchedSource row : citations
                .findMatchedSources(visible.stream().map(Experience::getId).toList())) {
            String fileName = row.getSourceName().substring(row.getSourceName().lastIndexOf('/') + 1);
            List<String> ofExperience = names.computeIfAbsent(row.getExperienceId(), id -> new ArrayList<>());
            if (!ofExperience.contains(fileName)) {
                ofExperience.add(fileName);
            }
        }
        return names;
    }

    private PublicProfile lockedProfile(long userId) {
        profiles.insertIfAbsent(userId);
        return profiles.findForUpdate(userId).orElseThrow();
    }

    private Settings settings(long userId, PublicProfile profile) {
        String token = profile == null ? null : profile.getToken();
        return new Settings(profile == null ? null : profile.getTitle(), token != null, token,
                experiences.countShared(userId), experiences.countVisible(userId));
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String parseTitle(PatchBody body) {
        PatchBody.Field<String> field = body.string("title")
                .orElseThrow(() -> ApiException.invalidRequest("title 이 없습니다"));
        if (field.value() == null) {
            return null;
        }
        String title = field.value().strip();
        if (title.length() > MAX_TITLE) {
            throw ApiException.invalidRequest("title 은 " + MAX_TITLE + "자 이하여야 합니다");
        }
        if (title.contains("\n") || title.contains("\r")) {
            throw ApiException.invalidRequest("title 에 줄바꿈을 넣을 수 없습니다");
        }
        return title.isEmpty() ? null : title;
    }

    private static ApiException notFound() {
        return ApiException.notFound("PUBLIC_PROFILE_NOT_FOUND", "없는 페이지입니다");
    }
}
