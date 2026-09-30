# cryptobot-signer — the only container with a key. Mount the keypair read-only; never bake it in.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -ntp -DskipTests package

# alpine, like the service image: the compose healthchecks use wget.
FROM eclipse-temurin:21-jre-alpine
# Identidad de build (misma forma que el Dockerfile del servicio): el contexto copia
# sólo src/ (sin .git), así que CI pasa el commit real como build-args y queda en el entorno
# para /actuator/info y para los logs (commitSha). Son hechos de git, nunca secretos.
ARG GIT_COMMIT=""
ARG GIT_BRANCH=""
ARG GIT_COMMIT_TIME=""
ENV GIT_COMMIT=${GIT_COMMIT} \
    GIT_BRANCH=${GIT_BRANCH} \
    GIT_COMMIT_TIME=${GIT_COMMIT_TIME}
RUN addgroup -S signer && adduser -S -u 10002 signer -G signer
WORKDIR /app
COPY --from=build /src/target/cryptobot-signer-*.jar app.jar
USER signer
EXPOSE 8096
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8096/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/app.jar"]
