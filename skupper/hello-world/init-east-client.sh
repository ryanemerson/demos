#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

function terminal-title() {
    printf "\033]2;$1\007"
}

export KUBECONFIG=$HOME/.kube/config-hello-world-east
terminal-title East
minikube update-context
kubectl config set-context --current --namespace hello-world-east
kubectl apply -f https://skupper.io/install.yaml
kubectl rollout status deployment/skupper-controller -n skupper

skupper site create east
skupper site status
skupper token redeem west.token
skupper link status

kubectl create deployment backend --image quay.io/skupper/hello-world-backend --replicas 3
kubectl rollout status deployment/backend
skupper connector create backend 8080