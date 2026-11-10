package com.woowangthon.backend.revision;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.question.Question;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수정 이력을 남긴다(계약 「언제 남나」). 고치는 서비스가 같은 트랜잭션 안에서 부른다 — 따로 쓰는 API 가 없다.
 * 스냅샷이 하나도 안 바뀐 저장은 남기지 않고 null 을 돌려준다.
 */
@Component
public class RevisionRecorder {

    private final RevisionRepository revisions;
    private final JsonMapper json;

    public RevisionRecorder(RevisionRepository revisions, JsonMapper json) {
        this.revisions = revisions;
        this.json = json;
    }

    /** 고친 경험 — before 는 고치기 전에 ExperienceSnapshot.of 로 떠 둔 것, 뒤 값은 지금 경험에서 읽는다. */
    public Revision experience(Experience experience, ExperienceSnapshot before, RevisionKind kind,
            RevisionOrigin origin, Long restoredFromId) {
        return save(experience.getActivity().getUserId(), RevisionTargetType.EXPERIENCE, experience.getId(), kind,
                origin, restoredFromId, TargetInfo.of(experience), before, ExperienceSnapshot.of(experience));
    }

    public Revision activity(Activity activity, ActivitySnapshot before, RevisionKind kind, RevisionOrigin origin,
            Long restoredFromId) {
        return save(activity.getUserId(), RevisionTargetType.ACTIVITY, activity.getId(), kind, origin, restoredFromId,
                TargetInfo.none(), before, ActivitySnapshot.of(activity));
    }

    /** 답은 문항마다 하나라 대상 번호는 문항 ID 다. before · after 는 kind 별 규칙대로 호출하는 쪽이 정한 글(계약 「모양」). */
    public Revision answer(Question question, String beforeText, String afterText, RevisionKind kind,
            RevisionOrigin origin, Long restoredFromId) {
        return save(question.getUserId(), RevisionTargetType.ANSWER, question.getId(), kind, origin, restoredFromId,
                TargetInfo.of(question), new AnswerSnapshot(beforeText), new AnswerSnapshot(afterText));
    }

    private Revision save(long userId, RevisionTargetType type, long targetId, RevisionKind kind,
            RevisionOrigin origin, Long restoredFromId, TargetInfo target, Object before, Object after) {
        if (before.equals(after)) {
            return null;
        }
        return revisions.saveAndFlush(new Revision(userId, type, targetId, kind, origin, restoredFromId, target,
                json.writeValueAsString(before), json.writeValueAsString(after)));
    }
}
