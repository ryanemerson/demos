#!/bin/bash

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
PUBLIC_PLATFORM="${PUBLIC_PLATFORM:-minikube}"
PRIVATE_PLATFORM="${PRIVATE_PLATFORM:-minikube}"

export KUBECONFIG=$HOME/.kube/public
if [ "${PUBLIC_PLATFORM}" = "openshift" ]; then
  kubectl apply -k "${SCRIPT_DIR}/overlays/openshift/client"
else
  kubectl apply -k "${SCRIPT_DIR}/client"
fi
kubectl rollout status deployment/hello-client -n client
skupper site create public --enable-link-access -n client
skupper token issue ${SCRIPT_DIR}/.tmp/skupper-client.token -n client

export KUBECONFIG=$HOME/.kube/private
if [ "${PRIVATE_PLATFORM}" = "openshift" ]; then
  kubectl apply -k "${SCRIPT_DIR}/overlays/openshift/server"
else
  kubectl apply -k "${SCRIPT_DIR}/server"
fi
kubectl rollout status deployment/hello-server -n server
skupper site create private -n server
skupper token redeem ${SCRIPT_DIR}/.tmp/skupper-client.token -n server
skupper connector create hello-server 8443 --workload deployment/hello-server -n server

export KUBECONFIG=$HOME/.kube/public
skupper listener create hello-server 8443 -n client

echo "Workload setup complete"
