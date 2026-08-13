# ---- Build stage -----------------------------------------------------------
# Jammy rather than Alpine: Temurin publishes no arm64 Alpine image for 17, so
# an Alpine base fails to build on Apple Silicon.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /build

# The wrapper and build definition change far less often than the source, so
# copying them first lets Docker reuse the cached dependency download on most
# builds.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY src ./src
# Tests are not run here: they need Docker (Testcontainers), which is not
# available inside a build container. CI runs them before this ever builds.
RUN ./gradlew bootJar --no-daemon -x test

# Split the jar into layers so application code, which changes on every build,
# does not share a layer with dependencies, which rarely change. Only the small
# top layer needs re-pushing and re-pulling on a typical deploy.
RUN java -Djarmode=tools -jar build/libs/statistra.jar extract --layers --launcher --destination extracted

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# Run unprivileged: a compromised process should not also be root.
RUN groupadd --system statistra && useradd --system --gid statistra statistra

# Ordered least- to most-frequently changed, matching Docker's layer caching.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER statistra
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the container memory limit is set
# by the platform, and a hardcoded heap either wastes the allocation or gets the
# process OOM-killed when the limit is lowered.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
