package com.woowangthon.backend.experience;

import com.woowangthon.backend.activity.ActivityResponses;
import com.woowangthon.backend.activity.ActivityService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/experience-api.md 「STAR 칸 — 정리 · 빈칸 답하기 · 칸 고치기」 (SPEC F-18 · S-95 · S-96). */
@RestController
public class StarController {

    private final StarService service;
    private final StarRunner runner;
    private final ActivityService activities;

    public StarController(StarService service, StarRunner runner, ActivityService activities) {
        this.service = service;
        this.runner = runner;
        this.activities = activities;
    }

    /** 실패한 정리 「다시 시도」(분석 횟수 안 셈) — 정리 중인 활동 세부를 먼저 읽고 나서 뒤에서 돌린다. */
    @PostMapping("/api/activities/{activityId}/star")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActivityResponses.Detail sort(@PathVariable long activityId) {
        List<List<Long>> groups = service.start(activityId);
        ActivityResponses.Detail detail = activities.detail(activityId);
        runner.run(groups);
        return detail;
    }

    /** 경험 하나 다시 나누기(분석 횟수 안 셈 · BE#184) — 정리 중인 그 경험을 돌려주고 뒤에서 돌린다. */
    @PostMapping("/api/experiences/{experienceId}/star")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StarView.WithExperience resort(@PathVariable long experienceId) {
        StarView.WithExperience started = service.startOne(experienceId);
        runner.run(List.of(List.of(experienceId)));
        return started;
    }

    @PostMapping("/api/experiences/{experienceId}/star/{slot}/answer")
    public StarView.WithExperience answer(@PathVariable long experienceId, @PathVariable String slot,
            @RequestBody(required = false) StarService.AnswerRequest request) {
        return service.answer(experienceId, slot, request);
    }

    @PutMapping("/api/experiences/{experienceId}/star/{slot}")
    public StarView.WithExperience edit(@PathVariable long experienceId, @PathVariable String slot,
            @RequestBody(required = false) StarService.EditRequest request) {
        return service.edit(experienceId, slot, request);
    }
}
