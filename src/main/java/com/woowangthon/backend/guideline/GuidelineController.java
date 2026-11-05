package com.woowangthon.backend.guideline;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/guideline-api.md — 작성 지침 (SPEC F-09 S-49). */
@RestController
@RequestMapping("/api/guidelines")
public class GuidelineController {

    private final GuidelineService service;

    public GuidelineController(GuidelineService service) {
        this.service = service;
    }

    @GetMapping
    public GuidelineService.GuidelineList list() {
        return service.list();
    }

    @PostMapping("/extract")
    public GuidelineService.ExtractResult extract(@RequestBody GuidelineService.ExtractRequest request) {
        return service.extract(request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GuidelineService.GuidelineList save(@RequestBody GuidelineService.SaveRequest request) {
        return service.save(request);
    }

    @PatchMapping("/{guidelineId}")
    public GuidelineService.GuidelineView update(@PathVariable long guidelineId, @RequestBody Map<String, Object> body) {
        return service.update(guidelineId, body);
    }

    @DeleteMapping("/{guidelineId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long guidelineId) {
        service.delete(guidelineId);
    }
}
