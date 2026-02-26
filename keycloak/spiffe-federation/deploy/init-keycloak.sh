#!/bin/bash
set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

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

$KCADMIN create identity-provider/instances -r spiffe -s alias=spiffe -s providerId=spiffe -s config='{"trustDomain": "spiffe://demo.example.com", "bundleEndpoint": "https://spire-server.spire.svc.cluster.local:443"}'

echo "------------------------------------------------------------"
echo "Create client authenticating with SPIFFE"
echo "------------------------------------------------------------"

$KCADMIN  create clients -r spiffe -s clientId=hello-client -s serviceAccountsEnabled=true -s clientAuthenticatorType=federated-jwt -s attributes='{ "jwt.credential.issuer": "spiffe", "jwt.credential.sub": " spiffe://demo.example.com/hello-client" }'

echo "Keycloak setup complete."
