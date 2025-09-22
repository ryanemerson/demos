# Configure Kubernetes
1. Make `/.well-known/openid-configuration` and `/openid/v1/jwks` publicly
   accessibe

```
kubectl patch clusterrolebinding system:service-account-issuer-discovery --type='merge' -p '{"subjects":[{"apiGroup":"rbac.authorization.k8s.io","kind":"User","name":"system:anonymous"}]}'
```

# Launch Keycloak
1. ./kc.sh start-dev --features=client-auth-federated --spi-connections-http-client--default--disable-trust-manager=true
2. Create admin user

# Configure Keycloak
1. Create realm:

```
./kcadm.sh create realms -s realm=kubernetes -s enabled=true
```

2. Create identity-provider:

```
./kcadm.sh create identity-provider/instances -r kubernetes     -f - << EOF
{
  "alias": "kubernetes",
  "providerId": "kubernetes",
  "hideOnLogin": true,
  "config": {
    "validateSignature": "true",
    "issuer": "https://kubernetes.default.svc.cluster.local",
    "useJwksUrl": "true",
    "supportsClientAssertions": "true",
    "supportsClientAssertionReuse": "true",
    "showInAccountConsole": "NEVER"
  }
}
EOF
```

Update the ip of the `jwksUrl` to that of your Kubernetes cluster, e.g.
`minikube ip` or `oc whoami --show-server`.

3. Create client:

```
./kcadm.sh create clients -r kubernetes  -f - << EOF
{                                                                                      "clientId": "myclient",
  "serviceAccountsEnabled": true,
  "clientAuthenticatorType": "federated-jwt",
  "attributes": {
    "jwt.credential.issuer": "kubernetes",
    "jwt.credential.sub": "system:serviceaccount:default:default"
  }
}
EOF
```

# Try it out
1. Retrieve Kubernetes Service Account Token
```
TOKEN=$(kubectl create token default --audience="http://localhost:8080/realms/kubernetes")
```

2. Client credential grant

```
ACCESS_TOKEN=$(curl -s -X POST \
  -d grant_type=client_credentials \
  -d client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer \
  -d client_assertion="$TOKEN" \
  http://localhost:8080/realms/kubernetes/protocol/openid-connect/token | jq -r .access_token)
```

3. Token introspection

```
curl -s -X POST \
  -d token=$ACCESS_TOKEN \
  -d client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer \
  -d client_assertion="$TOKEN" \
  http://localhost:8080/realms/kubernetes/protocol/openid-connect/token/introspect | jq .
```

# Retrieve JWKS within pod

```
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: jwks-printer-pod
spec:
  containers:
  - name: jwks-printer
    image: curlimages/curl:latest
    command: ["/bin/sh", "-c"]
    args:
    - |
      API_SERVER="https://kubernetes.default.svc"
      TOKEN_PATH="/var/run/secrets/kubernetes.io/serviceaccount/token"
      CACERT_PATH="/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"

      curl --cacert \$CACERT_PATH \
           --header "Authorization: Bearer \$(cat \$TOKEN_PATH)" \
           -s \
           \$API_SERVER/openid/v1/jwks
  restartPolicy: Never
EOF
```
