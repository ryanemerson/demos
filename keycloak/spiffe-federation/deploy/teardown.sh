#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

kubectl delete namespace client --ignore-not-found
kubectl delete namespace server --ignore-not-found
kubectl delete namespace keycloak --ignore-not-found
kubectl delete namespace spire --ignore-not-found
