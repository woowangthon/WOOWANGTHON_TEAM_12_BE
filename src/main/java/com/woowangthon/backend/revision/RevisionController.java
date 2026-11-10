package com.woowangthon.backend.revision;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ETC docs/contracts/revision-api.md — 최근 수정 · 항목의 수정 이력 · 그때 값 · 수정 전 값으로 되돌리기 (SPEC S-80).
 * 웹(쿠키 로그인)에서만 — 외부 AI(MCP)에는 이력 도구가 없다.
 */
@RestController
@RequestMapping("/api/revisions")
public class RevisionController {

    private final RevisionService service;

    public RevisionController(RevisionService service) {
        this.service = service;
    }

    @GetMapping
    public RevisionService.Page list(@RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return service.list(targetType, targetId, cursor, limit);
    }

    @PostMapping("/{revisionId}/restore")
    public RevisionService.RestoreResult restore(@PathVariable long revisionId,
            @RequestBody(required = false) RevisionService.RestoreRequest request) {
        return service.restore(revisionId, request == null ? null : request.to());
    }
}
