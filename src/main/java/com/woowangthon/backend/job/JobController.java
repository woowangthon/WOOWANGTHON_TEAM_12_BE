package com.woowangthon.backend.job;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/experience-api.md — 자료 넣기 · 작업 상태 · 다시 시도 (SPEC S-01 · S-02). */
@RestController
@RequestMapping("/api")
public class JobController {

    private final AnalysisService service;
    private final ExtractionRunner runner;

    public JobController(AnalysisService service, ExtractionRunner runner) {
        this.service = service;
        this.runner = runner;
    }

    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponses.Submitted submit(@RequestBody AnalysisService.SourceRequest request) {
        JobResponses.Submitted submitted = service.submit(request);
        runner.run(submitted.jobId());
        return submitted;
    }

    @GetMapping("/jobs/{jobId}")
    public JobResponses.Job get(@PathVariable long jobId) {
        return service.get(jobId);
    }

    @PostMapping("/jobs/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponses.Job retry(@PathVariable long jobId) {
        JobResponses.Job job = service.retry(jobId);
        runner.run(jobId);
        return job;
    }
}
