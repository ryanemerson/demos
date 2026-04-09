# SPIFFE/SPIRE mTLS: Keycloak + PostgreSQL Demo

Demonstrates using SPIFFE/SPIRE to provision X.509 SVID certificates via
spiffe-helper sidecars, enabling **mutual TLS** between Keycloak (nightly) and
PostgreSQL 18. PostgreSQL is configured to reject all connections that do not
use mTLS.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     SPIRE Server                                │
│              trust domain: demo.example.com                     │
└────────────────────┬────────────────────────────────────────────┘
                     │ join token
┌────────────────────▼────────────────────────────────────────────┐
│                     SPIRE Agent                                 │
│              Docker workload attestor                           │
└────────┬───────────────────────────────┬────────────────────────┘
         │                               │
┌────────▼─────────┐           ┌─────────▼────────┐
│ java-spiffe-     │           │ spiffe-helper     │
│ helper           │           │ (postgres)        │
│ (keycloak)       │           │                   │
│                  │           │ server.crt        │
│ keystore.p12     │           │ server.key        │
│ truststore.p12   │           │ ca.crt            │
└────────┬─────────┘           └─────────┬─────────┘
         │ shared volume                 │ shared volume
         │                               │
┌────────▼─────────┐                     │
│ spiffe-helper    │                     │
│ (keycloak)       │                     │
│                  │                     │
│ bundle.pem       │                     │
└────────┬─────────┘                     │
         │                               │
┌────────▼─────────┐           ┌─────────▼────────┐
│   Keycloak       │◄── mTLS ──►  PostgreSQL 18   │
│   (nightly)      │           │                   │
│   :8080          │           │   :5432           │
└──────────────────┘           └───────────────────┘
```

## SPIFFE IDs

| Service    | SPIFFE ID                               |
|------------|-----------------------------------------|
| Keycloak   | `spiffe://demo.example.com/keycloak`    |
| PostgreSQL | `spiffe://demo.example.com/postgres`    |

## How mTLS Works

