#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
function startMinikube {
    minikube start --driver docker --memory=4096
}

function provisionMinikube() {
    set +e
    status_output=$(minikube status 2>&1)
    status_exit_code=$?
    set -e

    if [ $status_exit_code -ne 0 ]; then
        echo "Minikube is not running or is not configured properly."
        echo "Details: $(echo "$status_output" | head -n 1)"
        startMinikube
    else
        if echo "$status_output" | grep -q "host: Running" && \
            echo "$status_output" | grep -q "kubelet: Running" && \
            echo "$status_output" | grep -q "apiserver: Running"; then
            echo "Minikube is running."
        else
            echo "Minikube is not running (or some essential components are stopped)."
            startMinikube
        fi
    fi
}

provisionMinikube

kubectl create namespace jgroups-east || true
kubectl create namespace jgroups-west || true

${SCRIPT_DIR}/init-image.sh

minikube tunnel
