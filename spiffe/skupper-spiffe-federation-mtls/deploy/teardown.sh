#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

export KUBECONFIG=$HOME/.kube/public
kubectl delete namespace client --ignore-not-found
kubectl delete namespace spire --ignore-not-found
kubectl delete namespace skupper --ignore-not-found

export KUBECONFIG=$HOME/.kube/private
kubectl delete namespace server --ignore-not-found
kubectl delete namespace spire --ignore-not-found
kubectl delete namespace skupper --ignore-not-found
