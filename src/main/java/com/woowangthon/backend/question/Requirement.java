package com.woowangthon.backend.question;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Arrays;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 요구사항 — AI 가 문항을 쪼갠 「이 지원처가 보려는 것」 하나와 그 판정(SPEC R-03 용어). */
@Embeddable
public class Requirement {

    @Column(nullable = false)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequirementStatus status;

    // 이 요구사항을 받치는 경험 ID — 경험이 지워져도 남아 있어 조회할 때 뺀다
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "bigint[]")
    private Long[] experienceIds = new Long[0];

    protected Requirement() {
    }

    public Requirement(String text, RequirementStatus status, List<Long> experienceIds) {
        this.text = text;
        this.status = status;
        this.experienceIds = experienceIds.toArray(Long[]::new);
    }

    public String getText() {
        return text;
    }

    public RequirementStatus getStatus() {
        return status;
    }

    public List<Long> getExperienceIds() {
        return Arrays.asList(experienceIds);
    }
}
