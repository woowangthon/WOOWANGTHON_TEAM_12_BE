package com.woowangthon.backend.mcp;

import com.woowangthon.backend.common.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/mcp-api.md 「변경 기록」 — 보기 · 되돌리기 · 맞아요 (SPEC F-11 · S-53 · S-54). 토큰이 아니라 로그인 세션으로 쓴다(/api 아래). */
@RestController
@RequestMapping("/api/mcp/changes")
public class McpChangeController {

    private final McpChangeService service;

    public McpChangeController(McpChangeService service) {
        this.service = service;
    }

    @GetMapping
    public McpChangeResponses.Changes list() {
        return service.list(CurrentUser.id());
    }

    @PostMapping("/{changeId}/undo")
    public McpChangeResponses.Undone undo(@PathVariable long changeId) {
        return service.undo(CurrentUser.id(), changeId);
    }

    @PostMapping("/{changeId}/confirm")
    public McpChangeResponses.Confirmed confirm(@PathVariable long changeId) {
        return service.confirm(CurrentUser.id(), changeId);
    }
}
