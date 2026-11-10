package com.woowangthon.backend.revision;

import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.question.Question;

/** 목록 한 줄에 그릴 대상 이름 — 기록한 때 값. 안 맞는 칸은 null(활동은 전부 null, 이름은 스냅샷에 있다). */
public record TargetInfo(Long activityId, String activityName, Long questionId, String questionText,
        Long applicationId, String organization) {

    static TargetInfo none() {
        return new TargetInfo(null, null, null, null, null, null);
    }

    static TargetInfo of(Experience e) {
        return new TargetInfo(e.getActivity().getId(), e.getActivity().getName(), null, null, null, null);
    }

    static TargetInfo of(Question q) {
        return new TargetInfo(null, null, q.getId(), q.getText(), q.getApplication().getId(), q.getOrganization());
    }
}
