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
│ spiffe-helper    │           │ spiffe-helper     │
│ (keycloak)       │           │ (postgres)        │
│                  │           │                   │
│ svid.pem         │           │ server.crt        │
│ svid_key.pem     │           │ server.key        │
│ bundle.pem       │           │ ca.crt            │
└────────┬─────────┘           └─────────┬─────────┘
         │ shared volume                 │ shared volume
┌────────▼─────────┐           ┌─────────▼────────┐
│ keycloak-cert-   │           │                   │
│ init (PEM→DER)   │           │                   │
└────────┬─────────┘           │                   │
         │ svid_key.der        │                   │
┌────────▼─────────┐           ┌─────────▼────────┐
│   Keycloak       │◄── mTLS ──►  PostgreSQL 18   │
│   (nightly)      │           │                   │
│   :8080 / :8443  │           │   :5432           │
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
4. **spiffe-helper** sidecars fetch SVIDs and write certs to shared Docker volumes
5. **keycloak-cert-init** converts the Keycloak SVID private key from PEM to PKCS8 DER format (required by the PostgreSQL JDBC driver)
6. **PostgreSQL** starts with SSL enabled, requiring client certificates signed by the SPIFFE trust bundle (`clientcert=verify-ca`). Plain TCP and TLS-without-client-cert connections are explicitly rejected
7. **Keycloak** connects to PostgreSQL using its SVID as the client cert with `sslmode=verify-full`, verifying the server's certificate hostname matches — establishing mutual TLS

## PostgreSQL mTLS Enforcement

PostgreSQL is configured via `pg_hba.conf` to enforce mTLS on all TCP connections:

- **`hostssl ... scram-sha-256 clientcert=verify-ca`** — Accepts only SSL connections where the client presents a certificate signed by the SPIFFE trust bundle
- **`hostnossl ... reject`** — Explicitly rejects any plain (non-SSL) TCP connection
- **`local ... trust`** — Allows Unix socket connections for the in-container healthcheck only

## Quick Start

```bash
docker compose up --build
```

Wait for all services to start (takes ~30-60 seconds for SPIRE bootstrapping and certificate provisioning).

### Access Keycloak

- **HTTP:**  http://localhost:8080
- **HTTPS:** https://localhost:8443 (SPIFFE-issued certificate)
- **Admin:** `admin` / `admin`

## Testing Connection Modes

The `KC_DB_URL` in `docker-compose.yml` includes three connection modes you can toggle to verify PostgreSQL's mTLS enforcement:

```yaml
# Plain connection — no SSL (rejected by hostnossl rule)
# KC_DB_URL: "jdbc:postgresql://postgres:5432/keycloak"

# TLS only — server cert verified, but no client cert (rejected by clientcert=verify-ca)
# KC_DB_URL: "jdbc:postgresql://postgres:5432/keycloak?ssl=true&sslmode=verify-full&sslrootcert=/opt/spiffe-certs/bundle.pem"

# mTLS — full mutual TLS with SPIFFE certificates (accepted)
KC_DB_URL: "jdbc:postgresql://postgres:5432/keycloak?ssl=true&sslmode=verify-full&sslcert=/opt/spiffe-certs/svid.pem&sslkey=/opt/spiffe-certs/svid_key.der&sslrootcert=/opt/spiffe-certs/bundle.pem&sslkeytype=DER"
```

Uncomment the desired mode and restart Keycloak to test:

```bash
docker compose up -d --force-recreate keycloak
```

## Verify mTLS

Check that PostgreSQL is enforcing SSL with client certs:

```bash
docker compose exec postgres psql -U keycloak -c "SELECT ssl, client_serial FROM pg_stat_ssl WHERE pid = pg_backend_pid();"
```

Check Keycloak logs for successful database connection:

```bash
docker compose logs keycloak | grep -i "database"
```

## Services

| Container                | Image                                    | Purpose                                       |
|--------------------------|------------------------------------------|-----------------------------------------------|
| `spire-server`           | `ghcr.io/spiffe/spire-server:1.12.0`     | SPIFFE identity control plane                 |
| `spire-init`             | Custom (Alpine + spire-server CLI)       | Generates join token, registers workloads     |
| `spire-agent`            | Custom (Alpine + spire-agent)            | Attests Docker workloads                      |
| `spiffe-helper-keycloak` | Custom (Alpine + spiffe-helper)          | Fetches/rotates Keycloak SVID                 |
| `spiffe-helper-postgres` | Custom (Alpine + spiffe-helper)          | Fetches/rotates PostgreSQL SVID               |
| `keycloak-cert-init`     | `alpine:3.19`                            | Converts SVID key PEM to PKCS8 DER for JDBC   |
| `postgres`               | `postgres:18` (custom entrypoint)        | Database with mTLS enforcement                |
| `keycloak`               | `quay.io/keycloak/keycloak:nightly`      | Identity server, mTLS client to Postgres      |

## Cleanup

```bash
docker compose down -v
```
