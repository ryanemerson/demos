# SPIFFE mTLS + Sender-Constrained Token Exchange Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add SPIFFE SPIRE infrastructure, mTLS between all services, and RFC 8705 sender-constrained tokens to the existing Keycloak token-exchange Docker Compose demo.

**Architecture:** Docker Compose with SPIRE server/agent for X.509-SVID provisioning via spiffe-helper containers. All service-to-service communication uses mTLS with SPIFFE certificates. Keycloak binds access tokens to client TLS certificates (RFC 8705). Quarkus TLS Registry manages certificates with auto-reload.

**Tech Stack:** SPIRE Server/Agent, spiffe-helper, Keycloak nightly, Quarkus 3.23.3 (TLS Registry, OIDC), Docker Compose

**Spec:** `docs/superpowers/specs/2026-03-16-spiffe-mtls-sender-constrained-design.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `spire/server.conf` | SPIRE server HCL config (trust domain, data store, node attestor) |
| `spire/agent.conf` | SPIRE agent HCL config (docker workload attestor, server address) |
| `spire/init.sh` | Registers SPIRE entries + generates join token |
| `spire/agent-entrypoint.sh` | Reads join token from shared volume, starts agent |
| `keycloak/spiffe-helper.conf` | spiffe-helper config for Keycloak cert provisioning |
| `frontend/spiffe-helper.conf` | spiffe-helper config for Frontend cert provisioning |
| `microservice1/spiffe-helper.conf` | spiffe-helper config for Microservice1 cert provisioning |
| `microservice2/spiffe-helper.conf` | spiffe-helper config for Microservice2 cert provisioning |

### Modified Files
| File | Changes |
|------|---------|
| `docker-compose.yml` | Add SPIRE infra (9 new services), volumes, labels, HTTPS URLs |
| `keycloak/demo-realm.json` | Frontend→confidential, cert-bound tokens, sslRequired, HTTPS URIs |
| `frontend/pom.xml` | Add `quarkus-tls-registry` dependency |
| `frontend/src/main/resources/application.properties` | TLS config, HTTPS URLs, remove http port |
| `frontend/Dockerfile` | Update EXPOSE port |
| `microservice1/pom.xml` | Add `quarkus-tls-registry` dependency |
| `microservice1/src/main/resources/application.properties` | TLS config, HTTPS URLs, mTLS required |
| `microservice1/Dockerfile` | Update EXPOSE port |
| `microservice2/pom.xml` | Add `quarkus-tls-registry` dependency |
| `microservice2/src/main/resources/application.properties` | TLS config, HTTPS URLs, mTLS required |
| `microservice2/Dockerfile` | Update EXPOSE port |

---

## Chunk 1: SPIRE Infrastructure

### Task 1: Create SPIRE Server Configuration

**Files:**
- Create: `spire/server.conf`

- [ ] **Step 1: Create SPIRE server config**

```hcl
server {
    bind_address = "0.0.0.0"
    bind_port = "8081"
    trust_domain = "demo.example.com"
    data_dir = "/opt/spire/data"
    log_level = "DEBUG"
}

plugins {
    DataStore "sql" {
        plugin_data {
            database_type = "sqlite3"
            connection_string = "/opt/spire/data/datastore.sqlite3"
        }
    }

    NodeAttestor "join_token" {
        plugin_data {}
    }

    KeyManager "memory" {
        plugin_data {}
    }
}

health_checks {
    listener_enabled = true
    bind_address = "0.0.0.0"
    bind_port = "8080"
    live_path = "/live"
    ready_path = "/ready"
}
```

- [ ] **Step 2: Commit**

```bash
git add spire/server.conf
git commit -m "Add SPIRE server configuration"
```

---

### Task 2: Create SPIRE Agent Configuration

**Files:**
- Create: `spire/agent.conf`

- [ ] **Step 1: Create SPIRE agent config**

The agent uses `join_token` node attestor and `docker` workload attestor. The Docker workload attestor needs access to the Docker socket to correlate PIDs with containers.

```hcl
agent {
    data_dir = "/opt/spire/data"
    log_level = "DEBUG"
    server_address = "spire-server"
    server_port = "8081"
    socket_path = "/run/spire/sockets/agent.sock"
    trust_domain = "demo.example.com"
}

