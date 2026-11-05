package com.woowangthon.backend.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 접근 토큰 — 로그인 세션 밖에서 사용자 데이터를 읽는 열쇠(V9). 원문 대신 SHA-256 해시만 둔다.
 * READ 는 사용자의 MCP 연결 주소 하나, CHAGOK 은 차곡이 일마다 만드는 토큰(만료 있음 · mcp-api.md 「토큰 범위」).
 */
@Entity
@Table(name = "access_token")
public class AccessToken {

    public static final String SCOPE_READ = "READ";
    public static final String SCOPE_CHAGOK = "CHAGOK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String tokenHash;

    @Column(nullable = false)
    private String scope;

    private String name;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime lastUsedAt;

    private OffsetDateTime expiresAt;

    protected AccessToken() {
    }

    AccessToken(Long userId, String tokenHash, String scope, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.scope = scope;
        this.expiresAt = expiresAt;
    }

    boolean isExpired() {
        return expiresAt != null && !OffsetDateTime.now().isBefore(expiresAt);
    }

    void markUsed() {
        this.lastUsedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getScope() {
        return scope;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getLastUsedAt() {
        return lastUsedAt;
    }
}
