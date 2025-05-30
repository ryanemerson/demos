# Start Keycloak

```
docker run --rm --name keycloak \
    -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
    -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
    -p 8081:8080 \
    quay.io/keycloak/keycloak:latest \
    start-dev
```

# Configure Realm and client

1. Login as master realm
2. Create new realm "demo"
3. Create a user Ryan
4. Create a client "bret-fischer-service"

# Start the application

```
./mvnw quarkus:dev
```
