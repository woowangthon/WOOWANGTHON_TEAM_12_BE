package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.woowangthon.backend.account.AppUser;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.activity.Activity;
import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.activity.Period;
import com.woowangthon.backend.experience.Actor;
import com.woowangthon.backend.experience.Citation;
import com.woowangthon.backend.experience.CitationRepository;
import com.woowangthon.backend.experience.CompetenciesBy;
import com.woowangthon.backend.experience.Competency;
import com.woowangthon.backend.experience.Experience;
import com.woowangthon.backend.experience.ExperienceRepository;
import com.woowangthon.backend.experience.SourceStatus;
import com.woowangthon.backend.experience.Supplement;
import com.woowangthon.backend.experience.SupplementRepository;
import com.woowangthon.backend.mcp.GetActivityTool;
import com.woowangthon.backend.mcp.McpConnectionService;
import com.woowangthon.backend.source.Source;
import com.woowangthon.backend.source.SourceKind;
import com.woowangthon.backend.source.SourceRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** ETC docs/contracts/share-api.md — 프로필에 넣기 · 공개 설정 · 링크 · 공개 읽기(S-65 · S-66, BE#102). 테스트마다 롤백한다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class PublicProfileApiTest {

    static final String UNKNOWN_TOKEN = "AAAAAAAAAAAAAAAAAAAAAA";

    @Autowired
    WebApplicationContext context;

    @Autowired
    AppUserRepository users;

    @Autowired
    ActivityRepository activities;

    @Autowired
    ExperienceRepository experiences;

    @Autowired
    CitationRepository citations;

    @Autowired
    SupplementRepository supplements;

    @Autowired
    SourceRepository sources;

    @Autowired
    GetActivityTool getActivity;

    @Autowired
    EntityManager em;

    @Autowired
    ObjectMapper json;

    MockMvc web;
    MockMvc anonymous;
    long userId;
    long otherUserId;

    @BeforeEach
    void setUp() {
        userId = users.saveAndFlush(new AppUser("share-owner", "hash", null)).getId();
        otherUserId = users.saveAndFlush(new AppUser("share-other", "hash", null)).getId();
        web = TestLogin.mvc(context, userId);
        anonymous = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // --- 프로필에 넣기 ---

    @Test
    void 넣고_빼도_출처_상태는_그대로고_다른_API_응답에도_실린다() throws Exception {
        Activity activity = activity(userId, "활동");
        Experience e = experience(activity, "본문", SourceStatus.SOURCE_CONFIRMED);

        share(e.getId(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shared").value(true))
                .andExpect(jsonPath("$.sourceStatus").value("SOURCE_CONFIRMED"))
                .andExpect(jsonPath("$.competenciesBy").value("AI"));
        share(e.getId(), true).andExpect(status().isOk());
        em.flush();
        em.clear();
        web.perform(get("/api/activities/{id}", activity.getId()))
                .andExpect(jsonPath("$.experiences[0].shared").value(true));

        share(e.getId(), false).andExpect(status().isOk()).andExpect(jsonPath("$.shared").value(false));
    }

    @Test
    void 확인_필요는_넣을_수_없지만_뺄_수는_있다() throws Exception {
        Experience e = experience(activity(userId, "활동"), "본문", SourceStatus.SOURCE_CONFIRMED);
        share(e.getId(), true).andExpect(status().isOk());
        toNeedsReview(e);

        expectError(share(e.getId(), true), 409, "EXPERIENCE_NEEDS_REVIEW");
        share(e.getId(), false).andExpect(status().isOk()).andExpect(jsonPath("$.shared").value(false));
        expectError(share(e.getId(), true), 409, "EXPERIENCE_NEEDS_REVIEW");
    }

    @Test
    void 다른_사용자의_경험은_고를_수_없다() throws Exception {
        Experience others = experience(activity(otherUserId, "남의 활동"), "본문", SourceStatus.SOURCE_CONFIRMED);

        expectError(share(others.getId(), true), 404, "EXPERIENCE_NOT_FOUND");

        em.flush();
        em.clear();
        assertThat(experiences.findById(others.getId()).orElseThrow().isShared()).isFalse();
    }

    @Test
    void shared_값이_없거나_불리언이_아니면_400() throws Exception {
        Experience e = experience(activity(userId, "활동"), "본문", SourceStatus.SOURCE_CONFIRMED);

        for (String body : List.of("{}", "{\"shared\":null}", "{\"shared\":\"true\"}")) {
            expectError(web.perform(put("/api/experiences/{id}/shared", e.getId())
                    .contentType(MediaType.APPLICATION_JSON).content(body)), 400, "INVALID_REQUEST");
        }
    }

    @Test
    void 외부_AI_에는_shared_가_보이지_않는다() throws Exception {
        Activity activity = activity(userId, "활동");
        Experience e = experience(activity, "본문", SourceStatus.SOURCE_CONFIRMED);
        share(e.getId(), true).andExpect(status().isOk());
        em.flush();
        em.clear();

        Object detail = getActivity.call(new McpConnectionService.Authenticated(1L, userId, "READ", "claude-ai"),
                json.createObjectNode().put("activityId", activity.getId()));

        JsonNode experience = json.valueToTree(detail).get("experiences").get(0);
        assertThat(experience.get("id").asLong()).isEqualTo(e.getId());
        assertThat(experience.has("shared")).isFalse();
    }

    // --- 공개 설정 ---

    @Test
    void 만진_적_없으면_빈_설정이다() throws Exception {
        web.perform(get("/api/public-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(nullValue()))
                .andExpect(jsonPath("$.linkEnabled").value(false))
                .andExpect(jsonPath("$.token").value(nullValue()))
                .andExpect(jsonPath("$.sharedCount").value(0))
                .andExpect(jsonPath("$.visibleCount").value(0));
    }

    @Test
    void 고른_수와_실제로_실리는_수를_따로_센다() throws Exception {
        Activity activity = activity(userId, "활동");
        Experience kept = experience(activity, "실림", SourceStatus.SOURCE_CONFIRMED);
        Experience reviewing = experience(activity, "확인 필요", SourceStatus.SOURCE_CONFIRMED);
        experience(activity, "안 고름", SourceStatus.SOURCE_CONFIRMED);
        share(kept.getId(), true);
        share(reviewing.getId(), true);
        toNeedsReview(reviewing);

        web.perform(get("/api/public-profile"))
                .andExpect(jsonPath("$.sharedCount").value(2))
                .andExpect(jsonPath("$.visibleCount").value(1));
    }

    @Test
    void 제목은_걷어서_저장하고_비우면_지워지고_주소는_그대로다() throws Exception {
        String token = enableWithOneExperience();

        rename("{\"title\":\"  내 경험  \\n\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("내 경험"))
                .andExpect(jsonPath("$.token").value(token));
        anonymous.perform(get("/api/public/profiles/{token}", token)).andExpect(jsonPath("$.title").value("내 경험"));

        rename("{\"title\":\"   \"}").andExpect(jsonPath("$.title").value(nullValue()));
        rename("{\"title\":\"다시\"}").andExpect(jsonPath("$.title").value("다시"));
        rename("{\"title\":null}").andExpect(jsonPath("$.title").value(nullValue()));
        rename("{\"title\":\"" + "가".repeat(100) + "\"}").andExpect(status().isOk());
    }

    @Test
    void 링크가_꺼져_있어도_제목을_고칠_수_있다() throws Exception {
        rename("{\"title\":\"제목\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("제목"))
                .andExpect(jsonPath("$.linkEnabled").value(false));
    }

    @Test
    void 제목이_규칙을_어기면_400() throws Exception {
        for (String body : List.of("{}", "{\"title\":3}", "{\"title\":\"한\\n둘\"}", "{\"title\":\"한\\r둘\"}",
                "{\"title\":\"" + "가".repeat(101) + "\"}")) {
            expectError(rename(body), 400, "INVALID_REQUEST");
        }
    }

    // --- 링크 ---

    @Test
    void 고른_경험이_없거나_전부_확인_필요면_켤_수_없다() throws Exception {
        expectError(web.perform(post("/api/public-profile/link")), 409, "NO_SHARED_EXPERIENCES");

        Experience e = oneSharedExperience();
        toNeedsReview(e);

        expectError(web.perform(post("/api/public-profile/link")), 409, "NO_SHARED_EXPERIENCES");
        web.perform(get("/api/public-profile")).andExpect(jsonPath("$.linkEnabled").value(false));
    }

    @Test
    void 새로_만들면_새_토큰이고_옛_주소는_바로_404_다() throws Exception {
        oneSharedExperience();
        String first = token(web.perform(post("/api/public-profile/link"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkEnabled").value(true))
                .andExpect(jsonPath("$.token", matchesPattern("[A-Za-z0-9_-]{22}"))));

        String second = token(web.perform(post("/api/public-profile/link")).andExpect(status().isCreated()));

        assertThat(second).isNotEqualTo(first);
        anonymous.perform(get("/api/public/profiles/{token}", second)).andExpect(status().isOk());
        expectError(anonymous.perform(get("/api/public/profiles/{token}", first)), 404, "PUBLIC_PROFILE_NOT_FOUND");
    }

    @Test
    void 끄면_바로_404_고_제목과_고른_경험은_남고_다시_켜면_새_주소다() throws Exception {
        Experience e = oneSharedExperience();
        rename("{\"title\":\"제목\"}");
        String old = enable();

        web.perform(delete("/api/public-profile/link")).andExpect(status().isNoContent());
        web.perform(delete("/api/public-profile/link")).andExpect(status().isNoContent());

        expectError(anonymous.perform(get("/api/public/profiles/{token}", old)), 404, "PUBLIC_PROFILE_NOT_FOUND");
        web.perform(get("/api/public-profile"))
                .andExpect(jsonPath("$.title").value("제목"))
                .andExpect(jsonPath("$.linkEnabled").value(false))
                .andExpect(jsonPath("$.token").value(nullValue()))
                .andExpect(jsonPath("$.sharedCount").value(1));

        assertThat(enable()).isNotEqualTo(old);
    }

    @Test
    void 설정을_한_번도_만진_적_없어도_끄기는_204_다() throws Exception {
        web.perform(delete("/api/public-profile/link")).andExpect(status().isNoContent());
    }

    // --- 공개 읽기 ---

    @Test
    void 허용_필드만_나가고_발췌문_보완_계기_이메일은_없다() throws Exception {
        Activity activity = activity(userId, "바꿔요");
        activity.changePeriod(new Period("2025-03", "2025-06", false));
        activity.changeNote("비공개 계기");
        Experience e = experience(activity, "공개 본문", SourceStatus.SOURCE_CONFIRMED);
        Source source = sources.saveAndFlush(new Source(userId, SourceKind.TEXT, "볼트/활동/바꿔요-회고.md", "비공개 원문 전체"));
        citations.saveAndFlush(new Citation(e, source, "비공개 발췌문", true, 1, 2));
        supplements.saveAndFlush(new Supplement(e, null, null, "비공개 질문", "비공개 보완"));
        share(e.getId(), true);
        String token = enable();
        rename("{\"title\":\"공개 제목\"}");
        em.flush();
        em.clear();

        String body = anonymous.perform(get("/api/public/profiles/{token}", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn().getResponse().getContentAsString();

        JsonNode root = json.readTree(body);
        assertThat(fields(root)).containsExactlyInAnyOrder("title", "activities");
        assertThat(root.get("title").asString()).isEqualTo("공개 제목");

        JsonNode publicActivity = root.get("activities").get(0);
        assertThat(fields(publicActivity))
                .containsExactlyInAnyOrder("name", "period", "category", "categoryBy", "experiences");
        assertThat(publicActivity.get("name").asString()).isEqualTo("바꿔요");
        assertThat(publicActivity.get("period").get("start").asString()).isEqualTo("2025-03");

        JsonNode publicExperience = publicActivity.get("experiences").get(0);
        assertThat(fields(publicExperience))
                .containsExactlyInAnyOrder("content", "sourceStatus", "competencies", "competenciesBy", "sourceNames");
        assertThat(publicExperience.get("sourceNames").get(0).asString()).isEqualTo("바꿔요-회고.md");

        assertThat(body).doesNotContain("비공개", "볼트", "share-owner", "actor", "usedIn");
    }

    @Test
    void 지금_고른_경험만_지금_값으로_타임라인_순서대로_싣는다() throws Exception {
        Activity later = activity(userId, "나중 활동");
        later.changePeriod(new Period("2025-01", null, false));
        Activity earlier = activity(userId, "먼저 활동");
        earlier.changePeriod(new Period("2024-01", null, false));
        Activity notShared = activity(userId, "안 고른 활동");
        Experience first = experience(later, "첫째", SourceStatus.SOURCE_CONFIRMED);
        Experience second = experience(later, "둘째", SourceStatus.USER_ENTERED);
        experience(later, "안 고른 경험", SourceStatus.SOURCE_CONFIRMED);
        Experience earliest = experience(earlier, "맨 앞", SourceStatus.SOURCE_CONFIRMED);
        experience(notShared, "다른 경험", SourceStatus.SOURCE_CONFIRMED);
        share(second.getId(), true);
        share(first.getId(), true);
        share(earliest.getId(), true);
        String token = enable();

        web.perform(patch("/api/experiences/{id}", first.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"고친 첫째\"}")).andExpect(status().isOk());

        anonymous.perform(get("/api/public/profiles/{token}", token))
                .andExpect(jsonPath("$.activities.length()").value(2))
                .andExpect(jsonPath("$.activities[0].name").value("먼저 활동"))
                .andExpect(jsonPath("$.activities[1].name").value("나중 활동"))
                .andExpect(jsonPath("$.activities[1].experiences.length()").value(2))
                .andExpect(jsonPath("$.activities[1].experiences[0].content").value("고친 첫째"))
                .andExpect(jsonPath("$.activities[1].experiences[0].sourceStatus").value("USER_ENTERED"))
                .andExpect(jsonPath("$.activities[1].experiences[1].content").value("둘째"));

        web.perform(delete("/api/experiences/{id}", first.getId())).andExpect(status().isNoContent());
        anonymous.perform(get("/api/public/profiles/{token}", token))
                .andExpect(jsonPath("$.activities[1].experiences.length()").value(1));
    }

    @Test
    void 나중에_확인_필요가_되면_빠지고_다시_확인되면_돌아온다() throws Exception {
        Experience e = oneSharedExperience();
        String token = enable();
        toNeedsReview(e);

        anonymous.perform(get("/api/public/profiles/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activities.length()").value(0));

        web.perform(patch("/api/experiences/{id}", e.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"확인한 본문\"}")).andExpect(status().isOk());
        anonymous.perform(get("/api/public/profiles/{token}", token))
                .andExpect(jsonPath("$.activities[0].experiences[0].content").value("확인한 본문"));
    }

    @Test
    void 근거를_못_찾은_원본은_빼고_같은_이름은_한_번만_처음_순서로_싣는다() throws Exception {
        Experience e = oneSharedExperience();
        citations.save(new Citation(e, source("가/회고.md"), "발췌", true, null, null));
        citations.save(new Citation(e, source("못찾음.md"), "발췌", false, null, null));
        citations.save(new Citation(e, source("대조안함.md"), "발췌", null, null, null));
        citations.save(new Citation(e, source("메모.txt"), "발췌", true, null, null));
        citations.saveAndFlush(new Citation(e, source("나/회고.md"), "발췌", true, null, null));
        String token = enable();

        anonymous.perform(get("/api/public/profiles/{token}", token))
                .andExpect(jsonPath("$.activities[0].experiences[0].sourceNames.length()").value(2))
                .andExpect(jsonPath("$.activities[0].experiences[0].sourceNames[0]").value("회고.md"))
                .andExpect(jsonPath("$.activities[0].experiences[0].sourceNames[1]").value("메모.txt"));
    }

    @Test
    void 고른_경험이_다_빠져도_링크는_살아_있다() throws Exception {
        Experience e = oneSharedExperience();
        String token = enable();

        share(e.getId(), false);

        anonymous.perform(get("/api/public/profiles/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activities.length()").value(0));
    }

    @Test
    void 남의_경험은_내_프로필에_실리지_않는다() throws Exception {
        Experience others = experience(activity(otherUserId, "남의 활동"), "남의 본문", SourceStatus.SOURCE_CONFIRMED);
        TestLogin.mvc(context, otherUserId).perform(put("/api/experiences/{id}/shared", others.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"shared\":true}")).andExpect(status().isOk());
        Experience mine = oneSharedExperience();
        String token = enable();

        anonymous.perform(get("/api/public/profiles/{token}", token))
                .andExpect(jsonPath("$.activities.length()").value(1))
                .andExpect(jsonPath("$.activities[0].experiences[0].content").value("본문"));
    }

    @Test
    void 없는_주소_꺼진_주소_형식이_틀린_주소는_응답이_똑같다() throws Exception {
        String old = enableWithOneExperience();
        web.perform(delete("/api/public-profile/link")).andExpect(status().isNoContent());

        String unknown = notFoundBody(UNKNOWN_TOKEN);
        assertThat(notFoundBody(old)).isEqualTo(unknown);
        assertThat(notFoundBody("example-public-token")).isEqualTo(unknown);
        assertThat(notFoundBody("x")).isEqualTo(unknown);
    }

    @Test
    void 주인이_불러도_응답이_같다() throws Exception {
        String token = enableWithOneExperience();

        String asOwner = web.perform(get("/api/public/profiles/{token}", token))
                .andReturn().getResponse().getContentAsString();
        String asAnonymous = anonymous.perform(get("/api/public/profiles/{token}", token))
                .andReturn().getResponse().getContentAsString();

        assertThat(asOwner).isEqualTo(asAnonymous);
    }

    // --- 보안 설정 ---

    @Test
    void 로그인_없이_열린_길은_공개_읽기_하나뿐이다() throws Exception {
        Experience e = experience(activity(userId, "활동"), "본문", SourceStatus.SOURCE_CONFIRMED);

        expectError(anonymous.perform(get("/api/public-profile")), 401, "UNAUTHENTICATED");
        expectError(anonymous.perform(patch("/api/public-profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\"}")), 401, "UNAUTHENTICATED");
        expectError(anonymous.perform(post("/api/public-profile/link")), 401, "UNAUTHENTICATED");
        expectError(anonymous.perform(delete("/api/public-profile/link")), 401, "UNAUTHENTICATED");
        expectError(anonymous.perform(put("/api/experiences/{id}/shared", e.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"shared\":true}")), 401, "UNAUTHENTICATED");

        // 공개 경로 언저리가 함께 열리지 않는다
        expectError(anonymous.perform(get("/api/public")), 401, "UNAUTHENTICATED");
        expectError(anonymous.perform(get("/api/public/profiles")), 401, "UNAUTHENTICATED");
        expectError(anonymous.perform(get("/api/public/other")), 401, "UNAUTHENTICATED");
        expectError(anonymous.perform(get("/api/public/profiles/" + UNKNOWN_TOKEN + "/more")), 401, "UNAUTHENTICATED");

        // 같은 경로의 쓰기는 처리기가 없다
        anonymous.perform(post("/api/public/profiles/" + UNKNOWN_TOKEN)).andExpect(status().isMethodNotAllowed());
        anonymous.perform(delete("/api/public/profiles/" + UNKNOWN_TOKEN)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void FE_가_PUT_을_부를_수_있다() throws Exception {
        anonymous.perform(options("/api/experiences/1/shared")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Access-Control-Allow-Methods"))
                        .contains("PUT"));
    }

    // --- 도우미 ---

    private ResultActions share(long experienceId, boolean shared) throws Exception {
        return web.perform(put("/api/experiences/{id}/shared", experienceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"shared\":" + shared + "}"));
    }

    private ResultActions rename(String body) throws Exception {
        return web.perform(patch("/api/public-profile").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String enable() throws Exception {
        return token(web.perform(post("/api/public-profile/link")).andExpect(status().isCreated()));
    }

    private String enableWithOneExperience() throws Exception {
        oneSharedExperience();
        return enable();
    }

    private String token(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("token").asString();
    }

    private String notFoundBody(String token) throws Exception {
        return anonymous.perform(get("/api/public/profiles/{token}", token))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("PUBLIC_PROFILE_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
    }

    private Experience oneSharedExperience() throws Exception {
        Experience e = experience(activity(userId, "활동"), "본문", SourceStatus.SOURCE_CONFIRMED);
        share(e.getId(), true).andExpect(status().isOk());
        return e;
    }

    private Activity activity(long ownerId, String name) {
        return activities.saveAndFlush(new Activity(ownerId, name, new Period(null, null, false)));
    }

    private Experience experience(Activity activity, String content, SourceStatus status) {
        return experiences.saveAndFlush(new Experience(activity, content, List.of(Competency.IMPLEMENTATION),
                CompetenciesBy.AI, Actor.ME, status, experiences.findLastPosition(activity.getId()) + 1));
    }

    private Source source(String name) {
        return sources.save(new Source(userId, SourceKind.TEXT, name, "원문"));
    }

    private void toNeedsReview(Experience e) {
        e.rewriteByExternalAi(e.getContent(), 1L, "claude-ai");
        experiences.saveAndFlush(e);
    }

    private static List<String> fields(JsonNode node) {
        return node.propertyNames().stream().toList();
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }
}
