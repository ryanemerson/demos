#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

set -e

echo "--- Installing Skupper on Public Cluster ---"
export KUBECONFIG=$HOME/.kube/public
kubectl apply -k "${SCRIPT_DIR}/skupper"
kubectl rollout status deployment/skupper-controller -n skupper

echo "--- Installing Skupper on Private Cluster ---"
export KUBECONFIG=$HOME/.kube/private
kubectl apply -k "${SCRIPT_DIR}/skupper"
kubectl rollout status deployment/skupper-controller -n skupper

echo "--- Skupper Installation Complete ---"
