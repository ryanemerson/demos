FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY frontend/pom.xml frontend/
COPY microservice1/pom.xml microservice1/
COPY microservice2/pom.xml microservice2/
COPY microservice2/src microservice2/src
RUN mvn -B package -pl microservice2 -am -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/microservice2/target/quarkus-app/ /app/
EXPOSE 8443
CMD ["java", "-jar", "quarkus-run.jar"]
