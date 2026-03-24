FROM eclipse-temurin:25-jdk-ubi10-minimal AS jar-builder
COPY scripts/ /tmp/scripts/
RUN cd /tmp/scripts && jar cf /tmp/custom-policies.jar META-INF/keycloak-scripts.json own-user-or-same-team-manager.js

FROM quay.io/keycloak/keycloak:nightly
COPY --from=jar-builder /tmp/custom-policies.jar /opt/keycloak/providers/custom-policies.jar
