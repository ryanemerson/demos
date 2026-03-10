#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
TMP="${SCRIPT_DIR}"/.tmp
SPIRE_SERVER_BIN="/opt/spire/bin/spire-server"

set -e
mkdir -p ${TMP}

SPIRE_NS="spire"

echo "--- Creating Public Skupper site and Token ---"
export KUBECONFIG=$HOME/.kube/public
skupper site create public --enable-link-access -n "${SPIRE_NS}" || true
skupper token issue ${TMP}/skupper-spire.token -n "${SPIRE_NS}"

echo "--- Creating Private Skupper site and redeeming public token ---"
export KUBECONFIG=$HOME/.kube/private
skupper site create private -n "${SPIRE_NS}" || true
skupper token redeem ${TMP}/skupper-spire.token -n "${SPIRE_NS}"
sleep 2
echo "Waiting for Private Skupper AccessToken to be Ready..."
skupper link status -n "${SPIRE_NS}"

echo "--- Configure Skupper Connectors and Listeners ---"
# Expose servers of private site
skupper connector create spire-private 8443 --workload service/spire-server -n "${SPIRE_NS}"

export KUBECONFIG=$HOME/.kube/public
# Expose server of public site
skupper connector create spire-public 8443 --workload service/spire-server -n "${SPIRE_NS}"

# Consume private server
skupper listener create spire-private 443 -n "${SPIRE_NS}"

export KUBECONFIG=$HOME/.kube/private
# Consume public server
skupper listener create spire-public 443 -n "${SPIRE_NS}"

# ---------------------------------------------------------------------------
# Exchange trust bundles and register workload entries via CLI
# ---------------------------------------------------------------------------
echo "--- Configure Spire Trust Bundles ---"
kubectl exec -n spire spire-server-0 -c spire-server -- ${SPIRE_SERVER_BIN} bundle show -format spiffe > ${TMP}/spire-private.bundle

export KUBECONFIG=$HOME/.kube/public
kubectl exec -n spire spire-server-0 -c spire-server -- ${SPIRE_SERVER_BIN} bundle show -format spiffe > ${TMP}/spire-public.bundle

kubectl exec -i -n spire spire-server-0 -c spire-server -- \
  ${SPIRE_SERVER_BIN} bundle set \
  -format spiffe \
  -id spiffe://private.demo.example.com < ${TMP}/spire-private.bundle

export KUBECONFIG=$HOME/.kube/private
kubectl exec -i -n spire spire-server-0 -c spire-server -- \
  ${SPIRE_SERVER_BIN} bundle set \
  -format spiffe \
  -id spiffe://public.demo.example.com < ${TMP}/spire-public.bundle

echo "--- Registering federated workload entries ---"
# hello-client workload on public cluster
export KUBECONFIG=$HOME/.kube/public
kubectl exec -n spire spire-server-0 -c spire-server -- \
  ${SPIRE_SERVER_BIN} entry create \
  -spiffeID spiffe://public.demo.example.com/hello-client \
  -parentID spiffe://public.demo.example.com/ns/spire/sa/spire-agent \
  -selector k8s:ns:client \
  -selector k8s:sa:hello-client \
  -federatesWith spiffe://private.demo.example.com

# keycloak workload
kubectl exec -n spire spire-server-0 -c spire-server -- \
  ${SPIRE_SERVER_BIN} entry create \
  -spiffeID spiffe://public.demo.example.com/keycloak \
  -parentID spiffe://public.demo.example.com/ns/spire/sa/spire-agent \
  -selector k8s:ns:keycloak \
  -selector k8s:sa:default \
  -federatesWith spiffe://private.demo.example.com \
  -dns keycloak.keycloak.svc.cluster.local # Allows kcadm.sh hostname verification to work as expected

# hello-server workload on private cluster
export KUBECONFIG=$HOME/.kube/private
kubectl exec -n spire spire-server-0 -c spire-server -- \
  ${SPIRE_SERVER_BIN} entry create \
  -spiffeID spiffe://private.demo.example.com/hello-server \
  -parentID spiffe://private.demo.example.com/ns/spire/sa/spire-agent \
  -selector k8s:ns:server \
  -selector k8s:sa:hello-server \
  -dns hello-server.client.svc.cluster.local \
  -federatesWith spiffe://public.demo.example.com

echo "--- Spire Skupper Federation Setup Complete ---"
