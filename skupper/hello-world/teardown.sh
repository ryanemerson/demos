#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

export KUBECONFIG=$HOME/.kube/config-hello-world-west
skupper site delete --all
kubectl delete deployment/frontend

export KUBECONFIG=$HOME/.kube/config-hello-world-east
skupper site delete --all
kubectl delete deployment/backend
