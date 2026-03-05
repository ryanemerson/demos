#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

PID_FILE="${SCRIPT_DIR}/.tmp/minikube-tunnel.pids"
if [ -f "${PID_FILE}" ]; then
    while read -r TUNNEL_PID; do
        if kill -0 "${TUNNEL_PID}" 2>/dev/null; then
            echo "Stopping minikube tunnel (PID: ${TUNNEL_PID})..."
            sudo kill "${TUNNEL_PID}"
        fi
    done < "${PID_FILE}"
fi

echo "Removing cross-cluster iptables rules..."
sudo iptables -D DOCKER-USER -d 10.96.0.0/12 -j ACCEPT 2>/dev/null || true
sudo iptables -D DOCKER-USER -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true

rm -rf "${SCRIPT_DIR}/.tmp" "${SCRIPT_DIR}/.logs"

echo "Deleting minikube cluster 'public'..."
minikube delete -p public

echo "Deleting minikube cluster 'private'..."
minikube delete -p private

echo "Teardown complete."
