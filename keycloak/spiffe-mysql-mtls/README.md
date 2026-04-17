# SPIFFE/SPIRE mTLS: Keycloak + MySQL Demo

Demonstrates using SPIFFE/SPIRE to provision X.509 SVID certificates via
spiffe-helper sidecars, enabling **mutual TLS** between Keycloak (nightly) and
MySQL 8.4. MySQL is configured to require secure transport and X.509 client
certificates for the Keycloak database user. The java-spiffe-helper provisions
PKCS12 keystores directly from the SPIFFE Workload API for Keycloak's JDBC
connection.

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
│ helper           │           │ (mysql)           │
│ (keycloak)       │           │                   │
│                  │           │ server.crt        │
│ keystore.p12     │           │ server.key        │
│ truststore.p12   │           │ ca.crt            │
└────────┬─────────┘           └─────────┬─────────┘
         │ shared volume                 │ shared volume
┌────────▼─────────┐           ┌─────────▼────────┐
│   Keycloak       │◄── mTLS ──►    MySQL 8.4     │
│   (nightly)      │           │                   │
│   :8080          │           │   :3306           │
└──────────────────┘           └───────────────────┘
```

## SPIFFE IDs

| Service  | SPIFFE ID                             |
|----------|---------------------------------------|
| Keycloak | `spiffe://demo.example.com/keycloak`  |
| MySQL    | `spiffe://demo.example.com/mysql`     |

## How mTLS Works

1. **SPIRE Server** issues X.509 SVIDs for registered workloads
2. **SPIRE Init** generates a join token, then registers workload entries using the agent's SPIFFE ID as parent
3. **SPIRE Agent** boots with the join token and attests containers via Docker labels (`spiffe-workload: <name>`)
4. **spiffe-helper** (Go) sidecar fetches MySQL's SVID and writes PEM certs to a shared Docker volume
5. **java-spiffe-helper** sidecar fetches Keycloak's SVID and provisions PKCS12 keystores (`keystore.p12` and `truststore.p12`) directly from the SPIFFE Workload API
6. **MySQL** starts with `--require-secure-transport=ON` and `--ssl-ca/cert/key` flags pointing to its SPIFFE certificates. An init script runs `ALTER USER 'keycloak'@'%' REQUIRE X509` to enforce client certificate authentication
7. **Keycloak** connects to MySQL using Keycloak's native `KC_DB_TLS_*` and `KC_DB_MTLS_*` environment variables — the PKCS12 truststore for CA verification and the PKCS12 keystore for client certificate authentication — establishing mutual TLS

## MySQL mTLS Enforcement

MySQL is configured to enforce mTLS through two mechanisms:

- **`--require-secure-transport=ON`** — Rejects any non-SSL connection attempt
- **`ALTER USER 'keycloak'@'%' REQUIRE X509`** — Requires the keycloak user to present a valid X.509 client certificate signed by a trusted CA

## Quick Start

```bash
docker compose up --build
```

Wait for all services to start (takes ~30-60 seconds for SPIRE bootstrapping and certificate provisioning).

### Access Keycloak

- **HTTP:**  http://localhost:8080
- **Admin:** `admin` / `admin`

## Verify mTLS

Check that MySQL is enforcing SSL:

```bash
docker compose exec mysql mysql -u keycloak -pkeycloak -e "SHOW STATUS LIKE 'Ssl_cipher';"
```

Check Keycloak logs for successful database connection:

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
| `spiffe-helper-mysql`          | `ghcr.io/spiffe/spiffe-helper:0.11.0`   | Fetches/rotates MySQL SVID                    |
| `mysql`                        | `mysql:8.4` (custom entrypoint)          | Database with mTLS enforcement                |
| `keycloak`                     | `quay.io/keycloak/keycloak:nightly`      | Identity server, mTLS client to MySQL         |

## Cleanup

```bash
docker compose down -v
```
