#!/bin/bash
#set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

kubectl apply -f "${SCRIPT_DIR}/client/client.yml"

kubectl rollout status deployment/hello-client -n client
echo "Client setup complete."
