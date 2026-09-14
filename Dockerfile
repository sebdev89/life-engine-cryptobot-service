# cryptobot-service — hackathon image. Build context: this repo.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -ntp -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 cryptobot
WORKDIR /app
COPY --from=build /src/target/cryptobot-service-*.jar app.jar
USER cryptobot
EXPOSE 8091
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
