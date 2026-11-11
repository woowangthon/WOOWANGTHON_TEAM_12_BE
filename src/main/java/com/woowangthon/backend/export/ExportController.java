package com.woowangthon.backend.export;

import com.woowangthon.backend.common.CurrentUser;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/export-api.md — 전체 · 활동 하나 · 지원 하나를 Markdown 파일로 (SPEC S-33). */
@RestController
@RequestMapping("/api/exports")
public class ExportController {

    // RFC 7763
    static final MediaType TEXT_MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final ExportService service;

    public ExportController(ExportService service) {
        this.service = service;
    }

    @GetMapping("/all")
    public ResponseEntity<byte[]> all() {
        return file(service.all(CurrentUser.id()));
    }

    @GetMapping("/activities/{activityId}")
    public ResponseEntity<byte[]> activity(@PathVariable long activityId) {
        return file(service.activity(CurrentUser.id(), activityId));
    }

    @GetMapping("/applications/{applicationId}")
    public ResponseEntity<byte[]> application(@PathVariable long applicationId) {
        return file(service.application(CurrentUser.id(), applicationId));
    }

    // UTF-8 · BOM 없음 · 줄바꿈 \n. Access-Control-Expose-Headers 는 CorsConfig 가 교차 출처 요청에 붙인다
    private static ResponseEntity<byte[]> file(ExportService.Exported exported) {
        return ResponseEntity.ok()
                .contentType(TEXT_MARKDOWN)
                .header(HttpHeaders.CONTENT_DISPOSITION, exported.fileName().contentDisposition())
                .cacheControl(CacheControl.noStore())
                .body(exported.markdown().getBytes(StandardCharsets.UTF_8));
    }
}
