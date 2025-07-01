#!/bin/bash

DB_URL="jdbc:oracle:thin:@//database-1.clhthfqe0h8p.eu-west-1.rds.amazonaws.com:1521/ORCL"
DB_USERNAME="admin"
DB_PASSWORD=""

set -x

docker run -it --rm -p 8080:8080 -p 8443:8443 \
-e KC_BOOTSTRAP_ADMIN_USERNAME=admin  \
-e KC_BOOTSTRAP_ADMIN_PASSWORD=123 \
-e KC_DB_URL="${DB_URL}" \
-e KC_DB_USERNAME="${DB_USERNAME}" \
-e KC_DB_PASSWORD="${DB_PASSWORD}" \
-e KC_HOSTNAME_STRICT=false \
--name MyKeycloak \
my-keycloak
