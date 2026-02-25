#!/bin/bash
set -e

SPIRE_VERSION="1.9.4"
export SPIRE_SERVER_IMAGE="ghcr.io/spiffe/spire-server:${SPIRE_VERSION}"
export SPIRE_OIDC_DISCOVERY_IMAGE="ghcr.io/spiffe/oidc-discovery-provider:${SPIRE_VERSION}"
export SPIRE_AGENT_IMAGE="ghcr.io/spiffe/spire-agent:${SPIRE_VERSION}"

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

echo "--- Deploying SPIRE ---"

# Create unsigned CA
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,DNS:*.localdomain,IP:127.0.0.1" \
  -keyout /tmp/localhost-unsigned.key \
  -out /tmp/localhost-unsigned.pem

kubectl create namespace spire || true
kubectl -n spire delete secret oidc-discovery-certs --ignore-not-found
kubectl -n spire create secret tls oidc-discovery-certs \
  --cert=/tmp/localhost-unsigned.pem \
  --key=/tmp/localhost-unsigned.key

envsubst < "${SCRIPT_DIR}/spire/agent.yml" | kubectl apply -f -
envsubst < "${SCRIPT_DIR}/spire/server.yml" | kubectl apply -f -

# ---------------------------------------------------------------------------
# Wait for SPIRE server to be ready before starting agents
# ---------------------------------------------------------------------------
echo "Waiting for SPIRE server to be ready..."
kubectl rollout status statefulset/spire-server -n spire

echo "Waiting for SPIRE agents to be ready..."
kubectl rollout status daemonset/spire-agent -n spire

# ---------------------------------------------------------------------------
# Register workload entries
# ---------------------------------------------------------------------------
echo "--- Registering workload entries ---"

kubectl exec -n spire spire-server-0 -- \
    /opt/spire/bin/spire-server entry create \
    -spiffeID spiffe://demo.example.com/ns/spire/sa/spire-agent \
    -selector k8s_psat:cluster:minikube \
    -selector k8s_psat:agent_ns:spire \
    -selector k8s_psat:agent_sa:spire-agent \
    -node

# keycloak workload.
kubectl exec -n spire statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server entry create \
  -spiffeID spiffe://demo.example.com/keycloak \
  -parentID spiffe://demo.example.com/ns/spire/sa/spire-agent \
  -selector k8s:ns:keycloak \
  -selector k8s:sa:default

# hello-server workload.
kubectl exec -n spire statefulset/spire-server -c spire-server -- \
  /opt/spire/bin/spire-server entry create \
  -spiffeID spiffe://demo.example.com/hello-client \
  -parentID spiffe://demo.example.com/ns/spire/sa/spire-agent \
  -selector k8s:ns:client \
  -selector k8s:sa:hello-client

echo ""
echo "SPIRE deployed successfully."
echo ""
echo "Trust domains:"
echo "  demo.example.com/keycloak"
echo "  demo.example.com/hello-client"
echo ""
echo "SPIFFE IDs:"
echo "  Server: spiffe://demo.example.com/keycloak"
echo "  Client: spiffe://demo.example.com/hello-client"
echo ""
