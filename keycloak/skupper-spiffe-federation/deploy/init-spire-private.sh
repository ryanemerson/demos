#!/bin/bash
set -e

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
TMP="${SCRIPT_DIR}/.tmp"
PRIVATE_PLATFORM="${PRIVATE_PLATFORM:-minikube}"

SPIRE_SERVER_BIN="/opt/spire/bin/spire-server"

echo "--- Deploying SPIRE (private) ---"

# Create unsigned CA for federation bundle endpoint
mkdir -p ${TMP}
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes -subj "/CN=spire-server" -addext "subjectAltName=DNS:spire-private.spire.svc.cluster.local" \
  -keyout ${TMP}/spire-private.key \
  -out ${TMP}/spire-private.pem

export KUBECONFIG=$HOME/.kube/private

# Create the spire namespace and federation cert secret before deploying SPIRE
kubectl create namespace spire --dry-run=client -o yaml | kubectl apply -f -
kubectl delete secret spire-federation-cert -n spire --ignore-not-found
kubectl create secret tls spire-federation-cert -n spire \
  --cert=${TMP}/spire-private.pem \
  --key=${TMP}/spire-private.key

if [ "${PRIVATE_PLATFORM}" = "openshift" ]; then
  kubectl apply -k "${SCRIPT_DIR}/overlays/openshift/spire/private"

  echo "Waiting for SPIRE server to be ready..."
  kubectl rollout status statefulset/spire-server -n spire --timeout=300s

  echo "Waiting for SPIRE agents to be ready..."
  kubectl rollout status daemonset/spire-agent -n spire --timeout=120s

  echo "--- Registering workload entries ---"
  kubectl exec -n spire spire-server-0 -c spire-server -- \
      ${SPIRE_SERVER_BIN} entry create \
      -spiffeID spiffe://private.demo.example.com/ns/spire/sa/spire-agent \
      -selector k8s_psat:cluster:private \
      -selector k8s_psat:agent_ns:spire \
      -selector k8s_psat:agent_sa:spire-agent \
      -node
else
  kubectl apply -k "${SCRIPT_DIR}/spire/private"

  echo "Waiting for SPIRE server to be ready..."
  kubectl rollout status statefulset/spire-server -n spire

  echo "Waiting for SPIRE agents to be ready..."
  kubectl rollout status daemonset/spire-agent -n spire

  echo "--- Registering workload entries ---"
  kubectl exec -n spire spire-server-0 -- \
      ${SPIRE_SERVER_BIN} entry create \
      -spiffeID spiffe://private.demo.example.com/ns/spire/sa/spire-agent \
      -selector k8s_psat:cluster:minikube \
      -selector k8s_psat:agent_ns:spire \
      -selector k8s_psat:agent_sa:spire-agent \
      -node
fi

echo ""
echo "SPIRE deployed successfully."
echo ""
echo "Trust domains:"
echo "  private.demo.example.com"
echo ""
