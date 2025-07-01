FROM quay.io/keycloak/keycloak:26.2.5
ENV KC_DB=oracle
COPY --chown=keycloak:keycloak --chmod=644 drivers/*.jar /opt/keycloak/providers/
WORKDIR /opt/keycloak
RUN keytool -genkeypair -storepass password -storetype PKCS12 -keyalg RSA -keysize 2048 -dname "CN=server" -alias server -ext "SAN:c=DNS:localhost,IP:127.0.0.1" -keystore conf/server.keystore
RUN /opt/keycloak/bin/kc.sh build
ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start"]

