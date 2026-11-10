package com.woowangthon.backend.activity;

import com.woowangthon.backend.common.PatchBody;
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

/** ETC docs/contracts/experience-api.md — 활동 목록 · 세부 · 수정 · 합치기 · 지우기 · 같은 활동 제안 따로 두기 (SPEC S-07 · S-08 · S-09 · S-10 · S-13 · S-34 · S-44 · S-88). */
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

    @PostMapping("/{activityId}/merge")
    public ActivityResponses.Detail merge(@PathVariable long activityId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.merge(activityId, new PatchBody(body));
    }

    @PostMapping("/{activityId}/actor")
    public ActivityService.ActorDecided decideActor(@PathVariable long activityId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.decideActor(activityId, new PatchBody(body));
    }

    @DeleteMapping("/{activityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long activityId) {
        service.delete(activityId);
    }

    @DeleteMapping("/{activityId}/merge-suggestion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismissMergeSuggestion(@PathVariable long activityId) {
        service.dismissMergeSuggestion(activityId);
    }
}
