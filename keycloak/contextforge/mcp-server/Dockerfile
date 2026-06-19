FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src/ ./src/
RUN mvn package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/quarkus-app/ /app/
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
