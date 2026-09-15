# cryptobot-signer — the only container with a key. Mount the keypair read-only; never bake it in.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -ntp -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd -r -u 10002 signer
WORKDIR /app
COPY --from=build /src/target/cryptobot-signer-*.jar app.jar
USER signer
EXPOSE 8096
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "/app/app.jar"]
