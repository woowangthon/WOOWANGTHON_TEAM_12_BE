package com.woowangthon.backend.experience;

import com.woowangthon.backend.common.CurrentUser;
import com.woowangthon.backend.common.PatchBody;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/experience-api.md — 경험 수정 · 직접 추가 · 지우기 · 옮기기 (SPEC S-10 · S-11 · S-12). */
@RestController
public class ExperienceController {

    private final ExperienceService service;
    private final ExperienceSearch search;

    public ExperienceController(ExperienceService service, ExperienceSearch search) {
        this.service = service;
        this.search = search;
    }

    /** 경험 찾기(S-58) — MCP search_experiences 와 같은 검색. 쿼리는 다 선택이고 조건은 모두 AND. AI 를 부르지 않는다. */
    @GetMapping("/api/experiences")
    public ExperienceSearch.AppResult search(@RequestParam(required = false) String query,
            @RequestParam(required = false) String competencies, @RequestParam(required = false) String year,
            @RequestParam(required = false) String sourceStatus, @RequestParam(required = false) String category,
            @RequestParam(required = false) String saved, @RequestParam(required = false) Integer limit) {
        return search.searchForApp(CurrentUser.id(), query, competencies, year, sourceStatus, category, saved, limit);
    }

    @PatchMapping("/api/experiences/{experienceId}")
    public ExperienceResponse update(@PathVariable long experienceId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.update(experienceId, new PatchBody(body));
    }

    @PostMapping("/api/activities/{activityId}/experiences")
    @ResponseStatus(HttpStatus.CREATED)
    public ExperienceResponse add(@PathVariable long activityId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.add(activityId, new PatchBody(body));
    }

    @PostMapping("/api/experiences/{experienceId}/move")
    public ExperienceResponse move(@PathVariable long experienceId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.move(experienceId, new PatchBody(body));
    }

    /** 경험 합치기(S-70) — 주소의 경험이 남는다. */
    @PostMapping("/api/experiences/{keepId}/merge")
    public ExperienceResponse merge(@PathVariable long keepId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.merge(keepId, new PatchBody(body));
    }

    @PutMapping("/api/experiences/{experienceId}/shared")
    public ExperienceResponse setShared(@PathVariable long experienceId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.setShared(experienceId, new PatchBody(body));
    }

    @PutMapping("/api/experiences/{experienceId}/saved")
    public ExperienceResponse setSaved(@PathVariable long experienceId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.setSaved(experienceId, new PatchBody(body));
    }

    @DeleteMapping("/api/experiences/{experienceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long experienceId) {
        service.delete(experienceId);
    }
}
