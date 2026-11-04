package com.woowangthon.backend.mcp;

import com.woowangthon.backend.common.Times;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 연결 주소(ETC docs/contracts/mcp-api.md 「연결 주소」). 사용자당 하나 — 다시 만들면 예전 토큰은 지운다(바로 401).
 * 토큰은 32바이트 무작위 값을 URL 에 그대로 쓰는 base64url 로 내보내고, DB 에는 SHA-256 해시만 둔다.
 */
@Service
public class McpConnectionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccessTokenRepository tokens;
    private final String baseUrl;

    public McpConnectionService(AccessTokenRepository tokens, @Value("${app.mcp.base-url}") String baseUrl) {
        this.tokens = tokens;
        // 주소 앞부분은 설정값 — 요청 호스트로 짐작하지 않는다(Cloudflare Tunnel 뒤라서)
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    public record Status(boolean connected, OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {
    }

    public record Created(String url, OffsetDateTime createdAt) {
    }

    @Transactional(readOnly = true)
    public Status status(long userId) {
        return tokens.findFirstByUserIdOrderByIdDesc(userId)
                .map(t -> new Status(true, Times.seoul(t.getCreatedAt()),
                        t.getLastUsedAt() == null ? null : Times.seoul(t.getLastUsedAt())))
                .orElse(new Status(false, null, null));
    }

    // 지금 토큰은 MCP 연결 한 종류뿐이라 사용자의 토큰을 통째로 지운다. 다른 종류가 들어오면 그때 종류로 거른다
    @Transactional
    public Created create(long userId) {
        tokens.deleteAllOfUser(userId);
        String token = newToken();
        AccessToken saved = tokens.saveAndFlush(new AccessToken(userId, hash(token), AccessToken.SCOPE_READ));
        return new Created(baseUrl + token, Times.seoul(saved.getCreatedAt()));
    }

    @Transactional
    public void delete(long userId) {
        tokens.deleteAllOfUser(userId);
    }

    /** 주소의 토큰 → 주인 사용자 ID. 있으면 lastUsedAt 을 지금으로. 없거나 끊긴 토큰이면 비어 있다. */
    @Transactional
    public Optional<Long> authenticate(String token) {
        return tokens.findByTokenHash(hash(token)).map(t -> {
            t.markUsed();
            return t.getUserId();
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
