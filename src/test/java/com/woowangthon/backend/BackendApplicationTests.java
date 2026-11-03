package com.woowangthon.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.woowangthon.backend.activity.ActivityRepository;
import com.woowangthon.backend.account.AppUserRepository;
import com.woowangthon.backend.seed.SampleSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BackendApplicationTests {

    @Autowired
    ApplicationContext context;

    @Autowired
    ActivityRepository activities;

    @Autowired
    AppUserRepository users;

    @Test
    void 시드는_기본으로_꺼져_있다() {
        assertThat(context.getBeanNamesForType(SampleSeeder.class)).isEmpty();
        assertThat(activities.existsByUserId(TestLogin.USER_ID)).isFalse();
    }

    @Test
    void 테스트_계정은_비밀번호를_넣어야_생긴다() {
        assertThat(users.findByLoginId("test")).isEmpty();
    }
}
