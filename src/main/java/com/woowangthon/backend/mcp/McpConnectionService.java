package com.woowangthon.backend.mcp;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.Times;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 연결 주소(ETC docs/contracts/mcp-api.md 「연결 주소」). 사용자당 하나 — 다시 만들면 예전 토큰은 지운다(바로 404).
 * READ · READ_WRITE 는 연결 하나의 두 상태다 — 쓰기를 켜고 꺼도 주소는 그대로이고 범위만 바뀐다(S-52).
 * 차곡이 일의 CHAGOK 토큰도 여기서 만들지만 연결 주소와는 따로 산다 — 연결 화면에 안 보이고 위 규칙에 안 세인다(「토큰 범위」).
 * 토큰은 32바이트 무작위 값을 URL 에 그대로 쓰는 base64url 로 내보내고, DB 에는 SHA-256 해시만 둔다.
 */
@Service
public class McpConnectionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CHAGOK_TOKEN_LIFETIME = Duration.ofMinutes(10);
    private static final List<String> CONNECTION_SCOPES = List.of(AccessToken.SCOPE_READ, AccessToken.SCOPE_READ_WRITE);
    private static final String UNKNOWN_CLIENT = "외부 AI";
    private static final int MAX_CLIENT_LENGTH = 40;

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

    /** 토큰의 주인 · 범위와 표시용 클라이언트 이름(권한 · 식별에 쓰지 않는다). */
    public record Authenticated(long tokenId, long userId, String scope, String client) {
    }

    /** 일 하나의 토큰 — url 은 AI 서버가 부르는 내부망 주소이고 토큰 원문이 들어 있다(로그에 남기지 않는다). */
    public record ChagokToken(long id, String url) {
    }

    public record Status(boolean connected, boolean writeEnabled, OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {

        private static Status of(AccessToken t) {
            return new Status(true, AccessToken.SCOPE_READ_WRITE.equals(t.getScope()), Times.seoul(t.getCreatedAt()),
                    t.getLastUsedAt() == null ? null : Times.seoul(t.getLastUsedAt()));
        }
    }

    public record Created(String url, OffsetDateTime createdAt) {
    }

    @Transactional(readOnly = true)
    public Status status(long userId) {
        return connection(userId).map(Status::of).orElse(new Status(false, false, null, null));
    }

    /** 쓰기 켜기 · 끄기 — 주소는 그대로, 다음 MCP 요청부터 적용된다. */
    @Transactional
    public Status setWriteEnabled(long userId, boolean enabled) {
        AccessToken token = connection(userId).orElseThrow(
                () -> ApiException.notFound("MCP_CONNECTION_NOT_FOUND", "연결이 없습니다"));
        token.changeScope(enabled ? AccessToken.SCOPE_READ_WRITE : AccessToken.SCOPE_READ);
        return Status.of(token);
    }

    private Optional<AccessToken> connection(long userId) {
        return tokens.findFirstByUserIdAndScopeInOrderByIdDesc(userId, CONNECTION_SCOPES);
    }

    /** 새 연결은 쓰기가 꺼진 채(READ)로 시작한다. */
    @Transactional
    public Created create(long userId) {
        tokens.deleteOfUser(userId, CONNECTION_SCOPES);
        String token = newToken();
        AccessToken saved = tokens.saveAndFlush(new AccessToken(userId, hash(token), AccessToken.SCOPE_READ, null));
        return new Created(baseUrl + token, Times.seoul(saved.getCreatedAt()));
    }

    @Transactional
    public void delete(long userId) {
        tokens.deleteOfUser(userId, CONNECTION_SCOPES);
    }

    /** 차곡이 일 하나에만 쓰는 토큰을 만든다. CHAGOK_TOKEN_LIFETIME 또는 revoke 까지 — 먼저 오는 쪽. */
    @Transactional
    public ChagokToken createChagok(long userId) {
        String token = newToken();
        AccessToken saved = tokens.save(new AccessToken(userId, hash(token), AccessToken.SCOPE_CHAGOK,
                OffsetDateTime.now().plus(CHAGOK_TOKEN_LIFETIME)));
        return new ChagokToken(saved.getId(), internalBaseUrl + token);
    }

    /** 일이 끝난 토큰을 지운다 — 바로 404. */
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
            return new Authenticated(t.getId(), t.getUserId(), t.getScope(),
                    t.getName() == null ? UNKNOWN_CLIENT : t.getName());
        });
    }

    /** initialize 가 보낸 clientInfo.name 을 토큰에 둔다 — 요청마다 상태가 없어서. 다음 요청의 client 가 된다. */
    @Transactional
    public void rememberClient(long tokenId, String clientName) {
        tokens.findById(tokenId).ifPresent(t -> t.rename(cleanClient(clientName)));
    }

    // 제어 문자를 빼고 앞 40자 — 원본 이름 · 변경 기록에 그대로 보이는 값이라. 남는 게 없으면 null(「외部 AI」)
    private static String cleanClient(String clientName) {
        if (clientName == null) {
            return null;
        }
        String cleaned = clientName.replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        int length = cleaned.codePointCount(0, cleaned.length());
        return length <= MAX_CLIENT_LENGTH ? cleaned
                : cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_CLIENT_LENGTH)).strip();
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
