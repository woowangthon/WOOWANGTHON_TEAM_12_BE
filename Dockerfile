# BE 서버 이미지 — ETC ADR-004: OCI 서버 하나에 Docker Compose 로 BE · AI · DB (docker-compose.yml).
# 베이스 이미지가 amd64 · arm64 둘 다라서 OCI ARM 서버에서 그대로 빌드하면 arm64 가 된다.
# 다른 기계에서 ARM 용으로 만들 땐 `docker buildx build --platform linux/arm64 .`

FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /app

# 의존성 먼저 — 코드만 바뀌면 이 층은 캐시를 쓴다
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

# 테스트는 여기서 돌리지 않는다 — Testcontainers 라 Docker 가 필요하다. 검증은 `./gradlew build`
COPY src ./src
RUN ./gradlew --no-daemon bootJar && mv build/libs/*.jar app.jar

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build /app/app.jar app.jar

# 환경 변수(DB_PASSWORD 등)는 이미지에 넣지 않는다 — Compose 가 실행할 때 넣는다
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
