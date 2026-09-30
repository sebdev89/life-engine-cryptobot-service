# syntax=docker/dockerfile:1
# cryptobot-service — multi-stage build (Maven 3.9 + JRE 21 alpine). Build context: this repo.
#
# Misma forma que life-engine-runtime: es la imagen que publica CI (docker-publish) y
# la que consume uat-compose por digest. El módulo `signer/` tiene su propio pom y su propia
# imagen; no entra acá.

# ── Stage 1: compile ─────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -ntp -DskipTests package

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
# alpine y no ubuntu: los healthcheck de los compose de UAT/prod usan `wget` (igual que los
# otros seis servicios) y `eclipse-temurin:21-jre` (ubuntu) no trae wget ni curl.
FROM eclipse-temurin:21-jre-alpine AS runtime

# Identidad de build para /actuator/info. El contexto copia sólo src/
# (sin .git), así que git-commit-id no genera git.properties: CI pasa los datos reales como
# build-args y BuildIdentityResolver los prefiere (identity.git.* en application.yml).
# Son hechos de git, nunca secretos.
ARG GIT_COMMIT=""
ARG GIT_BRANCH=""
ARG GIT_COMMIT_TIME=""
ENV GIT_COMMIT=${GIT_COMMIT} \
    GIT_BRANCH=${GIT_BRANCH} \
    GIT_COMMIT_TIME=${GIT_COMMIT_TIME}

RUN addgroup -S cryptobot && adduser -S -u 10001 cryptobot -G cryptobot
USER cryptobot
WORKDIR /app

COPY --from=build /src/target/cryptobot-service-*.jar app.jar

EXPOSE 8091

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD wget -qO- http://localhost:8091/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
