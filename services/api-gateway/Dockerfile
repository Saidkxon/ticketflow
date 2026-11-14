# syntax=docker/dockerfile:1
# Build from the repository root (the parent POM lives there):
#   docker build -f services/api-gateway/Dockerfile -t ticketflow-api-gateway .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Poms first, so the dependency download is cached until a pom changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY services/catalog-service/pom.xml services/catalog-service/pom.xml
COPY services/api-gateway/pom.xml services/api-gateway/pom.xml
COPY services/api-gateway/src services/api-gateway/src
# Tests run in CI, not in the image build.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -pl services/api-gateway -am package -DskipTests

FROM eclipse-temurin:25-jre
# curl is used only by the compose healthcheck (the JRE image does not ship it)
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/services/api-gateway/target/api-gateway-*.jar app.jar
USER app
# JSON logs on standard output (ADR-0008); the heap follows the container's memory limit.
ENV SPRING_PROFILES_ACTIVE=json \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 18080 19080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
