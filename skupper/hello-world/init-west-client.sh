#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

function terminal-title() {
    printf "\033]2;$1\007"
}

export KUBECONFIG=$HOME/.kube/config-hello-world-west
terminal-title West
minikube update-context
kubectl config set-context --current --namespace hello-world-west
kubectl apply -f https://skupper.io/install.yaml
kubectl rollout status deployment/skupper-controller -n skupper

skupper site create west --enable-link-access
skupper site status
skupper token issue west.token
skupper link status

kubectl create deployment frontend --image quay.io/skupper/hello-world-frontend
kubectl rollout status deployment/frontend
skupper listener create backend 8080
kubectl get service/backend
kubectl port-forward deployment/frontend 8080:8080
