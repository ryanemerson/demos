#!/bin/bash

docker run --rm --name keycloak \
    -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
    -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
    -p 8081:8080 \
    quay.io/keycloak/keycloak:latest \
    start-dev
