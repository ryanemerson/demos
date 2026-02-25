# SPIFFE + Keycloak Zero Trust Demo

A demonstration of zero trust authentication using SPIFFE/SPIRE federation with Keycloak. The demo
deploys a client and server application on Kubernetes (Minikube), where all service-to-service
communication is secured with mTLS using SPIFFE X.509-SVIDs and access control is enforced via
Keycloak-issued Bearer tokens.

## Prerequisites

- [Minikube](https://minikube.sigs.k8s.io/docs/start/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [Docker](https://docs.docker.com/get-docker/)
- [OpenSSL](https://www.openssl.org/)
- Java 21+ and Maven (to build the Quarkus applications)

## Architecture

The demo consists of four components:

- **Client** (`hello-client`) — Quarkus application that obtains a JWT-SVID from SPIRE, exchanges
  it for a Keycloak access token, and calls the server with the token as a Bearer credential.
  Serves a web UI for testing different authorization scenarios.
- **Server** (`hello-server`) — Quarkus application that exposes REST endpoints protected by
  role-based access control. Validates Bearer tokens locally using verification keys fetched from
  Keycloak at startup.
- **Keycloak** — Identity provider configured with SPIFFE federation. Validates JWT-SVIDs by
  fetching JWKS from the SPIRE OIDC Discovery Provider and issues access tokens with role claims.
- **SPIRE** — SPIFFE runtime that issues X.509-SVIDs (for mTLS) and JWT-SVIDs (for token exchange)
  to workloads. Includes an OIDC Discovery Provider for Keycloak integration.

All communication between the client, server, and Keycloak uses **mTLS**. Communication between
Keycloak and SPIRE uses **TLS**.

## Deploy

Deploy everything with a single command:

```bash
./deploy/deploy-all.sh
```

This runs the following scripts in order:

1. `init-k8s.sh` — Starts Minikube if not already running
2. `init-image.sh` — Builds the Quarkus application container images
3. `init-spire.sh` — Deploys SPIRE server and agents, registers workload entries
4. `init-keycloak.sh` — Deploys Keycloak, configures the SPIFFE realm, identity provider, client,
   and roles
5. `init-server.sh` — Deploys the hello-server application
6. `init-client.sh` — Deploys the hello-client application

## Access the Web UI

The client serves a web frontend over HTTPS on port 8443. To access it from your local machine,
forward the port:

```bash
kubectl port-forward -n client deployment/hello-client 8443:8443
```

Then open [https://localhost:8443](https://localhost:8443) in your browser. You will need to accept
the browser's certificate warning, as the client uses a SPIFFE X.509-SVID which is not trusted by
your browser.

The UI provides buttons to test different authorization scenarios against the server, showing how
mTLS and Bearer token-based RBAC work together.

## Teardown

To remove all deployed resources:

```bash
./deploy/teardown.sh
```
