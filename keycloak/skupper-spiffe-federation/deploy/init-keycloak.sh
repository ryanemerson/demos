#!/bin/bash
set -e

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
TMP=${SCRIPT_DIR}/.tmp
PUBLIC_PLATFORM="${PUBLIC_PLATFORM:-minikube}"
mkdir -p ${TMP}
export KUBECONFIG=$HOME/.kube/public

echo "----------------------------"
echo "Deploy Keycloak to the Public Cluster"
echo "----------------------------"
if [ "${PUBLIC_PLATFORM}" = "openshift" ]; then
  kubectl apply -k "${SCRIPT_DIR}/overlays/openshift/keycloak"
else
  kubectl apply -k "${SCRIPT_DIR}/keycloak"
fi

echo "--- Configure Keycloak Spire Federation CA ---"
kubectl delete secret spiffe-server-oidc-ca -n keycloak --ignore-not-found
kubectl create secret generic spiffe-server-oidc-ca -n keycloak --from-file=ca.pem=${TMP}/spire-public.pem

kubectl rollout status statefulset/keycloak -n keycloak

echo "----------------------------"
echo "Configure Keycloak"
echo "----------------------------"
KCEXEC="kubectl -n keycloak exec statefulset/keycloak -c keycloak --"
KCADMIN="$KCEXEC /opt/keycloak/bin/kcadm.sh"
KCCONFIG="--config /tmp/kcadm.config"

# Build a JKS truststore from the SPIFFE CA bundle so that kcadm.sh can verify
# Keycloak's TLS certificate (a SPIFFE X.509-SVID signed by the SPIRE CA).
$KCEXEC rm -f /tmp/truststore.jks
$KCEXEC keytool -importcert -noprompt -alias spiffe-ca \
  -file /opt/spiffe-certs/bundle.pem \
  -keystore /tmp/truststore.jks \
  -storepass changeit

$KCADMIN config truststore --trustpass changeit /tmp/truststore.jks ${KCCONFIG}

$KCADMIN config credentials ${KCCONFIG} \
  --server https://keycloak.keycloak.svc.cluster.local:8443 \
  --realm master --user admin --password admin

echo "----------------------------"
echo "Create demo kubernetes realm"
echo "----------------------------"

$KCADMIN create realms ${KCCONFIG} -s realm=spiffe -s enabled=true

echo "------------------------------------------"
echo "Create Kubernetes Identity Provider config"
echo "------------------------------------------"

$KCADMIN create identity-provider/instances ${KCCONFIG} -r spiffe -s alias=spiffe -s providerId=spiffe -s config='{"trustDomain": "spiffe://public.demo.example.com", "bundleEndpoint": "https://spire-server.spire.svc.cluster.local:8443"}'

echo "------------------------------------------------------------"
echo "Create client authenticating with SPIFFE"
echo "------------------------------------------------------------"

$KCADMIN create clients ${KCCONFIG} -r spiffe -s clientId=hello-client -s serviceAccountsEnabled=true -s clientAuthenticatorType=federated-jwt -s attributes='{ "jwt.credential.issuer": "spiffe", "jwt.credential.sub": "spiffe://public.demo.example.com/hello-client" }'

echo "------------------------------------------------------------"
echo "Create 'admin' role and assign to hello-client"
echo "------------------------------------------------------------"

$KCADMIN create roles ${KCCONFIG} -r spiffe -s name=admin
$KCADMIN add-roles ${KCCONFIG} -r spiffe --uusername service-account-hello-client --rolename admin

echo "------------------------------------------------------------"
echo "Enable mTLS client authentication"
echo "------------------------------------------------------------"
# TODO there's currently no way to use the kcadm.sh cli with mTLS so we have to start with KC_HTTPS_CLIENT_AUTH=none and then set it to required
kubectl -n keycloak patch statefulset keycloak --type json -p '[
  {"op": "add", "path": "/spec/template/spec/containers/0/env/-", "value": {"name": "KC_HTTPS_CLIENT_AUTH", "value": "required"}}
]'
kubectl rollout status statefulset/keycloak -n keycloak

# Create an empty keycloak namespace on the private cluster
# Make Skupper link from public to private cluster in namespace
echo "----------------------------"
echo "Configure Skupper between clusters"
echo "----------------------------"

echo "--- Creating Public Skupper site and Token ---"
skupper site create public --enable-link-access -n keycloak || true
skupper token issue ${TMP}/skupper-keycloak.token -n keycloak
skupper connector create keycloak 8443 --workload service/keycloak -n keycloak

echo "--- Creating Private Skupper site and redeeming public token ---"
export KUBECONFIG=$HOME/.kube/private
kubectl create namespace keycloak || true
skupper site create private -n keycloak || true
skupper token redeem ${TMP}/skupper-keycloak.token -n keycloak
sleep 2
echo "Waiting for Private Skupper AccessToken to be Ready..."
skupper link status -n keycloak
skupper listener create keycloak 8443 -n keycloak

echo "Keycloak setup complete."