1. **SPIRE Server** issues X.509 SVIDs for registered workloads
2. **SPIRE Init** generates a join token, then registers workload entries using the agent's SPIFFE ID as parent
3. **SPIRE Agent** boots with the join token and attests containers via Docker labels (`spiffe-workload: <name>`)
4. **spiffe-helper** (Go) sidecar fetches PostgreSQL's SVID and writes PEM certs to a shared Docker volume. A second spiffe-helper instance fetches Keycloak's trust bundle as PEM (required by the PostgreSQL JDBC driver's `sslrootcert` parameter, which only supports PEM format)
5. **java-spiffe-helper** sidecar fetches Keycloak's SVID and provisions PKCS12 keystores (`keystore.p12` and `truststore.p12`) directly from the SPIFFE Workload API
6. **PostgreSQL** starts with SSL enabled, requiring client certificates signed by the SPIFFE trust bundle. Plain TCP and TLS-without-client-cert connections are explicitly rejected via `pg_hba.conf`
7. **Keycloak** connects to PostgreSQL using the PKCS12 keystore as the client certificate via `sslkey`, with `sslrootcert` pointing to the PEM trust bundle and `sslmode=verify-full` — establishing mutual TLS

## PostgreSQL JDBC SSL Configuration

The PostgreSQL JDBC driver has specific requirements for SSL parameters:

- **`sslkey`** — Supports PKCS12 (`.p12`) keystores for client certificate authentication. The driver's `PKCS12KeyManager` expects the key alias to be `user`
- **`sslpassword`** — Password for the PKCS12 keystore
- **`sslrootcert`** — Only supports PEM format (not PKCS12), which is why a Go spiffe-helper sidecar provides `bundle.pem` alongside the java-spiffe-helper's PKCS12 stores
- **`sslmode=verify-full`** — Verifies the server certificate hostname matches

## PostgreSQL mTLS Enforcement

PostgreSQL is configured via `pg_hba.conf` to enforce mTLS on all TCP connections:

- **`hostssl ... cert`** — Accepts only SSL connections where the client presents a certificate signed by the SPIFFE trust bundle, with the certificate CN matching the database username
- **`hostnossl ... reject`** — Explicitly rejects any plain (non-SSL) TCP connection
- **`local ... trust`** — Allows Unix socket connections for the in-container healthcheck only

## Quick Start

```bash
docker compose up --build
```

Wait for all services to start (takes ~30-60 seconds for SPIRE bootstrapping and certificate provisioning).

### Access Keycloak

- **HTTP:**  http://localhost:8080
- **Admin:** `admin` / `admin`

## Testing Connection Modes

The `KC_DB_URL` in `docker-compose.yml` includes commented connection modes you can toggle to verify PostgreSQL's mTLS enforcement:

```yaml
# Plain connection — no SSL (rejected by hostnossl rule)
# KC_DB_URL: "jdbc:postgresql://postgres:5432/keycloak"

# TLS only — server cert verified, but no client cert (rejected by cert rule)
# KC_DB_URL: "jdbc:postgresql://postgres:5432/keycloak?ssl=true&sslmode=verify-full&sslrootcert=/opt/spiffe-certs/bundle.pem"

# mTLS via PEM (requires spiffe-helper PEM output + DER key conversion)
# KC_DB_URL: "jdbc:postgresql://postgres:5432/keycloak?ssl=true&sslmode=verify-full&sslcert=/opt/spiffe-certs/svid.pem&sslkey=/opt/spiffe-certs/svid_key.pem&sslrootcert=/opt/spiffe-certs/bundle.pem&pemKeyAlgorithm=EC"

# mTLS via PKCS12 keystore (provisioned by java-spiffe-helper) and PEM trust bundle (provisioned by spiffe-helper)
KC_DB_URL: "jdbc:postgresql://postgres:5432/keycloak?ssl=true&sslmode=verify-full&sslkey=/opt/spiffe-certs/keystore.p12&sslpassword=changeit&sslrootcert=/opt/spiffe-certs/bundle.pem"
```

Uncomment the desired mode and restart Keycloak to test:

```bash
docker compose up -d --force-recreate keycloak
```

## Verify mTLS

Check that PostgreSQL shows SSL connections with client certificates:

```bash
docker compose exec postgres psql -U keycloak -c "SELECT ssl, client_serial, client_dn FROM pg_stat_ssl WHERE ssl = true;"
```

Check Keycloak logs for successful startup:

```bash
docker compose logs keycloak | grep -i "started"
```

## Services

| Container                      | Image                                    | Purpose                                       |
|--------------------------------|------------------------------------------|-----------------------------------------------|
| `spire-server`                 | `ghcr.io/spiffe/spire-server:1.12.0`     | SPIFFE identity control plane                 |
| `spire-init`                   | Custom (Alpine + spire-server CLI)       | Generates join token, registers workloads     |
| `spire-agent`                  | Custom (Alpine + spire-agent)            | Attests Docker workloads                      |
| `java-spiffe-helper-keycloak`  | `ghcr.io/spiffe/java-spiffe-helper:0.8.16` | Provisions PKCS12 keystores for Keycloak    |
| `spiffe-helper-keycloak`       | `ghcr.io/spiffe/spiffe-helper:0.11.0`   | Fetches PEM trust bundle for Keycloak         |
| `spiffe-helper-postgres`       | `ghcr.io/spiffe/spiffe-helper:0.11.0`   | Fetches/rotates PostgreSQL SVID               |
| `postgres`                     | `postgres:18` (custom entrypoint)        | Database with mTLS enforcement                |
| `keycloak`                     | `quay.io/keycloak/keycloak:nightly`      | Identity server, mTLS client to Postgres      |

## Cleanup

```bash
docker compose down -v
```
