#!/bin/bash
#set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

kubectl apply -f "${SCRIPT_DIR}/server/server.yml"
kubectl rollout status deployment/hello-server -n server
echo "Server setup complete"
echo "Verify mTLS is working:"
echo "  kubectl logs -n client deployment/hello-client -f"
