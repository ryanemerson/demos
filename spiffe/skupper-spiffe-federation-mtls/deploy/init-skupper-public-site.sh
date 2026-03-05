#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

export KUBECONFIG=$HOME/.kube/public
minikube -p public update-context
kubectl config set-context --current --namespace skupper

kubectl apply -f https://skupper.io/install.yaml
kubectl set env deployment/skupper-controller SKUPPER_LOG_LEVEL=debug
kubectl rollout status deployment/skupper-controller

skupper site create public --enable-link-access || true
skupper site status
mkdir -p ${SCRIPT_DIR}/.tmp
skupper token issue ${SCRIPT_DIR}/.tmp/public.token
skupper link status