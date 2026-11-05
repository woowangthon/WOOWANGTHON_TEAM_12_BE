package com.woowangthon.backend.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 접근 토큰 — 로그인 세션 밖에서 사용자 데이터를 읽는 열쇠(V9). 원문 대신 SHA-256 해시만 둔다.
 * READ · READ_WRITE 는 사용자의 MCP 연결 주소 하나의 두 상태, CHAGOK 은 차곡이 일마다 만드는 토큰(만료 있음 · mcp-api.md 「토큰 범위」).
 * 바뀐 칸만 UPDATE 한다 — 요청마다 찍는 lastUsedAt 이 그새 끈 쓰기(scope)를 예전 값으로 덮지 않게.
 */
@Entity
@DynamicUpdate
@Table(name = "access_token")
public class AccessToken {

    public static final String SCOPE_READ = "READ";
    public static final String SCOPE_READ_WRITE = "READ_WRITE";
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

    void changeScope(String scope) {
        this.scope = scope;
    }

    void rename(String name) {
        this.name = name;
    }

    /** 표시용 클라이언트 이름 — 없으면 null. 권한 · 식별에 쓰지 않는다. */
    public String getName() {
        return name;
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
