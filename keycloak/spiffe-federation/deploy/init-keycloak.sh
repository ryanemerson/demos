#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

kubectl create namespace keycloak --dry-run=client -o yaml | kubectl apply -f -

kubectl delete secret spiffe-server-oidc-ca -n keycloak --ignore-not-found
kubectl create secret generic spiffe-server-oidc-ca -n keycloak \
  --from-literal=ca.pem="$(kubectl get secret oidc-discovery-certs -n spire -o jsonpath='{.data.tls\.crt}' | base64 -d)" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -f "${SCRIPT_DIR}/keycloak/keycloak.yml"
kubectl rollout status statefulset/keycloak -n keycloak

KCEXEC="kubectl -n keycloak exec statefulset/keycloak -c keycloak --"
KCADMIN="$KCEXEC /opt/keycloak/bin/kcadm.sh"

# Build a JKS truststore from the SPIFFE CA bundle so that kcadm.sh can verify
# Keycloak's TLS certificate (a SPIFFE X.509-SVID signed by the SPIRE CA).
$KCEXEC rm -f /tmp/truststore.jks
$KCEXEC keytool -importcert -noprompt -alias spiffe-ca \
  -file /opt/spiffe-certs/bundle.pem \
  -keystore /tmp/truststore.jks \
  -storepass changeit

$KCADMIN config truststore --trustpass changeit /tmp/truststore.jks

$KCADMIN config credentials \
  --server https://keycloak.keycloak.svc.cluster.local:8443 \
  --realm master --user admin --password admin

echo "----------------------------"
echo "Create demo kubernetes realm"
echo "----------------------------"

$KCADMIN create realms -s realm=spiffe -s enabled=true

echo "------------------------------------------"
echo "Create Kubernetes Identity Provider config"
echo "------------------------------------------"

$KCADMIN create identity-provider/instances -r spiffe -s alias=spiffe -s providerId=spiffe -s config='{"trustDomain": "spiffe://demo.example.com", "bundleEndpoint": "https://spire-server.spire.svc.cluster.local:443/keys"}'

echo "------------------------------------------------------------"
echo "Create client authenticating with SPIFFE"
echo "------------------------------------------------------------"

$KCADMIN create clients -r spiffe -s clientId=hello-client -s serviceAccountsEnabled=true -s clientAuthenticatorType=federated-jwt -s attributes='{ "jwt.credential.issuer": "spiffe", "jwt.credential.sub": "spiffe://demo.example.com/hello-client" }'

echo "------------------------------------------------------------"
echo "Create 'admin' role and assign to hello-client"
echo "------------------------------------------------------------"

$KCADMIN create roles -r spiffe -s name=admin
$KCADMIN add-roles -r spiffe --uusername service-account-hello-client --rolename admin

echo "------------------------------------------------------------"
echo "Enable mTLS client authentication"
echo "------------------------------------------------------------"
# TODO there's currently no way to use the kcadm.sh cli with mTLS so we have to start with KC_HTTPS_CLIENT_AUTH=none and then set it to required
kubectl -n keycloak patch statefulset keycloak --type json -p '[
  {"op": "add", "path": "/spec/template/spec/containers/0/env/-", "value": {"name": "KC_HTTPS_CLIENT_AUTH", "value": "required"}}
]'
kubectl rollout status statefulset/keycloak -n keycloak

echo "Keycloak setup complete."
