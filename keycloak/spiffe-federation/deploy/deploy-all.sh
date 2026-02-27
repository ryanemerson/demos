#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

${SCRIPT_DIR}/init-k8s.sh
${SCRIPT_DIR}/init-image.sh
${SCRIPT_DIR}/init-spire.sh
${SCRIPT_DIR}/init-keycloak.sh
${SCRIPT_DIR}/init-server.sh
${SCRIPT_DIR}/init-client.sh
