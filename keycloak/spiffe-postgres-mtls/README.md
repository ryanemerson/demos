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
7. **Keycloak** connects to PostgreSQL using Keycloak's native `KC_DB_TLS_*` and `KC_DB_MTLS_*` environment variables — the PEM trust bundle (`bundle.pem`) for server certificate verification and the PKCS12 keystore (`keystore.p12`) for client certificate authentication — establishing mutual TLS

## PostgreSQL TLS Trust Bundle

The PostgreSQL JDBC driver's `sslrootcert` parameter only supports PEM format (not PKCS12). This is why a Go spiffe-helper sidecar provides `bundle.pem` alongside the java-spiffe-helper's PKCS12 stores. Keycloak references this PEM bundle via `KC_DB_TLS_TRUST_STORE_FILE`. The java-spiffe-helper provisions `keystore.p12` with the key alias set to `user` (required by the PostgreSQL JDBC driver's `PKCS12KeyManager`).

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
