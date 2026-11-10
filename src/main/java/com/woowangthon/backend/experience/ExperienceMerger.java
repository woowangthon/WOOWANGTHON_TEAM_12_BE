package com.woowangthon.backend.experience;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 경험 합치기(S-70)에서 지울 경험을 가리키던 것을 전부 남길 경험으로 바꾼다 — 계약 「경험 합치기」 표 그대로.
 * 근거 · 보완은 id 를 그대로 둔 채 experience_id 만 바꾼다(추천 · 초안의 evidence 가 이 id 를 가리킨다).
 * 추천 · 초안 · 근거 점검 쪽 표는 경험에 외래 키가 없고 배열 · 순서 칸이 섞여 있어 SQL 로 직접 바꾼다.
 * 호출하는 쪽 트랜잭션 안에서 부르고, 부른 뒤 영속성 컨텍스트를 비워 다시 읽는다.
 */
@Component
class ExperienceMerger {

    private final EntityManager em;

    ExperienceMerger(EntityManager em) {
        this.em = em;
    }

    void repoint(long keepId, long fromId) {
        // 근거 · 보완 — 하나도 잃지 않는다. 같은 내용이어도 줄이지 않는다
        update("update citation set experience_id = :keepId where experience_id = :fromId", keepId, fromId);
        update("update supplement set experience_id = :keepId where experience_id = :fromId", keepId, fromId);

        // 지원 추천 — 한 문항에 둘 다 있으면 앞 순위 하나만(그 reason · emphasis · gaps · evidence 그대로). 뒤 것은 지운다
        update("update question_recommendation set experience_id = :keepId where experience_id = :fromId", keepId,
                fromId);
        update("""
                delete from question_recommendation r using (
                    select id, row_number() over (partition by question_id order by position, id) as rn
                    from question_recommendation where experience_id = :keepId) x
                where r.id = x.id and x.rn > 1""", keepId, fromId);

        // 요구사항 — 배열에서 바꾸고, 둘이 다 있었으면 앞의 하나만 남긴다(순서 그대로)
        update("""
                update question_requirement set experience_ids = coalesce((
                    select array_agg(s.v order by s.first_ord)
                    from (select t.v, min(t.ord) as first_ord
                          from unnest(array_replace(experience_ids, :fromId, :keepId)) with ordinality as t(v, ord)
                          group by t.v) s), '{}')
                where :fromId = any(experience_ids)""", keepId, fromId);

        // 되묻기 · 초안의 빠진 사실 · 확정 답의 새 경험 후보 — 줄이지 않는다
        update("update clarification set experience_id = :keepId where experience_id = :fromId", keepId, fromId);
        update("update draft_omitted set experience_id = :keepId where experience_id = :fromId", keepId, fromId);
        update("update new_fact_candidate set experience_id = :keepId where experience_id = :fromId", keepId, fromId);

        // 초안에 고른 경험 · 한 문장의 근거 — 둘이 같이 있으면 앞의 하나만, 순서 칸(0 부터)은 다시 맨다
        repointOrdered("draft_experience", "draft_id", "experience_id", false, keepId, fromId);
        repointOrdered("draft_sentence_evidence", "sentence_id", "ref_id", true, keepId, fromId);
        repointOrdered("grounding_sentence_evidence", "sentence_id", "ref_id", true, keepId, fromId);
    }

    /**
     * (group, position) 이 기본 키인 표 — 둘이 한 묶음에 같이 있으면 뒤의 것을 지우고 그 묶음의 position 을 0 부터 다시 맨다.
     * 기본 키가 순간 겹치지 않게 음수로 한 번 옮겼다가 되돌린다(SupplementRepository 와 같은 방법).
     * typed 면 type = 'EXPERIENCE' 인 줄만 본다(문장 근거는 근거 · 보완 번호와 한 칸을 같이 쓴다).
     */
    private void repointOrdered(String table, String group, String ref, boolean typed, long keepId, long fromId) {
        String type = typed ? " and type = 'EXPERIENCE'" : "";
        String typeA = typed ? " and a.type = 'EXPERIENCE' and b.type = 'EXPERIENCE'" : "";

        Query both = em.createNativeQuery("select distinct a.%s from %s a join %s b on a.%s = b.%s "
                .formatted(group, table, table, group, group)
                + "where a." + ref + " = :keepId and b." + ref + " = :fromId" + typeA);
        both.setParameter("keepId", keepId);
        both.setParameter("fromId", fromId);
        List<Long> groups = ((List<?>) both.getResultList()).stream().map(g -> ((Number) g).longValue()).toList();

        update("update %s set %s = :keepId where %s = :fromId%s".formatted(table, ref, ref, type), keepId, fromId);
        if (groups.isEmpty()) {
            return;
        }

        Query dups = em.createNativeQuery("""
                delete from %1$s t using (
                    select %2$s, position, row_number() over (partition by %2$s order by position) as rn
                    from %1$s where %3$s = :keepId%4$s and %2$s in (:groups)) x
                where t.%2$s = x.%2$s and t.position = x.position and x.rn > 1""".formatted(table, group, ref, type));
        dups.setParameter("keepId", keepId);
        dups.setParameter("groups", groups);
        dups.executeUpdate();

        Query negative = em.createNativeQuery("""
                update %1$s t set position = -1 - n.rn
                from (select %2$s, position, row_number() over (partition by %2$s order by position) - 1 as rn
                      from %1$s where %2$s in (:groups)) n
                where t.%2$s = n.%2$s and t.position = n.position""".formatted(table, group));
        negative.setParameter("groups", groups);
        negative.executeUpdate();

        Query back = em.createNativeQuery(
                "update %1$s set position = -1 - position where %2$s in (:groups) and position < 0"
                        .formatted(table, group));
        back.setParameter("groups", groups);
        back.executeUpdate();
    }

    private void update(String sql, long keepId, long fromId) {
        Query query = em.createNativeQuery(sql);
        // 문장에 없는 이름은 받지 않는다 — 추천 중복 지우기는 keepId 만 쓴다
        if (sql.contains(":keepId")) {
            query.setParameter("keepId", keepId);
        }
        if (sql.contains(":fromId")) {
            query.setParameter("fromId", fromId);
        }
        query.executeUpdate();
    }
}
