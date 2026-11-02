package com.woowangthon.backend.activity;

import com.woowangthon.backend.common.PatchBody;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/experience-api.md — 활동 목록 · 세부 · 수정 (SPEC S-07 · S-08 · S-09 · S-10). */
@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private final ActivityService service;

    public ActivityController(ActivityService service) {
        this.service = service;
    }

    @GetMapping
    public ActivityResponses.Timeline timeline() {
        return service.timeline();
    }

    @GetMapping("/{activityId}")
    public ActivityResponses.Detail detail(@PathVariable long activityId) {
        return service.detail(activityId);
    }

    @PatchMapping("/{activityId}")
    public ActivityResponses.Summary update(@PathVariable long activityId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.update(activityId, new PatchBody(body));
    }
}
