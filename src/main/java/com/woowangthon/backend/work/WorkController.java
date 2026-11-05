package com.woowangthon.backend.work;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/work-api.md — 진행 중인 일 · 소식 읽음 (SPEC F-10 · S-47). */
@RestController
@RequestMapping("/api/work")
public class WorkController {

    private final WorkService service;

    public WorkController(WorkService service) {
        this.service = service;
    }

    @GetMapping
    public WorkService.Work get() {
        return service.get();
    }

    @PostMapping("/seen")
    public WorkService.Seen seen(@RequestBody WorkService.Seen request) {
        return service.markSeen(request);
    }
}
