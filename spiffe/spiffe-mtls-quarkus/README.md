# SPIFFE/SPIRE mTLS with Quarkus

A demonstration of [SPIFFE](https://spiffe.io/)/[SPIRE](https://spiffe.io/docs/latest/spire-about/spire-concepts/)-based mutual TLS (mTLS) between two Quarkus microservices running in Kubernetes.

## Overview

This demo shows how to establish cryptographically verified service-to-service communication using SPIFFE workload identities. Each service obtains a short-lived X.509 SVID (SPIFFE Verifiable Identity Document) from SPIRE and uses it to authenticate itself to other services—without managing certificates manually.

```
┌────────────────────────────────────────────────────────────────┐
│  Kubernetes (Minikube)                                         │
│                                                                │
│  ┌──────────────┐   mTLS (port 8443)   ┌──────────────────┐  │
│  │ hello-client │ ──────────────────── │  hello-server    │  │
│  │              │                      │  GET /hello/{name}│  │
│  └──────┬───────┘                      └────────┬─────────┘  │
│         │ X509Source                            │ SpiffeService│
│         │                                       │              │
│  ┌──────▼───────────────────────────────────────▼──────────┐  │
│  │                   SPIRE Agent (DaemonSet)                │  │
│  │              /run/spire/sockets/agent.sock               │  │
│  └─────────────────────────┬────────────────────────────────┘  │
│                            │                                   │
│                   ┌────────▼────────┐                          │
│                   │  SPIRE Server   │                          │
│                   │ (StatefulSet)   │                          │
│                   └─────────────────┘                          │
└────────────────────────────────────────────────────────────────┘
```

## How It Works

### Workload Identities

SPIRE issues SPIFFE IDs (URIs of the form `spiffe://<trust-domain>/<path>`) to each workload:

| Service       | SPIFFE ID                                          |
|---------------|----------------------------------------------------|
| hello-server  | `spiffe://demo.example.com/hello-server`           |
| hello-client  | `spiffe://demo.example.com/hello-client`           |

### Certificate Flow

1. SPIRE Agent runs as a DaemonSet, exposing a Unix socket on each node.
2. On startup, both services connect to the SPIRE Agent to fetch their X.509 SVID.
3. The **server** writes the certificate, private key, and trust bundle to disk; Quarkus reloads them automatically every 5 seconds.
4. The **client** holds an `X509Source` that SPIRE updates in-place when certificates rotate.
5. Every 60 seconds the client makes an authenticated HTTPS request to the server, presenting its client certificate. Both sides verify each other's identity.
6. Certificates rotate automatically—no restarts required.

### TLS Modes

The client supports three modes (configured via `SPIFFE_TLS` environment variable):

| Mode     | Description                                              |
|----------|----------------------------------------------------------|
| `MTLS`   | Full mutual TLS with SPIFFE ID validation (default)      |
| `TLS`    | Server certificate validation only, no client cert       |
| `LEGACY` | Basic SSL without SPIFFE-aware validation (for testing)  |

## Repository Structure

```
spiffe-mtls-quarkus/
├── client/                   # Quarkus hello-client application
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/.../client/
│       │   ├── HelloClient.java        # REST client interface
│       │   └── HelloScheduler.java     # Scheduled SPIFFE mTLS calls
│       └── resources/application.properties
│
├── server/                   # Quarkus hello-server application
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/.../server/
│       │   ├── HelloResource.java      # GET /hello/{name} endpoint
│       │   └── SpiffeService.java      # SVID fetch and cert rotation
│       └── resources/application.properties
│
└── deploy/                   # Kubernetes deployment scripts
    ├── init-k8s.sh           # Start Minikube and build images
    ├── init-image.sh         # Build Docker images inside Minikube
    ├── init-spire.sh         # Deploy SPIRE server, agent, and entries
    ├── init-server.sh        # Deploy hello-server
    ├── init-client.sh        # Deploy hello-client
    └── teardown.sh           # Delete all demo resources
```

## Prerequisites

- [Minikube](https://minikube.sigs.k8s.io/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [Maven](https://maven.apache.org/) 3.9+ with Java 21
- Docker

## Running the Demo

### 1. Start Minikube and build images

```bash
./deploy/init-k8s.sh
```

This starts Minikube (4 GB RAM, Docker driver), enables the kubelet read-only port required for SPIRE node attestation, and builds the Docker images directly into Minikube's Docker daemon.

### 2. Deploy SPIRE

```bash
./deploy/init-spire.sh
```

Creates the `spire` namespace, deploys the SPIRE Server (StatefulSet) and SPIRE Agent (DaemonSet), waits for them to be ready, then registers workload entries for both the server and client.

### 3. Deploy the server

```bash
./deploy/init-server.sh
```

Creates the `server` namespace and deploys hello-server. Init containers fetch the initial SVID from SPIRE before the main container starts.

### 4. Deploy the client

```bash
./deploy/init-client.sh
```

Creates the `client` namespace and deploys hello-client. The client connects to SPIRE via the Workload API and begins making authenticated requests to the server every 60 seconds.

### 5. Verify

Check client logs to see successful mTLS calls:

```bash
kubectl logs -n client -l app=hello-client -f
```

You should see output similar to:

```
Response from server: Hello, Alice! (via SPIFFE mTLS)
Response from server: Hello, Bob! (via SPIFFE mTLS)
```

### Teardown

```bash
./deploy/teardown.sh
```

Deletes the `client`, `server`, and `spire` namespaces.

## Key Components

### hello-server

- Exposes `GET /hello/{name}` over HTTPS on port 8443.
- `SpiffeService` connects to the SPIRE Agent on startup, fetches the X.509 SVID, and writes PEM files to `/opt/spiffe-certs/`.
- Quarkus TLS registry watches the cert directory and reloads certificates every 5 seconds, enabling zero-downtime rotation.

### hello-client

- `HelloScheduler` maintains an `X509Source` connected to the SPIRE Workload API.
- Every 60 seconds it constructs a Java `HttpClient` with an `SSLContext` backed by the SPIFFE provider, then calls the server.
- Validates the server's SPIFFE ID against the configured `spiffe.ids` property (`*` by default, meaning any ID under the trust domain is accepted).
- The `X509Source` is updated by SPIRE automatically when certificates rotate.

## Configuration

### Client (`client/src/main/resources/application.properties`)

| Property            | Default                                                    | Description                        |
|---------------------|------------------------------------------------------------|------------------------------------|
| `spiffe.ids`        | `*`                                                        | Accepted server SPIFFE IDs         |
| `spiffe.tls`        | `mtls`                                                     | TLS mode: `mtls`, `tls`, `legacy`  |
| `hello.server.url`  | `https://hello-server.server.svc.cluster.local:8443`       | Server base URL                    |

### Server (`server/src/main/resources/application.properties`)

| Property                      | Default               | Description                       |
|-------------------------------|-----------------------|-----------------------------------|
| `spiffe.cert.dir`             | `/opt/spiffe-certs`   | Directory for PEM certificate files |
| `quarkus.tls.spiffe.reload-period` | `5s`            | Certificate hot-reload interval   |

## Technology Stack

| Component      | Technology                                                   |
|----------------|--------------------------------------------------------------|
| Microservices  | [Quarkus](https://quarkus.io/) 3.31.4, Java 21              |
| Identity       | [SPIFFE/SPIRE](https://spiffe.io/)                           |
| SPIFFE SDK     | [java-spiffe](https://github.com/spiffe/java-spiffe) 0.8.15  |
| Container      | UBI 9 OpenJDK 21                                             |
| Orchestration  | Kubernetes (Minikube)                                        |

## Further Reading

- [SPIFFE Specification](https://github.com/spiffe/spiffe/blob/main/standards/SPIFFE.md)
- [SPIRE Documentation](https://spiffe.io/docs/latest/)
- [java-spiffe Library](https://github.com/spiffe/java-spiffe)
- [Quarkus TLS Registry](https://quarkus.io/guides/tls-registry-reference)
