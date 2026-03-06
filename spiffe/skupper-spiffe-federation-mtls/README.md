# SPIFFE + Skupper Federated mTLS Demo

Cross-cluster mutual TLS between two Kubernetes clusters using federated SPIFFE identities,
with [Skupper](https://skupper.io) providing the network link.

## Overview

Two minikube clusters (`public` and `private`) each run an independent SPIRE deployment with
separate trust domains. Skupper links the clusters so SPIRE servers can exchange trust bundles
(federation) and workloads can communicate across cluster boundaries.

- **Public cluster** — runs `hello-client` (Quarkus) in the `client` namespace with SPIFFE ID
  `spiffe://public.demo.example.com/hello-client`
- **Private cluster** — runs `hello-server` (Quarkus) in the `server` namespace with SPIFFE ID
  `spiffe://private.demo.example.com/hello-server`

The client calls the server over mTLS. Both sides present X.509 SVIDs issued by their
respective SPIRE servers and verify each other using federated trust bundles.

## Prerequisites

- [minikube](https://minikube.sigs.k8s.io/) with the Docker driver
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [Skupper CLI](https://skupper.io/install/)
- Java 17+ and Maven (to build the Quarkus applications)
- Docker
- `sudo` access (for minikube tunnel and iptables rules)

## Quick Start

Deploy everything with a single script:

```bash
./deploy/deploy-all.sh
```

This runs the following steps in order:

| Script | Purpose |
|---|---|
| `init-k8s.sh` | Start two minikube clusters (`public`, `private`), configure tunnels and cross-cluster routing |
| `init-images.sh` | Build the Quarkus client and server Docker images inside each minikube cluster |
| `init-spire-public.sh` | Deploy SPIRE server + agent on the public cluster (`public.demo.example.com` trust domain) |
| `init-spire-private.sh` | Deploy SPIRE server + agent on the private cluster (`private.demo.example.com` trust domain) |
| `init-spire-skupper-federation.sh` | Install Skupper, link clusters, exchange SPIRE trust bundles, register federated workload entries |
| `init-workloads.sh` | Deploy `hello-client` and `hello-server`, create Skupper sites and listeners for workload traffic |

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
┌─────────────────────────────┐         ┌─────────────────────────────┐
│      PUBLIC CLUSTER         │         │      PRIVATE CLUSTER        │
│  public.demo.example.com   │         │  private.demo.example.com   │
│                             │         │                             │
│  ┌───────────────────────┐  │  mTLS   │  ┌───────────────────────┐  │
│  │ hello-client          │──┼─────────┼──│ hello-server          │  │
│  │  + spiffe-helper      │  │         │  │  + spiffe-helper      │  │
│  └───────────┬───────────┘  │         │  └───────────┬───────────┘  │
│              │              │         │              │              │
│  ┌───────────┴───────────┐  │         │  ┌───────────┴───────────┐  │
│  │ SPIRE Agent           │  │ Skupper │  │ SPIRE Agent           │  │
│  └───────────┬───────────┘  │  Link   │  └───────────┬───────────┘  │
│              │              │         │              │              │
│  ┌───────────┴───────────┐  │         │  ┌───────────┴───────────┐  │
│  │ SPIRE Server          │──┼─────────┼──│ SPIRE Server          │  │
│  └───────────────────────┘  │ bundle  │  └───────────────────────┘  │
│                             │exchange │                             │
└─────────────────────────────┘         └─────────────────────────────┘
```

All cross-cluster traffic flows through the Skupper link:
1. **SPIRE federation** — SPIRE servers exchange trust bundles (port 8443) so each cluster
   can verify identities from the other trust domain
2. **Workload traffic** — `hello-client` requests to `hello-server` are routed across
   clusters using mTLS with federated SPIFFE identities

## Teardown

Remove all Kubernetes resources (preserves minikube clusters):

```bash
./deploy/teardown.sh
```

Delete everything including minikube clusters:

```bash
./deploy/teardown-k8s.sh
```
