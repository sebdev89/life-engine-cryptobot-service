# cryptobot-validator — a process that is not the agent. Attestation key mounted read-only; never bake it in.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -ntp -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd -r -u 10003 validator
WORKDIR /app
COPY --from=build /src/target/cryptobot-validator-*.jar app.jar
USER validator
EXPOSE 8097
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/app.jar"]
