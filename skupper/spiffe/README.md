# Skupper + SPIFFE/SPIRE mTLS Demo

Demonstrates how [Skupper](https://skupper.io) bridges two isolated Kubernetes namespaces while [SPIRE](https://spiffe.io/docs/latest/spire-about/) provides workload identity and mutual TLS at the application layer.

A Quarkus HTTP server in **site-east** handles requests. A Quarkus client in **site-west** calls it every second. Neither namespace has direct network access to the other — all traffic flows through Skupper. Every connection is authenticated and encrypted using [SPIFFE](https://spiffe.io) X.509 SVIDs issued by per-site SPIRE deployments.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│ minikube                                                            │
│                                                                     │
│  ┌── site-east ────────────────────────────┐                        │
│  │  SPIRE Server (east.demo.example.com)   │                        │
│  │  SPIRE Agent  (/run/spire/east/)        │                        │
│  │                                         │                        │
│  │  hello-server (Deployment)              │                        │
│  │   ├─ init: fetch X.509 SVID from SPIRE  │                        │
│  │   └─ Quarkus :8443 (mTLS required)      │                        │
│  │                                         │                        │
│  │  Skupper Connector → hello-server:8443  │                        │
│  └─────────────────────┬───────────────────┘                        │
│                        │ Skupper network (TCP tunnel)               │
│  ┌── site-west ────────┴───────────────────┐                        │
│  │  SPIRE Server (west.demo.example.com)   │                        │
│  │  SPIRE Agent  (/run/spire/west/)        │                        │
│  │                                         │                        │
│  │  hello-client (Deployment)              │                        │
│  │   ├─ init: fetch X.509 SVID from SPIRE  │                        │
│  │   └─ Quarkus: calls hello-server:8443   │                        │
│  │              every 1s with random name   │                        │
│  │                                         │                        │
│  │  Skupper Listener → hello-server:8443   │                        │
│  └─────────────────────────────────────────┘                        │
└─────────────────────────────────────────────────────────────────────┘
```

### mTLS identity

| Workload | SPIFFE ID |
|----------|-----------|
| hello-server | `spiffe://east.demo.example.com/hello-server` |
| hello-client | `spiffe://west.demo.example.com/hello-client` |

Each SPIRE deployment has its own trust domain. The two servers exchange CA bundles (**SPIFFE federation**) so workloads can validate each other's certificates across trust domains.

## How it works

1. **SPIRE Agents** run as DaemonSets in each namespace, connecting to their local SPIRE Server.
2. Before each app pod starts, an **init container** (SPIRE Agent image) fetches the workload's X.509 SVID and writes `svid.pem`, `svid_key.pem`, and `bundle.pem` to a shared volume.
3. The **Quarkus app** starts with those PEM files already present. The Quarkus TLS Registry loads them and serves/connects over mTLS.
4. **`SpiffeService`** in each app connects to the SPIRE Workload API (via `java-spiffe-provider`) and refreshes the PEM files every 60 seconds, handling SPIRE certificate rotation automatically. The TLS registry reloads files on the same interval.
5. Skupper carries the raw TCP/TLS stream between the Listener in site-west and the Connector in site-east. Skupper does not terminate the application TLS — mTLS is truly end-to-end between the Quarkus workloads.

## Prerequisites

- [minikube](https://minikube.sigs.k8s.io/docs/start/) with the Docker driver
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [skupper CLI](https://skupper.io/install/)
- JDK 17+
- Maven 3.8+
- Docker

## Setup

The demo requires four terminal windows.

### Terminal 1 — minikube

```bash
cd deploy
./init-k8s.sh
```

This provisions minikube (4 GB RAM), creates the `site-east` and `site-west` namespaces, builds the Docker images inside minikube, and starts `minikube tunnel`. **Leave this running** — the tunnel must stay alive for Skupper link access to work.

### Terminal 2 — SPIRE

```bash
cd deploy
./init-spire.sh
```

Deploys a SPIRE Server and Agent in each namespace, registers workload entries, and federates the two trust domains so SVIDs from east are trusted by west and vice versa.

### Terminal 3 — site-west

```bash
cd deploy
./init-west-client.sh
```

Installs Skupper in site-west, creates the west Skupper site (with link access enabled), issues a `west.token`, deploys the hello-client, and creates the Skupper Listener for `hello-server:8443`.

### Terminal 4 — site-east

```bash
cd deploy
./init-east-client.sh
```

Installs Skupper in site-east, creates the east Skupper site, redeems `west.token` to link the two sites, deploys the hello-server, and creates the Skupper Connector.

## Verifying the demo

Once both sites are linked and the pods are running, watch the client logs:

```bash
kubectl logs -n site-west deployment/hello-client -f
```

You should see output like:

```
INFO  [com.example.spiffe.client.HelloScheduler] Response: Hello, Alice! (from site-east via SPIFFE mTLS)
INFO  [com.example.spiffe.client.HelloScheduler] Response: Hello, Bob! (from site-east via SPIFFE mTLS)
INFO  [com.example.spiffe.client.HelloScheduler] Response: Hello, Grace! (from site-east via SPIFFE mTLS)
```

### Inspect SPIFFE identities

Check the SPIFFE ID embedded in the server's SVID:

```bash
kubectl exec -n site-east deployment/hello-server -c hello-server -- \
  openssl x509 -noout -text -in /opt/spiffe-certs/svid.pem | grep URI
```

Expected output:

```
URI:spiffe://east.demo.example.com/hello-server
```

Check the trust bundle held by the client (should contain both east and west CAs):

```bash
kubectl exec -n site-west deployment/hello-client -c hello-client -- \
  openssl x509 -noout -subject -in /opt/spiffe-certs/bundle.pem
```

Check registered workload entries on either SPIRE server:

```bash
kubectl exec -n site-east statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server entry show
```

## Teardown

```bash
cd deploy
./teardown.sh
```

## Configuration reference

### Application properties

Both apps read the following properties, which can be overridden via environment variables using standard Quarkus naming (`spire.socket.path` → `SPIRE_SOCKET_PATH`).

| Property | Default | Description |
|----------|---------|-------------|
| `spire.socket.path` | `unix:/run/spire/sockets/agent.sock` | Path to the SPIRE Agent Unix socket |
| `spiffe.cert.dir` | `/opt/spiffe-certs` | Directory where SPIFFE PEM files are written |

The pod specs in the setup scripts set `SPIRE_SOCKET_PATH` to `unix:/run/spire/sockets/agent.sock`, which is the in-pod path of the site-specific hostPath volume (`/run/spire/east` or `/run/spire/west` on the node).

### TLS properties (server)

| Property | Value |
|----------|-------|
| `quarkus.http.ssl-port` | `8443` |
| `quarkus.http.ssl.client-auth` | `required` |
| `quarkus.tls.spiffe.reload-period` | `60s` |

### TLS properties (client)

| Property | Value |
|----------|-------|
| `quarkus.rest-client.hello-server.url` | `https://hello-server:8443` |
| `quarkus.rest-client.hello-server.tls-configuration-name` | `spiffe` |
| `quarkus.rest-client.hello-server.hostname-verifier` | `SpiffeHostnameVerifier` |

`SpiffeHostnameVerifier` accepts any hostname. This is intentional: SPIFFE certificates carry identity in a URI SAN (`spiffe://...`), not a DNS name, so standard hostname verification does not apply. Trust is still fully enforced via the mutual TLS certificate chain.

## Project structure

```
spiffe/
├── server/                          # site-east Quarkus server
│   ├── src/main/java/com/example/spiffe/server/
│   │   ├── HelloResource.java       # GET /hello/{name}
│   │   └── SpiffeService.java       # SPIRE Workload API client + cert refresh
│   ├── src/main/resources/
│   │   └── application.properties
│   ├── pom.xml
│   └── Dockerfile
├── client/                          # site-west Quarkus client
│   ├── src/main/java/com/example/spiffe/client/
│   │   ├── HelloClient.java         # MicroProfile REST Client interface
│   │   ├── HelloScheduler.java      # Calls server every 1s with a random name
│   │   ├── SpiffeHostnameVerifier.java  # SPIFFE-aware TLS hostname handling
│   │   └── SpiffeService.java       # SPIRE Workload API client + cert refresh
│   ├── src/main/resources/
│   │   └── application.properties
│   ├── pom.xml
│   └── Dockerfile
└── deploy/
    ├── init-k8s.sh                  # Provision minikube + build images
    ├── init-image.sh                # Maven build + docker build inside minikube
    ├── init-spire.sh                # Deploy SPIRE + federation
    ├── init-west-client.sh          # Skupper west site + hello-client
    ├── init-east-client.sh          # Skupper east site + hello-server
    └── teardown.sh
```
