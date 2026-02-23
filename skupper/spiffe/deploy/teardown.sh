#!/bin/bash
# Removes all demo resources from both namespaces.
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

echo "Tearing down site-east..."
export KUBECONFIG=$HOME/.kube/config-spiffe-east
minikube update-context
kubectl config set-context --current --namespace site-east

skupper site delete || true
kubectl delete deployment hello-server -n site-east --ignore-not-found
kubectl delete serviceaccount hello-server -n site-east --ignore-not-found

echo "Tearing down site-west..."
export KUBECONFIG=$HOME/.kube/config-spiffe-west
minikube update-context
kubectl config set-context --current --namespace site-west

skupper site delete || true
kubectl delete deployment hello-client -n site-west --ignore-not-found
kubectl delete serviceaccount hello-client -n site-west --ignore-not-found

echo "Removing SPIRE from both namespaces..."
export KUBECONFIG=$HOME/.kube/config-spiffe-east
minikube update-context
kubectl delete statefulset spire-server -n site-east --ignore-not-found
kubectl delete daemonset spire-agent -n site-east --ignore-not-found
kubectl delete configmap spire-server-config spire-agent-config -n site-east --ignore-not-found
kubectl delete serviceaccount spire-server spire-agent -n site-east --ignore-not-found

export KUBECONFIG=$HOME/.kube/config-spiffe-west
minikube update-context
kubectl delete statefulset spire-server -n site-west --ignore-not-found
kubectl delete daemonset spire-agent -n site-west --ignore-not-found
kubectl delete configmap spire-server-config spire-agent-config -n site-west --ignore-not-found
kubectl delete serviceaccount spire-server spire-agent -n site-west --ignore-not-found

kubectl delete clusterrole spire-server-cluster-role spire-agent-cluster-role --ignore-not-found
kubectl delete clusterrolebinding \
  spire-server-east-cluster-role-binding \
  spire-server-west-cluster-role-binding \
  spire-agent-east-cluster-role-binding \
  spire-agent-west-cluster-role-binding \
  --ignore-not-found

rm -f "${SCRIPT_DIR}/west.token"

echo "Teardown complete."
