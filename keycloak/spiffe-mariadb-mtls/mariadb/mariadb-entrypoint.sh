#!/bin/bash
set -e

# Ensure correct permissions on certs (mysql user is uid 999 in official image)
chmod 600 /opt/spiffe-certs/server.key
chmod 644 /opt/spiffe-certs/server.crt /opt/spiffe-certs/ca.crt
chown mysql:mysql /opt/spiffe-certs/server.key /opt/spiffe-certs/server.crt /opt/spiffe-certs/ca.crt

# Delegate to the official MariaDB entrypoint with SSL enabled
exec docker-entrypoint.sh mariadbd \
    --ssl-ca=/opt/spiffe-certs/ca.crt \
    --ssl-cert=/opt/spiffe-certs/server.crt \
    --ssl-key=/opt/spiffe-certs/server.key \
    --require-secure-transport=ON
