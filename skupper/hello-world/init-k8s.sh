#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

minikube-start

kubectl create namespace hello-world-east || true
kubectl create namespace hello-world-west || true

minikube tunnel
