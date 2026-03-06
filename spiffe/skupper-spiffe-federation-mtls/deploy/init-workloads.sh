#!/bin/bash

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

export KUBECONFIG=$HOME/.kube/public
kubectl apply -f "${SCRIPT_DIR}/client/client.yml"
kubectl rollout status deployment/hello-client -n client
skupper site create public --enable-link-access -n client
skupper token issue ${SCRIPT_DIR}/.tmp/skupper-client.token -n client

export KUBECONFIG=$HOME/.kube/private
kubectl apply -f "${SCRIPT_DIR}/server/server.yml"
kubectl rollout status deployment/hello-server -n server
skupper site create private -n server
skupper token redeem ${SCRIPT_DIR}/.tmp/skupper-client.token -n server
skupper connector create hello-server 8443 --workload deployment/hello-server -n server

export KUBECONFIG=$HOME/.kube/public
skupper listener create hello-server 8443 -n client

echo "Workload setup complete"
