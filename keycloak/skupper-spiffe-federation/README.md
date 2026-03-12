# SPIFFE + Keycloak Federated Zero Trust Demo

Cross-cluster mutual TLS with Bearer token authorization between two Kubernetes clusters using
federated SPIFFE identities, [Keycloak](https://www.keycloak.org/) for OIDC token exchange, and
[Skupper](https://skupper.io) providing the network link.

## Overview

Two Kubernetes clusters (`public` and `private`) each run an independent SPIRE deployment with
separate trust domains. Skupper links the clusters so SPIRE servers can exchange trust bundles
(federation) and workloads can communicate across cluster boundaries.

- **Public cluster** — runs `hello-client` (Quarkus) and `Keycloak` with SPIFFE IDs in trust
  domain `spiffe://public.demo.example.com/...`
- **Private cluster** — runs `hello-server` (Quarkus) with SPIFFE ID
  `spiffe://private.demo.example.com/hello-server`

The client calls the server over mTLS across the Skupper link. Both sides present X.509 SVIDs
issued by their respective SPIRE servers and verify each other using federated trust bundles.
For authorized endpoints, the client obtains a JWT-SVID from its local SPIRE agent, exchanges
it with Keycloak for an access token, and passes it as a Bearer token to the server.

## Prerequisites

- [minikube](https://minikube.sigs.k8s.io/) with the Docker driver (or two OpenShift clusters)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [Skupper CLI](https://skupper.io/install/)
- Java 17+ and Maven (to build the Quarkus applications)
- Docker
- `sudo` access (for minikube tunnel and iptables rules)

## Cluster Setup

`deploy-all.sh` does **not** create Kubernetes clusters — you must provision them beforehand.

**Option A: minikube (default)**

Use the provided script to start two minikube clusters (`public` and `private`) with tunnels
and cross-cluster routing pre-configured:

```bash
./deploy/init-k8s.sh
```

**Option B: Bring your own clusters**

If you are targeting OpenShift or any other Kubernetes distribution, provision two clusters
yourself and ensure their kubeconfigs are available at `~/.kube/public` and `~/.kube/private`.

## Quick Start

Once your clusters are ready, deploy everything:

```bash
./deploy/deploy-all.sh
```

This runs the following steps in order:

| Script | Purpose |
|---|---|
| `init-images.sh` | Build the Quarkus client and server Docker images (pushed to quay.io when targeting OpenShift) |
| `init-spire-public.sh` | Deploy SPIRE server + agent on the public cluster (`public.demo.example.com` trust domain) |
| `init-spire-private.sh` | Deploy SPIRE server + agent on the private cluster (`private.demo.example.com` trust domain) |
| `init-spire-skupper-federation.sh` | Install Skupper, link clusters, exchange SPIRE trust bundles, register federated workload entries |
| `init-keycloak.sh` | Deploy Keycloak on the public cluster with SPIFFE mTLS and Skupper connectivity |
| `init-workloads.sh` | Deploy `hello-client` and `hello-server`, create Skupper sites and listeners for workload traffic |

### Platform Variables

By default all scripts target minikube. Set the following environment variables to target
different platforms for each cluster:

| Variable | Default | Description |
|---|---|---|
| `PUBLIC_PLATFORM` | `minikube` | Platform for the public cluster (hello-client + Keycloak + SPIRE public) |
| `PRIVATE_PLATFORM` | `minikube` | Platform for the private cluster (hello-server + SPIRE private) |

For example, to deploy with the public cluster on OpenShift and the private cluster on minikube:

```bash
PUBLIC_PLATFORM=openshift ./deploy/deploy-all.sh
```

Or to deploy both clusters on OpenShift:

```bash
PUBLIC_PLATFORM=openshift PRIVATE_PLATFORM=openshift ./deploy/deploy-all.sh
```

When a platform is set to `openshift`:
- SPIRE is deployed with SecurityContextConstraints (SCCs) for the agent and CSI driver
- The SPIFFE CSI driver provides the agent socket to workloads (replacing hostPath volumes)
- Workload images are pushed to `quay.io/remerson/hello-*` and `imagePullPolicy` is set to `Always`

## Testing

Once deployed, the client exposes an HTTPS endpoint. Forward the port and make a request:

```bash
export KUBECONFIG=$HOME/.kube/public
kubectl port-forward -n client deploy/hello-client 8443:8443
```

Then in another terminal:

```bash
curl -k https://localhost:8443/hello
```

The client calls the server on the private cluster via Skupper using mTLS with federated SPIFFE
identities, and returns the server's response.

The client also serves a web UI at `https://localhost:8443/` with architecture diagrams and a
button to trigger the cross-cluster call.

## Architecture

```
┌──────────────────────────────┐             ┌──────────────────────────────┐
│       PUBLIC CLUSTER         │             │       PRIVATE CLUSTER        │
│  public.demo.example.com    │             │  private.demo.example.com    │
│                              │             │                              │
│  ┌────────────────────────┐  │    mTLS     │  ┌────────────────────────┐  │
│  │ hello-client           │──┼─────────────┼──│ hello-server           │  │
│  │  + spiffe-helper       │  │             │  │  + spiffe-helper       │  │
│  └────────────────────────┘  │             │  └────────────┬───────────┘  │
│                              │  Skupper    │               │              │
│  ┌────────────────────────┐  │   Link      │  ┌────────────┴───────────┐  │
│  │ Keycloak               │  │             │  │ SPIRE Agent            │  │
│  │  + spiffe-helper       │  │             │  └────────────┬───────────┘  │
│  └────────────────────────┘  │             │               │              │
│                              │             │  ┌────────────┴───────────┐  │
│  ┌────────────────────────┐  │             │  │ SPIRE Server           │  │
│  │ SPIRE Agent            │  │             │  └────────────────────────┘  │
│  └────────────┬───────────┘  │             │                              │
│               │              │             └──────────────────────────────┘
│  ┌────────────┴───────────┐  │   bundle                    ▲
│  │ SPIRE Server           │──┼─────────────────────────────┘
│  └────────────────────────┘  │  exchange
│                              │
└──────────────────────────────┘
```

All cross-cluster traffic flows through the Skupper link:
1. **SPIRE federation** — SPIRE servers exchange trust bundles (port 8443) so each cluster
   can verify identities from the other trust domain
2. **Workload traffic** — `hello-client` requests to `hello-server` are routed across
   clusters using mTLS with federated SPIFFE identities
3. **Authorization** — the client obtains a JWT-SVID, exchanges it with Keycloak for an
   access token, then passes the token to the server as a Bearer header

## Teardown

Remove all deployed Kubernetes resources:

```bash
./deploy/teardown.sh
```

Teardown minikube clusters:

```bash
./deploy/teardown-k8s.sh
```
