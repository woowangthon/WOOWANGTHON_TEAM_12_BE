package com.woowangthon.backend.experience;

import com.woowangthon.backend.common.PatchBody;
import java.util.Map;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/experience-api.md — 경험 수정 (SPEC S-10). */
@RestController
@RequestMapping("/api/experiences")
public class ExperienceController {

    private final ExperienceService service;

    public ExperienceController(ExperienceService service) {
        this.service = service;
    }

    @PatchMapping("/{experienceId}")
    public ExperienceResponse update(@PathVariable long experienceId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.update(experienceId, new PatchBody(body));
    }
}
