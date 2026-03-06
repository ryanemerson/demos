#!/bin/bash

SPIRE_VERSION="1.9.4"
export SPIRE_SERVER_IMAGE="ghcr.io/spiffe/spire-server:${SPIRE_VERSION}"
export SPIRE_OIDC_DISCOVERY_IMAGE="ghcr.io/spiffe/oidc-discovery-provider:${SPIRE_VERSION}"
export SPIRE_AGENT_IMAGE="ghcr.io/spiffe/spire-agent:${SPIRE_VERSION}"

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
TMP="${SCRIPT_DIR}"/.tmp

echo "--- Deploying SPIRE ---"

export KUBECONFIG=$HOME/.kube/private

# Create unsigned CA
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes -subj "/CN=spire-private" -addext "subjectAltName=DNS:spire-private.spire.svc.cluster.local" \
  -keyout ${TMP}/private_spiffe.key \
  -out ${TMP}/private_spiffe.pem

kubectl create namespace spire || true
kubectl -n spire delete secret oidc-discovery-certs --ignore-not-found
kubectl -n spire create secret tls oidc-discovery-certs \
  --cert=${TMP}/private_spiffe.pem  \
  --key=${TMP}/private_spiffe.key

envsubst < "${SCRIPT_DIR}/spire/private-agent.yml" | kubectl apply -f -
envsubst < "${SCRIPT_DIR}/spire/private-server.yml" | kubectl apply -f -

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
    -spiffeID spiffe://private.demo.example.com/ns/spire/sa/spire-agent \
    -selector k8s_psat:cluster:minikube \
    -selector k8s_psat:agent_ns:spire \
    -selector k8s_psat:agent_sa:spire-agent \
    -node

echo ""
echo "SPIRE deployed successfully."
echo ""
echo "Trust domains:"
echo "  private.demo.example.com"
echo ""
