package com.woowangthon.backend.source;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.Times;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 계약 experience-api 「GET /api/sources/{sourceId}」 — 내 자료의 저장된 글자를 읽는다(S-74). AI 를 부르지 않는다. */
@RestController
@RequestMapping("/api/sources")
public class SourceController {

    public record SourceResponse(Long id, String name, String text, OffsetDateTime createdAt) {
    }

    private final SourceRepository sources;

    public SourceController(SourceRepository sources) {
        this.sources = sources;
    }

    @GetMapping("/{sourceId}")
    public SourceResponse get(@PathVariable long sourceId) {
        Source source = sources.findByIdAndUserId(sourceId, CurrentUser.id())
                .orElseThrow(() -> ApiException.notFound("SOURCE_NOT_FOUND", "자료 " + sourceId + " 을(를) 찾을 수 없습니다"));
        return new SourceResponse(source.getId(), source.getName(), source.getText(), Times.seoul(source.getCreatedAt()));
    }
}
