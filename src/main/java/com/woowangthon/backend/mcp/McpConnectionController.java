package com.woowangthon.backend.mcp;

import com.woowangthon.backend.common.ApiException;
import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/mcp-api.md — 연결 상태 · 주소 만들기 · 쓰기 켜기 · 끊기 (SPEC F-11 · S-32 · S-52). 로그인해야 쓴다(/api 아래). */
@RestController
@RequestMapping("/api/mcp/connection")
public class McpConnectionController {

    private final McpConnectionService service;

    public McpConnectionController(McpConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public McpConnectionService.Status status() {
        return service.status(CurrentUser.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public McpConnectionService.Created create() {
        return service.create(CurrentUser.id());
    }

    @PatchMapping
    public McpConnectionService.Status setWrite(@RequestBody(required = false) Map<String, Object> body) {
        Boolean writeEnabled = new PatchBody(body).bool("writeEnabled").map(PatchBody.Field::value).orElse(null);
        if (writeEnabled == null) {
            throw ApiException.invalidRequest("writeEnabled 가 없습니다");
        }
        return service.setWriteEnabled(CurrentUser.id(), writeEnabled);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete() {
        service.delete(CurrentUser.id());
    }
}