plugins {
    NodeAttestor "join_token" {
        plugin_data {}
    }

    KeyManager "memory" {
        plugin_data {}
    }

    WorkloadAttestor "docker" {
        plugin_data {
            docker_socket_path = "unix:///var/run/docker.sock"
        }
    }
}

health_checks {
    listener_enabled = true
    bind_address = "0.0.0.0"
    bind_port = "9982"
    live_path = "/live"
    ready_path = "/ready"
}
```

- [ ] **Step 2: Commit**

```bash
git add spire/agent.conf
git commit -m "Add SPIRE agent configuration with Docker workload attestor"
```

---

### Task 3: Create SPIRE Init Script

**Files:**
- Create: `spire/init.sh`

- [ ] **Step 1: Create init script**

This script runs in the `spire-init` container. It waits for the SPIRE server to be ready, registers workload entries using Docker label selectors, and generates a join token for the agent.

```bash
#!/bin/bash
set -e

echo "Waiting for SPIRE server to be ready..."
while ! /opt/spire/bin/spire-server healthcheck -socketPath /run/spire/sockets/server.sock 2>/dev/null; do
    sleep 1
done
echo "SPIRE server is ready"

echo "Creating workload entries..."

# Keycloak
/opt/spire/bin/spire-server entry create \
    -socketPath /run/spire/sockets/server.sock \
    -spiffeID spiffe://demo.example.com/keycloak \
    -parentID spiffe://demo.example.com/spire-agent \
    -selector docker:label:spiffe-workload:keycloak \
    -dns keycloak

# Frontend
/opt/spire/bin/spire-server entry create \
    -socketPath /run/spire/sockets/server.sock \
    -spiffeID spiffe://demo.example.com/frontend \
    -parentID spiffe://demo.example.com/spire-agent \
    -selector docker:label:spiffe-workload:frontend \
    -dns frontend

# Microservice1
/opt/spire/bin/spire-server entry create \
    -socketPath /run/spire/sockets/server.sock \
    -spiffeID spiffe://demo.example.com/microservice1 \
    -parentID spiffe://demo.example.com/spire-agent \
    -selector docker:label:spiffe-workload:microservice1 \
    -dns microservice1

# Microservice2
/opt/spire/bin/spire-server entry create \
    -socketPath /run/spire/sockets/server.sock \
    -spiffeID spiffe://demo.example.com/microservice2 \
    -parentID spiffe://demo.example.com/spire-agent \
    -selector docker:label:spiffe-workload:microservice2 \
    -dns microservice2

echo "All entries created"

echo "Generating join token for SPIRE agent..."
TOKEN=$(/opt/spire/bin/spire-server token generate \
    -socketPath /run/spire/sockets/server.sock \
    -spiffeID spiffe://demo.example.com/spire-agent \
    -output json | sed -n 's/.*"value":"\([^"]*\)".*/\1/p')

echo "$TOKEN" > /run/spire/join-token/token
echo "Join token written to /run/spire/join-token/token"
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x spire/init.sh
```

- [ ] **Step 3: Commit**

```bash
git add spire/init.sh
git commit -m "Add SPIRE init script for entry registration and join token"
```

---

### Task 4: Create SPIRE Agent Entrypoint Script

**Files:**
- Create: `spire/agent-entrypoint.sh`

- [ ] **Step 1: Create agent entrypoint**

This reads the join token written by `spire-init` and starts the agent.

```bash
#!/bin/bash
set -e

echo "Waiting for join token..."
while [ ! -f /run/spire/join-token/token ]; do
    sleep 1
done

JOIN_TOKEN=$(cat /run/spire/join-token/token)
echo "Join token found, starting SPIRE agent..."

exec /opt/spire/bin/spire-agent run \
    -config /opt/spire/conf/agent/agent.conf \
    -joinToken "$JOIN_TOKEN"
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x spire/agent-entrypoint.sh
```

- [ ] **Step 3: Commit**

```bash
git add spire/agent-entrypoint.sh
git commit -m "Add SPIRE agent entrypoint script"
```

---

### Task 5: Create spiffe-helper Configuration Files

**Files:**
- Create: `keycloak/spiffe-helper.conf`
- Create: `frontend/spiffe-helper.conf`
- Create: `microservice1/spiffe-helper.conf`
- Create: `microservice2/spiffe-helper.conf`

- [ ] **Step 1: Create all four spiffe-helper configs**

All four configs are identical — they write to `/opt/spiffe-certs/` inside each helper container, which is mapped to a per-workload Docker volume.

`keycloak/spiffe-helper.conf`:
```hcl
agent_address = "/run/spire/sockets/agent.sock"
cert_dir = "/opt/spiffe-certs"
daemon_mode = true

