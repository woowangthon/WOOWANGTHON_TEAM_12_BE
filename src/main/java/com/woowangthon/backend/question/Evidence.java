package com.woowangthon.backend.question;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** 추천을 받치는 근거(citation id) 또는 보완(supplement id) 하나 — 추천했을 때 그대로 둔다. */
@Embeddable
public class Evidence {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EvidenceType type;

    @Column(nullable = false)
    private Long refId;

    protected Evidence() {
    }

    public Evidence(EvidenceType type, Long refId) {
        this.type = type;
        this.refId = refId;
    }

    public EvidenceType getType() {
        return type;
    }

    public Long getRefId() {
        return refId;
    }
}
