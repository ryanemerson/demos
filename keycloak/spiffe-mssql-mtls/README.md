# SPIFFE/SPIRE TLS: Keycloak + SQL Server Demo

Demonstrates using SPIFFE/SPIRE to provision X.509 SVID certificates via
spiffe-helper sidecars, enabling **TLS** between Keycloak (nightly) and
SQL Server 2022. SQL Server is configured with `forceencryption = 1` to require
all connections to use TLS. The java-spiffe-helper provisions PKCS12 keystores
directly from the SPIFFE Workload API for Keycloak's JDBC connection.

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
│ helper           │           │ (mssql)           │
│ (keycloak)       │           │                   │
│                  │           │ server.crt        │
│ keystore.p12     │           │ server.key        │
│ truststore.p12   │           │ ca.crt            │
└────────┬─────────┘           └─────────┬─────────┘
         │ shared volume                 │ shared volume
┌────────▼─────────┐           ┌─────────▼────────┐
│   Keycloak       │◄── TLS ───►  SQL Server 2022 │
│   (nightly)      │           │                   │
│   :8080          │           │   :1433           │
└──────────────────┘           └───────────────────┘
```

## SPIFFE IDs

| Service    | SPIFFE ID                             |
|------------|---------------------------------------|
| Keycloak   | `spiffe://demo.example.com/keycloak`  |
| SQL Server | `spiffe://demo.example.com/mssql`     |

## How TLS Works

1. **SPIRE Server** issues X.509 SVIDs for registered workloads
2. **SPIRE Init** generates a join token, then registers workload entries using the agent's SPIFFE ID as parent
3. **SPIRE Agent** boots with the join token and attests containers via Docker labels (`spiffe-workload: <name>`)
4. **spiffe-helper** (Go) sidecar fetches SQL Server's SVID and writes PEM certs to a shared Docker volume
5. **java-spiffe-helper** sidecar fetches Keycloak's SVID and provisions PKCS12 keystores (`keystore.p12` and `truststore.p12`) directly from the SPIFFE Workload API
6. **SQL Server** starts with a custom entrypoint that writes `mssql.conf` with TLS settings (`tlscert`, `tlskey`, `forceencryption = 1`), creates the `keycloak` database, login, and user, then waits on the SQL Server process
7. **Keycloak** connects to SQL Server using `encrypt=true` with `trustServerCertificate=false` and a PKCS12 truststore containing the SPIFFE trust bundle for server certificate verification

## SQL Server TLS Enforcement

SQL Server is configured to enforce TLS via `mssql.conf`:

- **`forceencryption = 1`** — Requires all client connections to use TLS encryption
- **`tlscert` / `tlskey`** — Points to the SPIFFE-issued server certificate and private key

## Quick Start

```bash
docker compose up --build
```

Wait for all services to start (takes ~60-90 seconds for SPIRE bootstrapping, certificate provisioning, and SQL Server initialization).

### Access Keycloak

- **HTTP:**  http://localhost:8080
- **Admin:** `admin` / `admin`

## Verify TLS

Check that SQL Server is using TLS:

```bash
docker compose exec mssql /opt/mssql-tools18/bin/sqlcmd -S localhost -U keycloak -P 'Keycloak1!' -d keycloak -C -Q "SELECT encrypt_option FROM sys.dm_exec_connections WHERE session_id = @@SPID"
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
| `spiffe-helper-mssql`          | `ghcr.io/spiffe/spiffe-helper:0.11.0`   | Fetches/rotates SQL Server SVID               |
| `mssql`                        | `mcr.microsoft.com/mssql/server:2022-latest` (custom entrypoint) | Database with TLS enforcement  |
| `keycloak`                     | `quay.io/keycloak/keycloak:nightly`      | Identity server, TLS client to SQL Server     |

## Cleanup

```bash
docker compose down -v
```
