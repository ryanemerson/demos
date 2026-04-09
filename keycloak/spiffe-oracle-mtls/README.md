# SPIFFE/SPIRE mTLS: Keycloak + Oracle Demo

Demonstrates using SPIFFE/SPIRE to provision X.509 SVID certificates via
spiffe-helper sidecars, enabling **mutual TLS** between Keycloak (nightly) and
Oracle Database Free 23ai. Oracle is configured with a TCPS listener on port
2484 using an Oracle wallet built from SPIFFE certificates. The java-spiffe-helper
provisions PKCS12 keystores directly from the SPIFFE Workload API, and Keycloak
connects via the Oracle thin JDBC driver with Java SSL properties for client
certificate authentication.

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
│ helper           │           │ (oracle)          │
│ (keycloak)       │           │                   │
│                  │           │ server.crt        │
│ keystore.p12     │           │ server.key        │
│ truststore.p12   │           │ ca.crt            │
└────────┬─────────┘           └─────────┬─────────┘
         │ shared volume                 │ shared volume
         │                      ┌────────▼─────────┐
         │                      │ oracle-cert-init  │
         │                      │ (fix permissions) │
         │                      └────────┬─────────┘
         │                               │
┌────────▼─────────┐           ┌─────────▼────────┐
│   Keycloak       │◄── mTLS ──►  Oracle Free     │
│   (nightly +     │           │  23ai (full)      │
│    ojdbc11.jar)  │           │                   │
│   :8080          │  TCPS     │  :1521 / :2484    │
└──────────────────┘           └───────────────────┘
```

## SPIFFE IDs

| Service  | SPIFFE ID                             |
|----------|---------------------------------------|
| Keycloak | `spiffe://demo.example.com/keycloak`  |
| Oracle   | `spiffe://demo.example.com/oracle`    |

## How mTLS Works

1. **SPIRE Server** issues X.509 SVIDs for registered workloads
2. **SPIRE Init** generates a join token, then registers workload entries using the agent's SPIFFE ID as parent
3. **SPIRE Agent** boots with the join token and attests containers via Docker labels (`spiffe-workload: <name>`)
4. **spiffe-helper** (Go) sidecar fetches Oracle's SVID and writes PEM certs to a shared Docker volume
5. **java-spiffe-helper** sidecar fetches Keycloak's SVID and provisions PKCS12 keystores (`keystore.p12` and `truststore.p12`) directly from the SPIFFE Workload API
6. **oracle-cert-init** fixes certificate file permissions for the oracle user (uid 54321), since the spiffe-helper writes files as root
7. **Oracle** starts and runs a startup script (`setup-tls.sh`) that:
   - Creates an Oracle auto-login wallet using `orapki` from the SPIFFE certificates
   - Imports the CA as a trusted certificate and the server cert/key via PKCS12
   - Configures `sqlnet.ora` for TCPS with `SSL_CLIENT_AUTHENTICATION = TRUE`
   - Configures `listener.ora` with a TCPS endpoint on port 2484
   - Restarts the listener to activate TCPS
8. **Keycloak** connects to Oracle via `jdbc:oracle:thin:@tcps://oracle:2484/FREEPDB1` with `JAVA_OPTS_APPEND` providing `javax.net.ssl.*` system properties pointing to the PKCS12 keystore and truststore. The Oracle SVID includes a `dns:oracle` SAN matching the JDBC hostname, so server DN matching succeeds — establishing mutual TLS

## Oracle TCPS Configuration

Oracle's TCPS (TLS) is configured through:

- **Oracle Wallet** — An auto-login wallet (`cwallet.sso`) created by `orapki` from the SPIFFE certificates, allowing the listener to use TLS without interactive password entry
- **`sqlnet.ora`** — Sets `SSL_CLIENT_AUTHENTICATION = TRUE` to require client certificates and `SSL_VERSION = 1.2`
- **`listener.ora`** — Adds a TCPS endpoint on port 2484 alongside the standard TCP listener on port 1521
- **`gvenzl/oracle-free:23-full`** — The full image is required because the slim image lacks the `orapki` jar files needed for wallet creation

## Oracle JDBC Driver

The Oracle JDBC driver (`ojdbc11.jar`) is not bundled with Keycloak. A custom
Keycloak Dockerfile (`keycloak/Dockerfile.keycloak`) downloads it from Maven
Central and adds it to `/opt/keycloak/providers/`.

## Quick Start

```bash
docker compose up --build
```

Wait for all services to start. Oracle takes significantly longer than other
databases (~2-3 minutes for first-time initialization, plus SPIRE bootstrapping).

### Access Keycloak

- **HTTP:**  http://localhost:8080
- **Admin:** `admin` / `admin`

## Verify mTLS

Check that Oracle's TCPS listener is active:

```bash
docker compose exec oracle lsnrctl status | grep -i tcps
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
| `spiffe-helper-oracle`         | `ghcr.io/spiffe/spiffe-helper:0.11.0`   | Fetches/rotates Oracle SVID                   |
| `oracle-cert-init`             | `alpine:3.19`                            | Fixes cert permissions for oracle user        |
| `oracle`                       | `gvenzl/oracle-free:23-full` (custom entrypoint + TLS setup) | Database with TCPS (mTLS) |
| `keycloak`                     | Custom (nightly + ojdbc11.jar)           | Identity server, mTLS client to Oracle        |

## Cleanup

```bash
docker compose down -v
```
