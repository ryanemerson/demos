# SPIFFE mTLS + Sender-Constrained Token Exchange Design

## Overview

Update the existing Keycloak token-exchange demo to add SPIFFE SPIRE infrastructure for X.509-SVID provisioning, mTLS between all services, and RFC 8705 mTLS certificate-bound access tokens (sender-constrained tokens). Deployment remains Docker Compose, launchable with a single `docker compose up`.

## Architecture

```
Browser --TLS--> Frontend (8443) --mTLS--> Microservice1 (8443) --mTLS--> Microservice2 (8443)
                     | mTLS                     | mTLS
                     +--------- Keycloak (8443) -+

SPIRE Server <-- SPIRE Agent <-- spiffe-helper (x4, one per workload)
                                      | writes certs to shared volumes
                                 svid.pem, svid_key.pem, bundle.pem
```

## Docker Compose Orchestration

All orchestration handled by `depends_on` conditions — no external scripts needed.

```
spire-server (health check)
    | service_healthy
spire-init (registers entries + writes join token, then exits)
    | service_completed_successfully
spire-agent (reads join token, starts with docker workload attestor)
    | service_healthy
spiffe-helper-* (4x, fetch SVIDs, write certs)
    | service_healthy (cert files exist)
keycloak, frontend, microservice1, microservice2
```

## SPIRE Configuration

- **Trust domain:** `demo.example.com`
- **Node attestor:** `join_token`
- **Workload attestor:** `docker` (attests by Docker container labels)
- **SPIRE Agent:** runs with `pid: "host"` and `/var/run/docker.sock` volume mounted

### Workload Entries

Docker labels are placed on the **spiffe-helper** containers (not the workload containers), since the helpers are the processes connecting to the SPIRE agent socket.

| SPIFFE ID | Docker Label on Helper | DNS |
|---|---|---|
| `spiffe://demo.example.com/keycloak` | `spiffe-workload=keycloak` | `keycloak` |
| `spiffe://demo.example.com/frontend` | `spiffe-workload=frontend` | `frontend` |
| `spiffe://demo.example.com/microservice1` | `spiffe-workload=microservice1` | `microservice1` |
| `spiffe://demo.example.com/microservice2` | `spiffe-workload=microservice2` | `microservice2` |

### Certificate Delivery

Each workload has a dedicated `spiffe-helper` container that:
- Connects to the SPIRE agent socket (shared volume)
- Fetches X.509-SVID for the workload
- Writes `svid.pem`, `svid_key.pem`, `bundle.pem` to a per-workload cert volume
- Health check verifies cert files exist

Each helper gets its own config file co-located with the respective component (e.g., `keycloak/spiffe-helper.conf`, `frontend/spiffe-helper.conf`, etc.) since output paths differ per workload.

## TLS Configuration

| Connection | Mode | Client Auth |
|---|---|---|
| Browser → Frontend | TLS | `none` |
| Browser → Keycloak (OIDC redirect) | TLS | `none` (request mode) |
| Frontend → Keycloak | mTLS | SPIFFE cert presented |
| Frontend → Microservice1 | mTLS | SPIFFE cert presented |
| Microservice1 → Keycloak | mTLS | SPIFFE cert presented |
| Microservice1 → Microservice2 | mTLS | SPIFFE cert presented |

### Keycloak TLS

