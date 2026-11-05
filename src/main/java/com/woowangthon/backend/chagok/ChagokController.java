package com.woowangthon.backend.chagok;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/chagok-api.md — 일 시작 · 진행 읽기 · 확인하고 실행 · 그만두기 (SPEC F-10 · S-31). */
@RestController
@RequestMapping("/api/chagok/runs")
public class ChagokController {

    private final ChagokService service;
    private final ChagokRunner runner;
    private final ChagokConfirmer confirmer;

    public ChagokController(ChagokService service, ChagokRunner runner, ChagokConfirmer confirmer) {
        this.service = service;
        this.runner = runner;
        this.confirmer = confirmer;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ChagokService.Started start(@RequestBody ChagokService.RunRequest request) {
        ChagokService.Launch launch = service.start(request);
        runner.run(launch);
        return new ChagokService.Started(launch.runId());
    }

    @GetMapping("/{runId}")
    public ChagokService.Run get(@PathVariable long runId) {
        return service.get(runId);
    }

    @PostMapping("/{runId}/confirm")
    public ChagokService.Run confirm(@PathVariable long runId) {
        return confirmer.confirm(runId);
    }

    @PostMapping("/{runId}/cancel")
    public ChagokService.Run cancel(@PathVariable long runId) {
        ChagokService.Run run = service.cancel(runId);
        runner.stop(runId);
        return run;
    }
}
