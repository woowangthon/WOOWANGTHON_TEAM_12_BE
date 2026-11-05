package com.woowangthon.backend.activity;

import com.woowangthon.backend.common.PatchBody;
import java.util.Map;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** ETC docs/contracts/experience-api.md 「이어짐 제안 받기」 · 「이어짐 정하기」(SPEC S-35). */
@RestController
public class ActivityLinkController {

    private final ActivityLinkService service;

    public ActivityLinkController(ActivityLinkService service) {
        this.service = service;
    }

    @PostMapping("/api/activities/{activityId}/link-suggestions")
    public ActivityResponses.Links suggest(@PathVariable long activityId) {
        return service.suggest(activityId);
    }

    @PatchMapping("/api/activity-links/{linkId}")
    public ActivityResponses.Link decide(@PathVariable long linkId,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.decide(linkId, new PatchBody(body));
    }
}