- `KC_HTTPS_CERTIFICATE_FILE=/opt/spiffe-certs/svid.pem`
- `KC_HTTPS_CERTIFICATE_KEY_FILE=/opt/spiffe-certs/svid_key.pem`
- `KC_TRUSTSTORE_PATHS=/opt/spiffe-certs/bundle.pem`
- `KC_HTTPS_CLIENT_AUTH=request` (optional — services present certs, browsers don't)
- `KC_HOSTNAME=https://localhost:8443` (full URL for v2 hostname provider)
- `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true` (preserve existing setting)
- `KC_HTTPS_PORT=8443`
- `KC_HTTP_ENABLED=false` (disable plaintext HTTP listener)
- Remove `KC_HTTP_PORT` (no longer needed)
- Keep `--start-dev` mode but disable HTTP explicitly

### Quarkus TLS (all services)

Requires `quarkus-tls-registry` Maven dependency added to each service's `pom.xml`.

```properties
quarkus.tls.spiffe.key-store.pem.0.cert=/opt/spiffe-certs/svid.pem
quarkus.tls.spiffe.key-store.pem.0.key=/opt/spiffe-certs/svid_key.pem
quarkus.tls.spiffe.trust-store.pem.certs=/opt/spiffe-certs/bundle.pem
quarkus.tls.spiffe.reload-period=5m

quarkus.http.ssl-port=8443
quarkus.http.tls-configuration-name=spiffe
quarkus.http.insecure-requests=disabled
```

- Frontend: `quarkus.http.ssl.client-auth=none`
- Microservice1: `quarkus.http.ssl.client-auth=required`
- Microservice2: `quarkus.http.ssl.client-auth=required`
- All OIDC and REST client connections: `tls-configuration-name=spiffe`
- All `quarkus.oidc.auth-server-url` updated from `http://keycloak:8080` to `https://keycloak:8443`
- All REST client URLs updated from `http://` to `https://` with port 8443
- Remove `quarkus.http.port` lines (only SSL port used)
- Keep `quarkus.oidc.token.issuer=any` (issuer URL varies between internal/external access)

## Sender-Constrained Tokens (RFC 8705)

### Keycloak Realm Config

- Change `frontend` client from `publicClient: true` to `publicClient: false` (confidential) to support certificate-bound tokens. Add client secret or use `client-x509` authenticator.
- Enable `tls-client-certificate-bound-access-tokens: true` on `frontend` and `microservice1` clients
- Update `sslRequired` from `"none"` to `"all"`
- Update redirect URIs from `http://localhost:8081/*` to `https://localhost:8081/*`

### Token Flow

1. **Frontend → Keycloak** (backchannel auth code exchange over mTLS): Keycloak binds access token to Frontend's SPIFFE cert thumbprint (`cnf.x5t#S256` claim). Note: the browser redirect to Keycloak for login is plain TLS; cert binding occurs only on the backchannel token endpoint call.
2. **Frontend → Microservice1** (mTLS + bound token): MS1 verifies `cnf` claim matches Frontend's TLS client cert
3. **Microservice1 → Keycloak** (token exchange over mTLS): Keycloak issues new token bound to MS1's SPIFFE cert
4. **Microservice1 → Microservice2** (mTLS + bound token): MS2 verifies `cnf` claim matches MS1's TLS client cert

### Resource Server Verification

Microservice1 and Microservice2 must verify the `cnf.x5t#S256` claim matches the caller's TLS client certificate. This requires Quarkus OIDC configuration for certificate-bound token verification (e.g., `quarkus.oidc.token.certificate-bound` or equivalent).

## File Changes Summary

### New Files

- `spire/server.conf` — SPIRE server configuration
- `spire/agent.conf` — SPIRE agent configuration
- `spire/init.sh` — Entry registration and join token generation
- `keycloak/spiffe-helper.conf` — spiffe-helper config for Keycloak
- `frontend/spiffe-helper.conf` — spiffe-helper config for Frontend
- `microservice1/spiffe-helper.conf` — spiffe-helper config for Microservice1
- `microservice2/spiffe-helper.conf` — spiffe-helper config for Microservice2

### Modified Files

- `docker-compose.yml` — Add SPIRE infrastructure, update ports/URLs, add volumes and labels
- `keycloak/demo-realm.json` — Make frontend confidential, enable certificate-bound tokens, update sslRequired, update redirect URIs
- `frontend/pom.xml` — Add `quarkus-tls-registry` dependency
- `frontend/src/main/resources/application.properties` — Add TLS config, update URLs
- `microservice1/pom.xml` — Add `quarkus-tls-registry` dependency
- `microservice1/src/main/resources/application.properties` — Add TLS config, update URLs
- `microservice2/pom.xml` — Add `quarkus-tls-registry` dependency
- `microservice2/src/main/resources/application.properties` — Add TLS config, update URLs

### Port Mapping (host → container)

- Keycloak: `8443:8443`
- Frontend: `8081:8443`
- Microservice1: `8082:8443`
- Microservice2: `8083:8443`
