#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

export KUBECONFIG=$HOME/.kube/private
minikube -p private update-context
kubectl config set-context --current --namespace skupper

kubectl apply -f https://skupper.io/install.yaml
kubectl set env deployment/skupper-controller SKUPPER_LOG_LEVEL=debug
kubectl rollout status deployment/skupper-controller
