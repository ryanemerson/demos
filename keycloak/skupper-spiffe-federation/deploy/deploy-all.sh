#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

${SCRIPT_DIR}/init-images.sh
${SCRIPT_DIR}/init-skupper.sh
${SCRIPT_DIR}/init-spire-public.sh
${SCRIPT_DIR}/init-spire-private.sh
${SCRIPT_DIR}/init-spire-skupper-federation.sh
${SCRIPT_DIR}/init-keycloak.sh
${SCRIPT_DIR}/init-workloads.sh
