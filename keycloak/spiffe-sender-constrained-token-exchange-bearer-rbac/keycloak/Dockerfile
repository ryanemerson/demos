FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY providers/pom.xml .
COPY providers/src/ src/
RUN mvn package -q -DskipTests

FROM quay.io/keycloak/keycloak:nightly
COPY --from=build /app/target/keycloak-mtls-cnf-mapper-1.0.0.jar /opt/keycloak/providers/
