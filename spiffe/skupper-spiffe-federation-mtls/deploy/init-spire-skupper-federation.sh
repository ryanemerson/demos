#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
TMP="${SCRIPT_DIR}"/.tmp
PUBLIC_PLATFORM="${PUBLIC_PLATFORM:-minikube}"
PRIVATE_PLATFORM="${PRIVATE_PLATFORM:-minikube}"

if [ "${PUBLIC_PLATFORM}" = "openshift" ]; then
  PUBLIC_SPIRE_SERVER_BIN="/spire-server"
else
  PUBLIC_SPIRE_SERVER_BIN="/opt/spire/bin/spire-server"
fi

if [ "${PRIVATE_PLATFORM}" = "openshift" ]; then
  PRIVATE_SPIRE_SERVER_BIN="/spire-server"
else
  PRIVATE_SPIRE_SERVER_BIN="/opt/spire/bin/spire-server"
fi

set -e
mkdir -p ${TMP}

SPIRE_NS="spire"

echo "--- Installing Skupper on Public Cluster ---"
export KUBECONFIG=$HOME/.kube/public
kubectl apply -f https://skupper.io/install.yaml
kubectl rollout status deployment/skupper-controller -n skupper

echo "--- Installing Skupper on Private Cluster ---"
export KUBECONFIG=$HOME/.kube/private
kubectl apply -f https://skupper.io/install.yaml
kubectl rollout status deployment/skupper-controller -n skupper

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
# Federation setup differs between operator-managed and manual SPIRE
# ---------------------------------------------------------------------------
if [ "${PUBLIC_PLATFORM}" = "openshift" ] && [ "${PRIVATE_PLATFORM}" = "openshift" ]; then
  # ---------------------------------------------------------------------------
  # Operator-managed: use ClusterFederatedTrustDomain CRDs
  # ---------------------------------------------------------------------------
  echo "--- Exchanging trust bundles for ClusterFederatedTrustDomain ---"

  # Get each cluster's trust bundle via the SPIRE server API
  SPIRE_SERVER_POD=$(kubectl -n "${SPIRE_NS}" get pod -l app=spire-server -o jsonpath='{.items[0].metadata.name}')
  kubectl exec -n "${SPIRE_NS}" "${SPIRE_SERVER_POD}" -c spire-server -- \
    ${PRIVATE_SPIRE_SERVER_BIN} bundle show -format spiffe > ${TMP}/.spiffe-private.bundle

  export KUBECONFIG=$HOME/.kube/public
  SPIRE_SERVER_POD=$(kubectl -n "${SPIRE_NS}" get pod -l app=spire-server -o jsonpath='{.items[0].metadata.name}')
  kubectl exec -n "${SPIRE_NS}" "${SPIRE_SERVER_POD}" -c spire-server -- \
    ${PUBLIC_SPIRE_SERVER_BIN} bundle show -format spiffe > ${TMP}/.spiffe-public.bundle

  # Escape the bundle JSON for embedding in YAML
  PRIVATE_BUNDLE=$(cat ${TMP}/.spiffe-private.bundle)
  PUBLIC_BUNDLE=$(cat ${TMP}/.spiffe-public.bundle)

  echo "--- Creating ClusterFederatedTrustDomain resources ---"

  # On the public cluster: federate with the private trust domain
  cat <<EOF | kubectl apply -f -
apiVersion: spire.spiffe.io/v1alpha1
kind: ClusterFederatedTrustDomain
metadata:
  name: private-demo
spec:
  trustDomain: private.demo.example.com
  bundleEndpointURL: https://spire-private:443
  bundleEndpointProfile:
    type: https_spiffe
    endpointSPIFFEID: spiffe://private.demo.example.com/spire/server
  trustDomainBundle: |
    ${PRIVATE_BUNDLE}
EOF

  # On the private cluster: federate with the public trust domain
  export KUBECONFIG=$HOME/.kube/private
  cat <<EOF | kubectl apply -f -
apiVersion: spire.spiffe.io/v1alpha1
kind: ClusterFederatedTrustDomain
metadata:
  name: public-demo
spec:
  trustDomain: public.demo.example.com
  bundleEndpointURL: https://spire-public:443
  bundleEndpointProfile:
    type: https_spiffe
    endpointSPIFFEID: spiffe://public.demo.example.com/spire/server
  trustDomainBundle: |
    ${PUBLIC_BUNDLE}
EOF

  # Workload registration is handled by ClusterSPIFFEID resources (deployed via kustomize)

else
  # ---------------------------------------------------------------------------
  # Manual SPIRE: exchange bundles and register entries via CLI
  # ---------------------------------------------------------------------------
  echo "--- Configure Spire Trust Bundles ---"
  kubectl exec -n spire spire-server-0 -- ${PRIVATE_SPIRE_SERVER_BIN} bundle show -format spiffe > ${TMP}/.spiffe-private.bundle

  export KUBECONFIG=$HOME/.kube/public
  kubectl exec -n spire spire-server-0 -- ${PUBLIC_SPIRE_SERVER_BIN} bundle show -format spiffe > ${TMP}/.spiffe-public.bundle

  kubectl exec -i -n spire spire-server-0 -- \
    ${PUBLIC_SPIRE_SERVER_BIN} bundle set \
    -format spiffe \
    -id spiffe://private.demo.example.com < ${TMP}/.spiffe-private.bundle

  export KUBECONFIG=$HOME/.kube/private
  kubectl exec -i -n spire spire-server-0 -- \
    ${PRIVATE_SPIRE_SERVER_BIN} bundle set \
    -format spiffe \
    -id spiffe://public.demo.example.com < ${TMP}/.spiffe-public.bundle

  echo "--- Registering federated workload entries ---"
  # hello-client workload on public cluster
  export KUBECONFIG=$HOME/.kube/public
  kubectl exec -n spire statefulset/spire-server -c spire-server -- \
    ${PUBLIC_SPIRE_SERVER_BIN} entry create \
    -spiffeID spiffe://public.demo.example.com/hello-client \
    -parentID spiffe://public.demo.example.com/ns/spire/sa/spire-agent \
    -selector k8s:ns:client \
    -selector k8s:sa:hello-client \
    -federatesWith spiffe://private.demo.example.com

  # hello-server workload on private cluster
  export KUBECONFIG=$HOME/.kube/private
  kubectl exec -n spire statefulset/spire-server -c spire-server -- \
    ${PRIVATE_SPIRE_SERVER_BIN} entry create \
    -spiffeID spiffe://private.demo.example.com/hello-server \
    -parentID spiffe://private.demo.example.com/ns/spire/sa/spire-agent \
    -selector k8s:ns:server \
    -selector k8s:sa:hello-server \
    -dns hello-server.client.svc.cluster.local \
    -federatesWith spiffe://public.demo.example.com
fi

echo "--- Spire Skupper Federation Setup Complete ---"
