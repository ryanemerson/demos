#!/bin/bash
#set -e
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

NAMESPACE=keycloak
kubectl apply -f "${SCRIPT_DIR}/keycloak/keycloak.yml"
kubectl rollout status statefulset/keycloak -n keycloak

KC_POD=$(kubectl -n ${NAMESPACE} get pods | grep keycloak | cut -f 1 -d ' ')
KCADMIN="kubectl -n ${NAMESPACE} exec $KC_POD -- /opt/keycloak/bin/kcadm.sh"

$KCADMIN config credentials --server http://localhost:8080 --realm master --user admin --password admin

echo "----------------------------"
echo "Create demo kubernetes realm"
echo "----------------------------"

$KCADMIN create realms -s realm=spiffe -s enabled=true

echo "------------------------------------------"
echo "Create Kubernetes Identity Provider config"
echo "------------------------------------------"

$KCADMIN create identity-provider/instances -r spiffe -s alias=spiffe -s providerId=spiffe -s config='{"trustDomain": "spiffe://demo.example.com", "bundleEndpoint": "https://localhost:8543"}'

echo "------------------------------------------------------------"
echo "Create client authenticating with SPIFFE"
echo "------------------------------------------------------------"

$KCADMIN  create clients -r spiffe -s clientId=myclient -s serviceAccountsEnabled=true -s clientAuthenticatorType=federated-jwt -s attributes='{ "jwt.credential.issuer": "spiffe", "jwt.credential.sub": " spiffe://demo.example.com/hello-client" }'

echo "Keycloak setup complete."