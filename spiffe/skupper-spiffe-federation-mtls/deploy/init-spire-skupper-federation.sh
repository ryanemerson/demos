#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
TMP="${SCRIPT_DIR}"/.tmp

set -e
echo "--- Installing Skupper on Public Cluster ---"
# Install Skupper controller in the spire namespace on both clusters
export KUBECONFIG=$HOME/.kube/public
kubectl apply -f https://skupper.io/install.yaml
kubectl rollout status deployment/skupper-controller

echo "--- Installing Skupper on Private Cluster ---"
export KUBECONFIG=$HOME/.kube/private
kubectl apply -f https://skupper.io/install.yaml
kubectl rollout status deployment/skupper-controller

echo "--- Creating Public Skupper site and Token ---"
export KUBECONFIG=$HOME/.kube/public
skupper site create public --enable-link-access -n spire || true
mkdir -p ${SCRIPT_DIR}/.tmp
skupper token issue ${SCRIPT_DIR}/.tmp/skupper.token -n spire

echo "--- Creating Private Skupper site and redeeming public token ---"
export KUBECONFIG=$HOME/.kube/private
skupper site create private -n spire || true
skupper token redeem ${SCRIPT_DIR}/.tmp/skupper.token -n spire
sleep 2
echo "Waiting for Private Skupper AccessToken to be Ready..."
skupper link status -n spire

echo "--- Configure Skupper Connectors and Listeners ---"
# Expose server of private site
skupper connector create spire-private 8443 --workload service/spire-server -n spire

export KUBECONFIG=$HOME/.kube/public
# Expose server of public site
skupper connector create spire-public 8443 --workload service/spire-server -n spire

# Consume private server
skupper listener create spire-private 443 -n spire

export KUBECONFIG=$HOME/.kube/private
# Consume public server
skupper listener create spire-public 443 -n spire

echo "--- Configure Spire Trust Bundles ---"
kubectl exec -n spire spire-server-0 -- /opt/spire/bin/spire-server bundle show -format spiffe > ${TMP}/.spiffe-private.bundle

export KUBECONFIG=$HOME/.kube/public
kubectl exec -n spire spire-server-0 -- /opt/spire/bin/spire-server bundle show -format spiffe > ${TMP}/.spiffe-public.bundle

kubectl exec -i -n spire spire-server-0 -- \
  /opt/spire/bin/spire-server bundle set \
  -format spiffe \
  -id spiffe://private.demo.example.com < ${TMP}/.spiffe-private.bundle

export KUBECONFIG=$HOME/.kube/private
kubectl exec -i -n spire spire-server-0 -- \
  /opt/spire/bin/spire-server bundle set \
  -format spiffe \
  -id spiffe://public.demo.example.com < ${TMP}/.spiffe-public.bundle

echo "--- Spire Skupper Federation Setup Complete ---"