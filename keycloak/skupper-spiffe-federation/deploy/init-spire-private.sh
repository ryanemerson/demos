#!/bin/bash
set -e

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
TMP="${SCRIPT_DIR}/.tmp"
PRIVATE_PLATFORM="${PRIVATE_PLATFORM:-minikube}"

if [ "${PRIVATE_PLATFORM}" = "openshift" ]; then
  SPIRE_SERVER_BIN="/spire-server"
else
  SPIRE_SERVER_BIN="/opt/spire/bin/spire-server"
fi

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
  # Install the Zero Trust Workload Identity Manager operator
  kubectl apply -k "${SCRIPT_DIR}/overlays/openshift/spire/operator/"

  echo "Waiting for operator CSV to succeed..."
  until kubectl get csv zero-trust-workload-identity-manager.v1.0.0 -n spire -o jsonpath='{.status.phase}' 2>/dev/null | grep -q "Succeeded"; do
    sleep 5
  done
  echo "Operator installed."

  kubectl apply -k "${SCRIPT_DIR}/overlays/openshift/spire/private"

  echo "Waiting for SPIRE operator operands to be ready..."
    # We can't wait for the condition=Ready here as it always returns False when `spec.federation.managedRoute=false`
  kubectl wait --for=condition=ServiceAvailable spireserver/cluster -n spire --timeout=300s
  kubectl wait --for=condition=Ready spireagent/cluster -n spire --timeout=120s
  kubectl wait --for=condition=Ready spiffecsidriver/cluster -n spire --timeout=120s
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
