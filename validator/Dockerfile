# cryptobot-validator — a process that is not the agent. Attestation key mounted read-only; never bake it in.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -ntp -DskipTests package

# alpine, like the service image: the compose healthchecks use wget (KAN-570).
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S validator && adduser -S -u 10003 validator -G validator
WORKDIR /app
COPY --from=build /src/target/cryptobot-validator-*.jar app.jar
USER validator
EXPOSE 8097
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8097/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/app.jar"]
