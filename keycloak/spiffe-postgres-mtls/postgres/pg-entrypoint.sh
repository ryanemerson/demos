#!/bin/bash
set -e

echo "Waiting for SPIFFE certificates to be provisioned..."
while [ ! -f /opt/spiffe-certs/server.crt ] || [ ! -f /opt/spiffe-certs/server.key ] || [ ! -f /opt/spiffe-certs/ca.crt ]; do
    sleep 1
done
echo "SPIFFE certificates found. Starting PostgreSQL with SSL..."

# Ensure correct permissions on certs
chmod 600 /opt/spiffe-certs/server.key
chmod 644 /opt/spiffe-certs/server.crt /opt/spiffe-certs/ca.crt
chown 999:999 /opt/spiffe-certs/server.key /opt/spiffe-certs/server.crt /opt/spiffe-certs/ca.crt

# Delegate to the official PostgreSQL entrypoint
exec docker-entrypoint.sh postgres \
    -c ssl=on \
    -c ssl_cert_file=/opt/spiffe-certs/server.crt \
    -c ssl_key_file=/opt/spiffe-certs/server.key \
    -c ssl_ca_file=/opt/spiffe-certs/ca.crt \
    -c hba_file=/etc/postgresql/pg_hba.conf
