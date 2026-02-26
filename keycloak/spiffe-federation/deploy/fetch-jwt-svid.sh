#!/bin/bash -e

kubectl exec -n keycloak statefulset/keycloak -c spiffe-helper -- \
    cat /opt/spiffe-certs/jwt.token