svid_file_name = "svid.pem"
svid_key_file_name = "svid_key.pem"
svid_bundle_file_name = "bundle.pem"
```

`frontend/spiffe-helper.conf` — same content.

`microservice1/spiffe-helper.conf` — same content.

`microservice2/spiffe-helper.conf` — same content.

- [ ] **Step 2: Commit**

```bash
git add keycloak/spiffe-helper.conf frontend/spiffe-helper.conf microservice1/spiffe-helper.conf microservice2/spiffe-helper.conf
git commit -m "Add spiffe-helper configs for all workloads"
```

---

## Chunk 2: Docker Compose

### Task 6: Rewrite docker-compose.yml

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Replace docker-compose.yml with full SPIRE + mTLS configuration**

The new docker-compose.yml adds:
- `spire-server` — SPIRE server container with health check
- `spire-init` — Registers entries and generates join token, then exits
- `spire-agent` — Runs with `pid: host` and Docker socket for workload attestation
- 4x `spiffe-helper-*` — One per workload, writes certs to shared volumes
- All workloads updated: HTTPS URLs, cert volume mounts, Docker labels
- Dependency chain ensures correct startup order

```yaml
services:
  # ============================================================
  # SPIRE Infrastructure
  # ============================================================
  spire-server:
    image: ghcr.io/spiffe/spire-server:1.12.0
    command: ["-config", "/opt/spire/conf/server/server.conf"]
    volumes:
      - ./spire/server.conf:/opt/spire/conf/server/server.conf:ro
      - spire-server-socket:/run/spire/sockets
    healthcheck:
      test: ["/opt/spire/bin/spire-server", "healthcheck", "-socketPath", "/run/spire/sockets/server.sock"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 10s

  spire-init:
    image: ghcr.io/spiffe/spire-server:1.12.0
    entrypoint: ["/bin/bash", "/opt/spire/init.sh"]
    volumes:
      - ./spire/init.sh:/opt/spire/init.sh:ro
      - spire-server-socket:/run/spire/sockets
      - spire-join-token:/run/spire/join-token
    depends_on:
      spire-server:
        condition: service_healthy

  spire-agent:
    image: ghcr.io/spiffe/spire-agent:1.12.0
    entrypoint: ["/bin/bash", "/opt/spire/agent-entrypoint.sh"]
    pid: host
    volumes:
      - ./spire/agent.conf:/opt/spire/conf/agent/agent.conf:ro
      - ./spire/agent-entrypoint.sh:/opt/spire/agent-entrypoint.sh:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - spire-join-token:/run/spire/join-token:ro
      - spire-agent-socket:/run/spire/sockets
    depends_on:
      spire-init:
        condition: service_completed_successfully
    healthcheck:
      test: ["/opt/spire/bin/spire-agent", "healthcheck", "-socketPath", "/run/spire/sockets/agent.sock"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 10s

  # ============================================================
  # spiffe-helper containers (one per workload)
  # ============================================================
  spiffe-helper-keycloak:
    image: ghcr.io/spiffe/spiffe-helper:0.11.0
    command: ["-config", "/opt/spiffe-helper/helper.conf"]
    labels:
      spiffe-workload: keycloak
    volumes:
      - ./keycloak/spiffe-helper.conf:/opt/spiffe-helper/helper.conf:ro
      - spire-agent-socket:/run/spire/sockets:ro
      - keycloak-certs:/opt/spiffe-certs
    depends_on:
      spire-agent:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "test", "-f", "/opt/spiffe-certs/svid.pem"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 10s

  spiffe-helper-frontend:
    image: ghcr.io/spiffe/spiffe-helper:0.11.0
    command: ["-config", "/opt/spiffe-helper/helper.conf"]
    labels:
      spiffe-workload: frontend
    volumes:
      - ./frontend/spiffe-helper.conf:/opt/spiffe-helper/helper.conf:ro
      - spire-agent-socket:/run/spire/sockets:ro
      - frontend-certs:/opt/spiffe-certs
    depends_on:
      spire-agent:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "test", "-f", "/opt/spiffe-certs/svid.pem"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 10s

  spiffe-helper-microservice1:
    image: ghcr.io/spiffe/spiffe-helper:0.11.0
    command: ["-config", "/opt/spiffe-helper/helper.conf"]
    labels:
      spiffe-workload: microservice1
    volumes:
      - ./microservice1/spiffe-helper.conf:/opt/spiffe-helper/helper.conf:ro
      - spire-agent-socket:/run/spire/sockets:ro
      - microservice1-certs:/opt/spiffe-certs
    depends_on:
      spire-agent:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "test", "-f", "/opt/spiffe-certs/svid.pem"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 10s

  spiffe-helper-microservice2:
    image: ghcr.io/spiffe/spiffe-helper:0.11.0
    command: ["-config", "/opt/spiffe-helper/helper.conf"]
    labels:
      spiffe-workload: microservice2
    volumes:
      - ./microservice2/spiffe-helper.conf:/opt/spiffe-helper/helper.conf:ro
      - spire-agent-socket:/run/spire/sockets:ro
      - microservice2-certs:/opt/spiffe-certs
    depends_on:
      spire-agent:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "test", "-f", "/opt/spiffe-certs/svid.pem"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 10s

  # ============================================================
  # Application Services
  # ============================================================
  keycloak:
    image: quay.io/keycloak/keycloak:nightly
    command:
      - start-dev
      - --import-realm
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
      KC_HEALTH_ENABLED: true
      KC_HOSTNAME: https://localhost:8443
      KC_HOSTNAME_BACKCHANNEL_DYNAMIC: "true"
      KC_HTTPS_CERTIFICATE_FILE: /opt/spiffe-certs/svid.pem
      KC_HTTPS_CERTIFICATE_KEY_FILE: /opt/spiffe-certs/svid_key.pem
      KC_TRUSTSTORE_PATHS: /opt/spiffe-certs/bundle.pem
      KC_HTTPS_CLIENT_AUTH: request
      KC_HTTP_ENABLED: "false"
      KC_HTTPS_PORT: 8443
    ports:
      - "8443:8443"
    volumes:
      - ./keycloak/demo-realm.json:/opt/keycloak/data/import/demo-realm.json:ro
      - keycloak-certs:/opt/spiffe-certs:ro
    healthcheck:
      test: ["CMD-SHELL", "{ printf 'HEAD /health/ready HTTP/1.0\\r\\n\\r\\n' >&0; grep 'HTTP/1.0 200'; } 0<>/dev/tcp/localhost/9000"]
      interval: 10s
      timeout: 5s
      retries: 15
      start_period: 30s
    depends_on:
      spiffe-helper-keycloak:
        condition: service_healthy

  frontend:
    build:
      context: .
      dockerfile: frontend/Dockerfile
    ports:
      - "8081:8443"
    environment:
      QUARKUS_OIDC_AUTH_SERVER_URL: https://keycloak:8443/realms/demo
      QUARKUS_OIDC_TOKEN_ISSUER: any
      QUARKUS_REST_CLIENT_MICROSERVICE1_URL: https://microservice1:8443
    volumes:
      - frontend-certs:/opt/spiffe-certs:ro
    depends_on:
      keycloak:
        condition: service_healthy
      spiffe-helper-frontend:
        condition: service_healthy

  microservice1:
    build:
      context: .
      dockerfile: microservice1/Dockerfile
    ports:
      - "8082:8443"
    environment:
      QUARKUS_OIDC_AUTH_SERVER_URL: https://keycloak:8443/realms/demo
      QUARKUS_OIDC_TOKEN_ISSUER: any
      QUARKUS_REST_CLIENT_MICROSERVICE2_URL: https://microservice2:8443
    volumes:
      - microservice1-certs:/opt/spiffe-certs:ro
    depends_on:
      keycloak:
        condition: service_healthy
      spiffe-helper-microservice1:
        condition: service_healthy

  microservice2:
    build:
      context: .
      dockerfile: microservice2/Dockerfile
    ports:
      - "8083:8443"
    environment:
      QUARKUS_OIDC_AUTH_SERVER_URL: https://keycloak:8443/realms/demo
      QUARKUS_OIDC_TOKEN_ISSUER: any
    volumes:
      - microservice2-certs:/opt/spiffe-certs:ro
    depends_on:
      keycloak:
        condition: service_healthy
      spiffe-helper-microservice2:
        condition: service_healthy

volumes:
  spire-server-socket:
  spire-join-token:
  spire-agent-socket:
  keycloak-certs:
  frontend-certs:
  microservice1-certs:
  microservice2-certs:
```

- [ ] **Step 2: Commit**

```bash
git add docker-compose.yml
git commit -m "Update docker-compose with SPIRE infrastructure and mTLS"
```

---

## Chunk 3: Keycloak Realm Configuration

### Task 7: Update Keycloak Realm for mTLS + Sender-Constrained Tokens

**Files:**
- Modify: `keycloak/demo-realm.json`

- [ ] **Step 1: Update sslRequired**

Change line 4 from `"sslRequired": "none"` to `"sslRequired": "all"`.

- [ ] **Step 2: Make frontend client confidential with cert-bound tokens**

Replace the `frontend` client block (lines 38-77) with:

```json
{
  "clientId": "frontend",
  "name": "Frontend Application",
  "enabled": true,
  "publicClient": false,
  "secret": "frontend-secret",
  "standardFlowEnabled": true,
  "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": true,
  "serviceAccountsEnabled": false,
  "protocol": "openid-connect",
  "rootUrl": "https://localhost:8081",
  "baseUrl": "https://localhost:8081",
  "redirectUris": [
    "https://localhost:8081/*"
  ],
  "webOrigins": [
    "https://localhost:8081"
  ],
  "attributes": {
    "tls.client.certificate.bound.access.tokens": "true"
  },
  "defaultClientScopes": [
    "email",
    "profile",
    "roles"
  ],
  "optionalClientScopes": [
    "offline_access"
  ],
  "protocolMappers": [
    {
      "name": "microservice1-audience",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-audience-mapper",
      "consentRequired": false,
      "config": {
        "included.client.audience": "microservice1",
        "id.token.claim": "false",
        "access.token.claim": "true",
        "introspection.token.claim": "true"
      }
    }
  ]
}
```

Key changes:
- `publicClient: false` (was `true`)
- Added `secret: "frontend-secret"`
- Added `attributes.tls.client.certificate.bound.access.tokens: "true"`
- All URLs changed from `http://localhost:8081` to `https://localhost:8081`

- [ ] **Step 3: Add cert-bound tokens to microservice1 client**

Add the `tls.client.certificate.bound.access.tokens` attribute to the microservice1 client (around line 90). Add to the existing `attributes` block:

```json
"attributes": {
  "standard.token.exchange.enabled": "true",
  "tls.client.certificate.bound.access.tokens": "true"
}
```

- [ ] **Step 4: Commit**

```bash
git add keycloak/demo-realm.json
git commit -m "Update realm: confidential frontend, cert-bound tokens, HTTPS URIs"
```

---

## Chunk 4: Quarkus Application Changes

### Task 8: Add quarkus-tls-registry Dependency

**Files:**
- Modify: `frontend/pom.xml`
- Modify: `microservice1/pom.xml`
- Modify: `microservice2/pom.xml`

- [ ] **Step 1: Add dependency to all three pom.xml files**

Add the following dependency to each service's `<dependencies>` section:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-tls-registry</artifactId>
</dependency>
```

In `frontend/pom.xml`: add after the `quarkus-rest-qute` dependency (line 33).
In `microservice1/pom.xml`: add after the `quarkus-oidc-client` dependency (line 33).
In `microservice2/pom.xml`: add after the `quarkus-rest-client-jackson` dependency (line 29).

- [ ] **Step 2: Commit**

```bash
git add frontend/pom.xml microservice1/pom.xml microservice2/pom.xml
git commit -m "Add quarkus-tls-registry dependency to all services"
```

---

### Task 9: Update Frontend application.properties

**Files:**
- Modify: `frontend/src/main/resources/application.properties`

- [ ] **Step 1: Replace with mTLS configuration**

```properties
quarkus.application.name=frontend

# TLS Configuration (SPIFFE certs from spiffe-helper)
quarkus.tls.spiffe.key-store.pem.0.cert=/opt/spiffe-certs/svid.pem
quarkus.tls.spiffe.key-store.pem.0.key=/opt/spiffe-certs/svid_key.pem
quarkus.tls.spiffe.trust-store.pem.certs=/opt/spiffe-certs/bundle.pem
quarkus.tls.spiffe.reload-period=5m

# HTTPS server (no client auth - browsers connect here)
quarkus.http.ssl-port=8443
quarkus.http.tls-configuration-name=spiffe
quarkus.http.insecure-requests=disabled
quarkus.http.ssl.client-auth=none

# OIDC (uses SPIFFE certs for mTLS to Keycloak)
quarkus.oidc.auth-server-url=https://keycloak:8443/realms/demo
quarkus.oidc.client-id=frontend
quarkus.oidc.credentials.secret=frontend-secret
quarkus.oidc.application-type=web-app
quarkus.oidc.authentication.redirect-path=/
quarkus.oidc.authentication.restore-path-after-redirect=true
quarkus.oidc.logout.path=/logout
quarkus.oidc.logout.post-logout-path=/
quarkus.oidc.tls.tls-configuration-name=spiffe

# REST client for microservice1 (mTLS)
quarkus.rest-client.microservice1.url=https://microservice1:8443
quarkus.rest-client.microservice1.tls-configuration-name=spiffe
```

Key changes from original:
- Removed `quarkus.http.port=8081`
- Added full TLS Registry config (`quarkus.tls.spiffe.*`)
- Added HTTPS server config with `client-auth=none`
- Changed OIDC URL to `https://keycloak:8443`
- Added `quarkus.oidc.credentials.secret=frontend-secret` (now confidential)
- Added `quarkus.oidc.tls.tls-configuration-name=spiffe`
- Removed `quarkus.oidc.credentials.jwt.audience` (not applicable)
- Changed REST client URL to HTTPS and added `tls-configuration-name`

- [ ] **Step 2: Commit**

```bash
git add frontend/src/main/resources/application.properties
git commit -m "Configure frontend with SPIFFE TLS and mTLS to Keycloak"
```

---

### Task 10: Update Microservice1 application.properties

**Files:**
- Modify: `microservice1/src/main/resources/application.properties`

- [ ] **Step 1: Replace with mTLS configuration**

```properties
quarkus.application.name=microservice1

# TLS Configuration (SPIFFE certs from spiffe-helper)
quarkus.tls.spiffe.key-store.pem.0.cert=/opt/spiffe-certs/svid.pem
quarkus.tls.spiffe.key-store.pem.0.key=/opt/spiffe-certs/svid_key.pem
quarkus.tls.spiffe.trust-store.pem.certs=/opt/spiffe-certs/bundle.pem
quarkus.tls.spiffe.reload-period=5m

# HTTPS server (mTLS required)
quarkus.http.ssl-port=8443
quarkus.http.tls-configuration-name=spiffe
quarkus.http.insecure-requests=disabled
quarkus.http.ssl.client-auth=required

# OIDC for validating incoming bearer tokens (mTLS to Keycloak)
quarkus.oidc.auth-server-url=https://keycloak:8443/realms/demo
quarkus.oidc.client-id=microservice1
quarkus.oidc.credentials.secret=microservice1-secret
quarkus.oidc.application-type=service
quarkus.oidc.tls.tls-configuration-name=spiffe

# OIDC Client for token exchange (mTLS to Keycloak)
quarkus.oidc-client.auth-server-url=https://keycloak:8443/realms/demo
quarkus.oidc-client.client-id=microservice1
quarkus.oidc-client.credentials.secret=microservice1-secret
quarkus.oidc-client.grant.type=exchange
quarkus.oidc-client.grant-options.exchange.audience=microservice2
quarkus.oidc-client.tls.tls-configuration-name=spiffe

# REST client for microservice2 (mTLS)
quarkus.rest-client.microservice2.url=https://microservice2:8443
quarkus.rest-client.microservice2.tls-configuration-name=spiffe
```

Key changes from original:
- Removed `quarkus.http.port=8082`
- Added full TLS Registry config
- Added HTTPS server with `client-auth=required`
- All URLs changed to HTTPS port 8443
- Added `tls-configuration-name=spiffe` to OIDC, OIDC client, and REST client

- [ ] **Step 2: Commit**

```bash
git add microservice1/src/main/resources/application.properties
git commit -m "Configure microservice1 with SPIFFE mTLS"
```

---

### Task 11: Update Microservice2 application.properties

**Files:**
- Modify: `microservice2/src/main/resources/application.properties`

- [ ] **Step 1: Replace with mTLS configuration**

```properties
quarkus.application.name=microservice2

# TLS Configuration (SPIFFE certs from spiffe-helper)
quarkus.tls.spiffe.key-store.pem.0.cert=/opt/spiffe-certs/svid.pem
quarkus.tls.spiffe.key-store.pem.0.key=/opt/spiffe-certs/svid_key.pem
quarkus.tls.spiffe.trust-store.pem.certs=/opt/spiffe-certs/bundle.pem
quarkus.tls.spiffe.reload-period=5m

# HTTPS server (mTLS required)
quarkus.http.ssl-port=8443
quarkus.http.tls-configuration-name=spiffe
quarkus.http.insecure-requests=disabled
quarkus.http.ssl.client-auth=required

# OIDC for validating incoming bearer tokens (mTLS to Keycloak)
quarkus.oidc.auth-server-url=https://keycloak:8443/realms/demo
quarkus.oidc.client-id=microservice2
quarkus.oidc.credentials.secret=microservice2-secret
quarkus.oidc.application-type=service
quarkus.oidc.tls.tls-configuration-name=spiffe
```

- [ ] **Step 2: Commit**

```bash
git add microservice2/src/main/resources/application.properties
git commit -m "Configure microservice2 with SPIFFE mTLS"
```

---

### Task 12: Update Dockerfiles

**Files:**
- Modify: `frontend/Dockerfile`
- Modify: `microservice1/Dockerfile`
- Modify: `microservice2/Dockerfile`

- [ ] **Step 1: Update EXPOSE port in all three Dockerfiles**

All three Dockerfiles have `EXPOSE 808x`. Change to `EXPOSE 8443`.

`frontend/Dockerfile` line 13: `EXPOSE 8081` → `EXPOSE 8443`
`microservice1/Dockerfile` line 13: `EXPOSE 8082` → `EXPOSE 8443`
`microservice2/Dockerfile` line 13: `EXPOSE 8083` → `EXPOSE 8443`

- [ ] **Step 2: Commit**

```bash
git add frontend/Dockerfile microservice1/Dockerfile microservice2/Dockerfile
git commit -m "Update Dockerfiles to expose HTTPS port 8443"
```

---

## Chunk 5: Verification

### Task 13: Test the Full Stack

- [ ] **Step 1: Build and start all services**

```bash
docker compose up --build -d
```

- [ ] **Step 2: Verify SPIRE infrastructure starts correctly**

```bash
docker compose logs spire-server spire-init spire-agent
```

Expected: Server healthy, entries created, agent joined with token.

- [ ] **Step 3: Verify certificates are provisioned**

```bash
docker compose exec spiffe-helper-keycloak ls -la /opt/spiffe-certs/
```

Expected: `svid.pem`, `svid_key.pem`, `bundle.pem` files present.

- [ ] **Step 4: Verify Keycloak starts with HTTPS**

```bash
curl -k https://localhost:8443/realms/demo/.well-known/openid-configuration | head -5
```

Expected: JSON response with HTTPS endpoints.

- [ ] **Step 5: Test login flow**

Open `https://localhost:8081` in browser, accept self-signed cert warning, log in as `ryan`/`password`.

Expected: Login succeeds, frontend shows user info and roles.

- [ ] **Step 6: Test service chain with sender-constrained tokens**

Click "Call Service" on the frontend.

Expected: Request flows Frontend → MS1 → MS2, all over mTLS. JWT tokens should contain `cnf.x5t#S256` claim showing certificate binding.

- [ ] **Step 7: Commit final state**

```bash
git add -A
git commit -m "SPIFFE mTLS + sender-constrained token exchange demo"
```
