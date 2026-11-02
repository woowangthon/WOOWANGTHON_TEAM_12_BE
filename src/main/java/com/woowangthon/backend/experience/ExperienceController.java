package com.woowangthon.backend.experience;

import com.woowangthon.backend.common.PatchBody;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/experience-api.md — 경험 수정 · 직접 추가 · 지우기 (SPEC S-10 · S-11). */
@RestController
public class ExperienceController {

    private final ExperienceService service;

    public ExperienceController(ExperienceService service) {
        this.service = service;
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

    @DeleteMapping("/api/experiences/{experienceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long experienceId) {
        service.delete(experienceId);
    }
}
