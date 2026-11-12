package com.woowangthon.backend.question;

import com.woowangthon.backend.ai.AiCallException;
import com.woowangthon.backend.ai.MatchRequest;
import com.woowangthon.backend.ai.MatchResponse;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.Citation;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.experience.Supplement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** 문장 단위 근거 — 근거 점검(S-45)과 답변 초안(S-27)이 같은 모양 · 같은 규칙을 쓴다(question-api.md `evidence`). */
@Component
public class SentenceEvidence {

    /**
     * sourceName 은 CITATION 이면 그 발췌가 나온 자료 이름, question 은 SUPPLEMENT 면 그 보완의 질문 — 그 밖엔 null(S-75).
     * actor 는 그 경험의 지금 수행 주체(S-79).
     */
    public record Ref(EvidenceType type, Long id, Long experienceId, Long activityId, String text, String sourceName,
            String question, Actor actor) {
    }

    private final ExperienceRepository experiences;

    public SentenceEvidence(ExperienceRepository experiences) {
        this.experiences = experiences;
    }

    /** 사용자의 경험 · 근거 · 보완을 지금 상태로. 키는 {@link #key}. */
    public Map<String, Ref> current(long userId) {
        Map<String, Ref> current = new HashMap<>();
        for (Experience e : experiences.findAllOfUser(userId)) {
            Long activityId = e.getActivity().getId();
            current.put(key(EvidenceType.EXPERIENCE, e.getId()),
                    new Ref(EvidenceType.EXPERIENCE, e.getId(), e.getId(), activityId, e.getContent(), null, null,
                            e.getActor()));
            for (Citation c : e.getCitations()) {
                current.put(key(EvidenceType.CITATION, c.getId()),
                        new Ref(EvidenceType.CITATION, c.getId(), e.getId(), activityId, c.getQuote(),
                                c.getSource().getName(), null, e.getActor()));
            }
            for (Supplement s : e.getSupplements()) {
                current.put(key(EvidenceType.SUPPLEMENT, s.getId()),
                        new Ref(EvidenceType.SUPPLEMENT, s.getId(), e.getId(), activityId, s.getAnswer(), null,
                                s.getQuestion(), e.getActor()));
            }
        }
        return current;
    }

    static String key(EvidenceType type, Long id) {
        return type + ":" + id;
    }

    /** 저장한 근거에 경험 · 활동 · 글을 지금 상태로 붙인다. 그새 지워진 것은 뺀다. */
    static List<Ref> resolve(List<Evidence> evidence, Map<String, Ref> current) {
        return evidence.stream().map(v -> current.get(key(v.getType(), v.getRefId()))).filter(Objects::nonNull).toList();
    }

    /** AI 에 싣는 경험 — 근거는 원본에서 찾은 것만(「원문」으로 칠하지 않게), 보완은 확인 전인 외부 AI 답만 빼고 전부(S-56). */
    static MatchRequest.ExperiencePart matchedPart(Experience e) {
        return new MatchRequest.ExperiencePart(e.getId(), null, e.getActivity().getName(), e.getActivity().getPeriod(),
                e.getContent(), e.getCompetencies(), e.getActor(), e.getSourceStatus(),
                e.getCitations().stream().filter(c -> Boolean.TRUE.equals(c.getMatched()))
                        .map(c -> new MatchRequest.CitationPart(c.getId(), c.getQuote())).toList(),
                e.getConfirmedSupplements().stream()
                        .map(s -> new MatchRequest.SupplementPart(s.getId(), s.getQuestion(), s.getAnswer())).toList(), List.of());
    }

    /** AI 에 보낸 것 — 응답의 근거 ID 가 보낸 것인지 확인한다. EXPERIENCE 는 USER_ENTERED 경험만 된다. */
    static final class Sent {

        private final Set<Long> citationIds = new HashSet<>();
        private final Set<Long> supplementIds = new HashSet<>();
        private final Set<Long> userEnteredIds = new HashSet<>();

        Sent(List<MatchRequest.ExperiencePart> experiences) {
            for (MatchRequest.ExperiencePart e : experiences) {
                e.citations().forEach(c -> citationIds.add(c.id()));
                e.supplements().forEach(s -> supplementIds.add(s.id()));
                if (e.sourceStatus() == SourceStatus.USER_ENTERED) {
                    userEnteredIds.add(e.id());
                }
            }
        }

        /** 받칠 수 있는 것이 하나라도 있나. */
        boolean hasBacking() {
            return !citationIds.isEmpty() || !supplementIds.isEmpty() || !userEnteredIds.isEmpty();
        }

        /** 보내지 않은 ID · 같은 근거 두 번 · 목록 밖 종류면 AiCallException(AI_FAILED). found 는 null 이어도 된다. */
        List<Evidence> checked(List<MatchResponse.EvidenceResult> found) {
            List<Evidence> evidence = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (MatchResponse.EvidenceResult e : found == null ? List.<MatchResponse.EvidenceResult>of() : found) {
                EvidenceType type = evidenceType(e.type());
                Set<Long> allowed = switch (type) {
                    case CITATION -> citationIds;
                    case SUPPLEMENT -> supplementIds;
                    case EXPERIENCE -> userEnteredIds;
                };
                if (e.id() == null || !allowed.contains(e.id())) {
                    throw AiCallException.invalidResponse("evidence 의 " + type + " " + e.id() + " 은(는) 보낸 것이 아닙니다");
                }
                if (!seen.add(key(type, e.id()))) {
                    throw AiCallException.invalidResponse("evidence 에 같은 것이 두 번 있습니다: " + type + " " + e.id());
                }
                evidence.add(new Evidence(type, e.id()));
            }
            return evidence;
        }

        private static EvidenceType evidenceType(String value) {
            return Stream.of(EvidenceType.values()).filter(t -> t.name().equals(value)).findFirst()
                    .orElseThrow(() -> AiCallException.invalidResponse(
                            "evidence[].type 은 CITATION · SUPPLEMENT · EXPERIENCE 중 하나여야 합니다: " + value));
        }
    }
}
