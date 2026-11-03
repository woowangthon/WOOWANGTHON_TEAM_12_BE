package com.woowangthon.backend.seed;

import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.Citation;
import com.woowangthon.backend.experience.CompetenciesBy;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.CitationRepository;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.source.Source;
import com.woowangthon.backend.source.SourceKind;
import com.woowangthon.backend.source.SourceRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAMPLE(dev-seed-activities): SEED_SAMPLE=true 일 때 계약 예시(「바꿔요」 회고 · 알고리즘 스터디)를
 * 테스트 계정(TestAccountSeeder)의 원본 · 활동 · 경험 · 근거로 한 번 넣는다. 테스트 계정이 없으면 넣지 않는다. AI 가 뽑은 결과가 아니라 손으로 넣은 데이터다.
 * 자료 넣기(BE#5)가 붙기 전에 타임라인 · 세부 · 고치기를 확인하려고 둔다. 공개 전엔 끄고 지운다.
 */
@Component
@ConditionalOnProperty(name = "app.seed-sample", havingValue = "true")
@Order(1)
public class SampleSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleSeeder.class);

    static final String SOURCE_TEXT = """
            # 바꿔요 — 캠퍼스 중고거래 앱 회고 (2025년 1학기 캡스톤)

            기간: 2025-03 ~ 2025-06, 4명 팀. 나는 백엔드를 맡았다.

            - 채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.
            - 팀은 매주 금요일 스프린트 회고를 했다.
            - 배포는 팀원이 맡아 AWS 에 올렸다.

            ## 알고리즘 스터디
            2024-09 부터 지금까지 매주 문제를 풀고 있다. 내가 문제 선정을 맡는다.
            """;

    private final SourceRepository sources;
    private final ActivityRepository activities;
    private final ExperienceRepository experiences;
    private final CitationRepository citations;
    private final AppUserRepository users;
    private final String testLoginId;

    public SampleSeeder(SourceRepository sources, ActivityRepository activities,
            ExperienceRepository experiences, CitationRepository citations, AppUserRepository users,
            @Value("${app.test-account.login-id}") String testLoginId) {
        this.sources = sources;
        this.activities = activities;
        this.experiences = experiences;
        this.citations = citations;
        this.users = users;
        this.testLoginId = testLoginId;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppUser testAccount = users.findByLoginId(testLoginId).orElse(null);
        if (testAccount == null) {
            log.warn("SEED_SAMPLE=true 인데 테스트 계정이 없어 예시 데이터를 넣지 않았습니다(TEST_ACCOUNT_PASSWORD)");
            return;
        }
        long userId = testAccount.getId();
        // 활동이 하나라도 있으면 넣지 않는다 — 재시작할 때마다 늘어나지 않게
        if (activities.existsByUserId(userId)) {
            log.info("SEED_SAMPLE=true — 활동이 이미 있어서 예시 데이터를 넣지 않았습니다");
            return;
        }

        Source source = sources.save(new Source(userId, SourceKind.FILE, "바꿔요-회고.md", SOURCE_TEXT));

        Activity app = activities.save(new Activity(userId, "바꿔요 — 캠퍼스 중고거래 앱",
                new Period("2025-03", "2025-06", false)));
        experience(app, 0, "채팅 메시지가 늦게 도착하는 문제를 폴링에서 WebSocket 으로 바꿔 해결했다",
                List.of(Competency.PROBLEM_SOLVING, Competency.IMPLEMENTATION),
                Actor.ME, SourceStatus.SOURCE_CONFIRMED, source,
                "채팅 메시지가 늦게 도착하는 문제가 있었다. 내가 폴링을 WebSocket 으로 바꿔 해결했다.", true, 5);
        experience(app, 1, "매주 금요일 스프린트 회고를 했다", List.of(Competency.COLLABORATION),
                Actor.TEAM, SourceStatus.SOURCE_CONFIRMED, source,
                "팀은 매주 금요일 스프린트 회고를 했다.", true, 6);
        experience(app, 2, "서비스를 AWS 에 배포했다", List.of(Competency.IMPLEMENTATION),
                Actor.TEAM, SourceStatus.SOURCE_CONFIRMED, source,
                "배포는 팀원이 맡아 AWS 에 올렸다.", true, 7);
        // 원본에 없는 발췌문 — 「확인 필요」가 어떻게 보이는지 확인하려고 계약 예시 그대로 둔다
        experience(app, 3, "백엔드 API 설계를 맡았다", List.of(Competency.IMPLEMENTATION),
                Actor.ME, SourceStatus.NEEDS_REVIEW, source,
                "나는 백엔드 API 설계를 전부 맡았다.", false, null);

        Activity study = activities.save(new Activity(userId, "알고리즘 스터디",
                new Period("2024-09", null, true)));
        experience(study, 0, "스터디에서 매주 풀 문제를 고르는 일을 맡고 있다",
                List.of(Competency.OPERATION),
                Actor.ME, SourceStatus.SOURCE_CONFIRMED, source,
                "2024-09 부터 지금까지 매주 문제를 풀고 있다. 내가 문제 선정을 맡는다.", true, 10);

        log.warn("SEED_SAMPLE=true — 예시 활동 2개를 넣었습니다(SAMPLE, 공개 전엔 끄세요)");
    }

    private void experience(Activity activity, int position, String content, List<Competency> competencies, Actor actor,
            SourceStatus status, Source source, String quote, Boolean matched, Integer line) {
        Experience experience = experiences.save(
                new Experience(activity, content, competencies, CompetenciesBy.AI, actor, status, position));
        citations.save(new Citation(experience, source, quote, matched, line, line));
    }
}
