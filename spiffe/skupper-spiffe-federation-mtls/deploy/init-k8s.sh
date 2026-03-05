#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

PROFILES=("public" "private")

# Use distinct service CIDRs to avoid conflicts between the two clusters
declare -A SERVICE_CIDRS
SERVICE_CIDRS[public]="10.96.0.0/12"
SERVICE_CIDRS[private]="10.112.0.0/12"

function startMinikube {
    local profile=$1
    minikube start -p "${profile}" --driver docker --memory=4096 --extra-config=kubelet.read-only-port=10255 --service-cluster-ip-range="${SERVICE_CIDRS[${profile}]}"
}

function provisionMinikube() {
    local profile=$1
    set +e
    status_output=$(minikube status -p "${profile}" 2>&1)
    status_exit_code=$?
    set -e

    if [ $status_exit_code -ne 0 ]; then
        echo "Minikube '${profile}' is not running or is not configured properly."
        echo "Details: $(echo "$status_output" | head -n 1)"
        startMinikube "${profile}"
    else
        if echo "$status_output" | grep -q "host: Running" && \
            echo "$status_output" | grep -q "kubelet: Running" && \
            echo "$status_output" | grep -q "apiserver: Running"; then
            echo "Minikube '${profile}' is running."
        else
            echo "Minikube '${profile}' is not running (or some essential components are stopped)."
            startMinikube "${profile}"
        fi
    fi
}

for profile in "${PROFILES[@]}"; do
    provisionMinikube "${profile}"
done

TMP_DIR="${SCRIPT_DIR}/.tmp"
LOG_DIR="${SCRIPT_DIR}/.logs"
mkdir -p "${TMP_DIR}" "${LOG_DIR}"

# Prompt for sudo password now while we still have an interactive terminal
sudo -v

# Kill stale tunnels from previous runs
PID_FILE="${TMP_DIR}/minikube-tunnel.pids"
if [ -f "${PID_FILE}" ]; then
    while read -r TUNNEL_PID; do
        if kill -0 "${TUNNEL_PID}" 2>/dev/null; then
            echo "Stopping stale minikube tunnel (PID: ${TUNNEL_PID})..."
            sudo kill "${TUNNEL_PID}" 2>/dev/null || true
            tail --pid="${TUNNEL_PID}" -f /dev/null 2>/dev/null || true
        fi
    done < "${PID_FILE}"
fi

# Start a tunnel for the public cluster so that its LoadBalancer services
# (e.g. skupper link-access) get an external IP. This adds a route on the
# host: 10.96.0.0/12 via <public-container-ip>.
> "${PID_FILE}"
echo "Starting minikube tunnel for 'public' cluster in the background..."
minikube tunnel -p public > "${LOG_DIR}/minikube-tunnel-public.log" 2>&1 &
TUNNEL_PID=$!
echo "Minikube tunnel 'public' PID: ${TUNNEL_PID}"
echo "${TUNNEL_PID}" >> "${PID_FILE}"

# Allow cross-cluster traffic from the private cluster to the public cluster's
# service CIDR. The private container's default route already sends unknown
# traffic to the host (Docker bridge gateway), and the minikube tunnel route
# on the host forwards it to the public container. However, Docker's
# DOCKER-ISOLATION iptables chains block traffic between different Docker
# bridges. These DOCKER-USER rules override that isolation for the public
# cluster's service CIDR and associated return traffic.
echo "Configuring iptables for cross-cluster routing..."
sudo iptables -C DOCKER-USER -d ${SERVICE_CIDRS[public]} -j ACCEPT 2>/dev/null \
    || sudo iptables -I DOCKER-USER -d ${SERVICE_CIDRS[public]} -j ACCEPT
sudo iptables -C DOCKER-USER -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null \
    || sudo iptables -I DOCKER-USER -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
echo "Cross-cluster routing configured (private -> public only)."
