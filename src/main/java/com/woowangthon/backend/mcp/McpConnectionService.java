package com.woowangthon.backend.mcp;

import com.woowangthon.backend.common.Times;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 연결 주소(ETC docs/contracts/mcp-api.md 「연결 주소」). 사용자당 하나 — 다시 만들면 예전 토큰은 지운다(바로 401).
 * 차곡이 일의 CHAGOK 토큰도 여기서 만들지만 연결 주소와는 따로 산다 — 연결 화면에 안 보이고 위 규칙에 안 세인다(「토큰 범위」).
 * 토큰은 32바이트 무작위 값을 URL 에 그대로 쓰는 base64url 로 내보내고, DB 에는 SHA-256 해시만 둔다.
 */
@Service
public class McpConnectionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CHAGOK_TOKEN_LIFETIME = Duration.ofMinutes(10);

    private final AccessTokenRepository tokens;
    private final String baseUrl;
    private final String internalBaseUrl;

    public McpConnectionService(AccessTokenRepository tokens, @Value("${app.mcp.base-url}") String baseUrl,
            @Value("${app.mcp.internal-base-url}") String internalBaseUrl) {
        this.tokens = tokens;
        // 주소 앞부분은 설정값 — 요청 호스트로 짐작하지 않는다(Cloudflare Tunnel 뒤라서)
        this.baseUrl = withSlash(baseUrl);
        this.internalBaseUrl = withSlash(internalBaseUrl);
    }

    private static String withSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    /** 토큰의 주인과 범위. */
    public record Authenticated(long userId, String scope) {
    }

    /** 일 하나의 토큰 — url 은 AI 서버가 부르는 내부망 주소이고 토큰 원문이 들어 있다(로그에 남기지 않는다). */
    public record ChagokToken(long id, String url) {
    }

    public record Status(boolean connected, OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {
    }

    public record Created(String url, OffsetDateTime createdAt) {
    }

    @Transactional(readOnly = true)
    public Status status(long userId) {
        return tokens.findFirstByUserIdAndScopeOrderByIdDesc(userId, AccessToken.SCOPE_READ)
                .map(t -> new Status(true, Times.seoul(t.getCreatedAt()),
                        t.getLastUsedAt() == null ? null : Times.seoul(t.getLastUsedAt())))
                .orElse(new Status(false, null, null));
    }

    @Transactional
    public Created create(long userId) {
        tokens.deleteOfUser(userId, AccessToken.SCOPE_READ);
        String token = newToken();
        AccessToken saved = tokens.saveAndFlush(new AccessToken(userId, hash(token), AccessToken.SCOPE_READ, null));
        return new Created(baseUrl + token, Times.seoul(saved.getCreatedAt()));
    }

    @Transactional
    public void delete(long userId) {
        tokens.deleteOfUser(userId, AccessToken.SCOPE_READ);
    }

    /** 차곡이 일 하나에만 쓰는 토큰을 만든다. CHAGOK_TOKEN_LIFETIME 또는 revoke 까지 — 먼저 오는 쪽. */
    @Transactional
    public ChagokToken createChagok(long userId) {
        String token = newToken();
        AccessToken saved = tokens.save(new AccessToken(userId, hash(token), AccessToken.SCOPE_CHAGOK,
                OffsetDateTime.now().plus(CHAGOK_TOKEN_LIFETIME)));
        return new ChagokToken(saved.getId(), internalBaseUrl + token);
    }

    /** 일이 끝난 토큰을 지운다 — 바로 401. */
    @Transactional
    public void revoke(long tokenId) {
        tokens.deleteById(tokenId);
    }

    /** 서버가 일 도중 꺼져 남은 CHAGOK 토큰을 지운다(InterruptedRunCleaner). */
    @Transactional
    public int revokeAllChagok() {
        return tokens.deleteAllOfScope(AccessToken.SCOPE_CHAGOK);
    }

    /** 주소의 토큰 → 주인 · 범위. 있으면 lastUsedAt 을 지금으로. 없거나 끊기거나 만료된 토큰이면 비어 있다. */
    @Transactional
    public Optional<Authenticated> authenticate(String token) {
        return tokens.findByTokenHash(hash(token)).filter(t -> !t.isExpired()).map(t -> {
            t.markUsed();
            return new Authenticated(t.getUserId(), t.getScope());
        });
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
