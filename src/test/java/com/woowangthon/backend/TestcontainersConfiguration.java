package com.woowangthon.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 테스트마다 compose-local.yaml 과 같은 이미지로 진짜 PostgreSQL 을 띄운다. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String POSTGRES_IMAGE = "pgvector/pgvector:pg18";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    }
}
