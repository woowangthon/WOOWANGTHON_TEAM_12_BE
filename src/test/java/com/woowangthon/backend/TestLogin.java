package com.woowangthon.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.woowangthon.backend.common.CurrentUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 테스트 요청을 로그인한 사용자로 보낸다 — 세션에 사용자 ID 를 넣어 둔다(로그인 API 를 거치지 않는다). */
final class TestLogin {

    /** V1 마이그레이션의 사용자 1. 로그인 수단은 없지만 테스트에선 세션에 바로 넣어 쓴다. */
    static final long USER_ID = 1L;

    private TestLogin() {
    }

    static MockMvc mvc(WebApplicationContext context) {
        return mvc(context, USER_ID);
    }

    static MockMvc mvc(WebApplicationContext context, long userId) {
        return MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/").sessionAttr(CurrentUser.SESSION_KEY, userId))
                .build();
    }
}
