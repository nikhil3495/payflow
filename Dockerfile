# Build stage: compile and package without running the (Docker-dependent) test suite;
# the full suite runs in CI via `mvn verify`.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is cached until the pom changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true -Dspotbugs.skip=true \
    && cp target/payflow-*.jar /workspace/app.jar

# Runtime stage: JRE only, non-root, no build tooling.
FROM eclipse-temurin:21-jre-noble
RUN groupadd --system --gid 10001 payflow \
    && useradd --system --uid 10001 --gid payflow --no-create-home --shell /usr/sbin/nologin payflow
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
