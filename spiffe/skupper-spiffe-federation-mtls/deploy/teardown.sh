#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
PUBLIC_PLATFORM="${PUBLIC_PLATFORM:-minikube}"
PRIVATE_PLATFORM="${PRIVATE_PLATFORM:-minikube}"

echo "--- Tearing down public cluster ---"
export KUBECONFIG=$HOME/.kube/public

if [ "${PUBLIC_PLATFORM}" = "openshift" ]; then
  # Delete operator operands and cluster-scoped CRDs
  kubectl delete -k "${SCRIPT_DIR}/overlays/openshift/spire/public" --ignore-not-found
  kubectl delete clusterfederatedtrustdomain private-demo --ignore-not-found
  kubectl delete -k "${SCRIPT_DIR}/overlays/openshift/spire/operator/" --ignore-not-found
  kubectl delete -k "${SCRIPT_DIR}/overlays/openshift/client" --ignore-not-found
else
  kubectl delete -k "${SCRIPT_DIR}/spire/public" --ignore-not-found
  kubectl delete -k "${SCRIPT_DIR}/client" --ignore-not-found
  # Cluster-scoped RBAC created by manual SPIRE
  kubectl delete clusterrole spire-server-cluster-role spire-agent-cluster-role --ignore-not-found
  kubectl delete clusterrolebinding spire-server-cluster-role-binding spire-agent-cluster-role-binding --ignore-not-found
fi

kubectl delete namespace client --ignore-not-found
kubectl delete namespace spire --ignore-not-found
kubectl delete namespace skupper --ignore-not-found

echo "--- Tearing down private cluster ---"
export KUBECONFIG=$HOME/.kube/private

if [ "${PRIVATE_PLATFORM}" = "openshift" ]; then
  kubectl delete -k "${SCRIPT_DIR}/overlays/openshift/spire/private" --ignore-not-found
  kubectl delete clusterfederatedtrustdomain public-demo --ignore-not-found
  kubectl delete -k "${SCRIPT_DIR}/overlays/openshift/spire/operator/" --ignore-not-found
  kubectl delete -k "${SCRIPT_DIR}/overlays/openshift/server" --ignore-not-found
else
  kubectl delete -k "${SCRIPT_DIR}/spire/private" --ignore-not-found
  kubectl delete -k "${SCRIPT_DIR}/server" --ignore-not-found
  kubectl delete clusterrole spire-server-cluster-role spire-agent-cluster-role --ignore-not-found
  kubectl delete clusterrolebinding spire-server-cluster-role-binding spire-agent-cluster-role-binding --ignore-not-found
fi

kubectl delete namespace server --ignore-not-found
kubectl delete namespace spire --ignore-not-found
kubectl delete namespace skupper --ignore-not-found

# Clean up temporary files
rm -rf "${SCRIPT_DIR}/.tmp"

echo "--- Teardown complete ---"
