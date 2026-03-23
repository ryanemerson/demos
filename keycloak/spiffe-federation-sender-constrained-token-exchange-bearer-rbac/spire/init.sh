#!/bin/sh
set -e

echo "Waiting for SPIRE server to be ready..."
while ! /opt/spire/bin/spire-server healthcheck -socketPath /tmp/spire-server/private/api.sock 2>/dev/null; do
    sleep 1
done
echo "SPIRE server is ready"

echo "Creating workload entries..."

# OIDC Discovery Provider
/opt/spire/bin/spire-server entry create \
    -socketPath /tmp/spire-server/private/api.sock \
    -spiffeID spiffe://demo.example.com/spire-oidc \
    -parentID spiffe://demo.example.com/spire-agent \
    -selector docker:label:spiffe-workload:spire-oidc \
    -dns spire-oidc

# Keycloak
/opt/spire/bin/spire-server entry create \
    -socketPath /tmp/spire-server/private/api.sock \
    -spiffeID spiffe://demo.example.com/keycloak \
    -parentID spiffe://demo.example.com/spire-agent \
    -selector docker:label:spiffe-workload:keycloak \
    -dns keycloak

# Frontend
/opt/spire/bin/spire-server entry create \
    -socketPath /tmp/spire-server/private/api.sock \
    -spiffeID spiffe://demo.example.com/frontend \
    -parentID spiffe://demo.example.com/spire-agent \
    -selector docker:label:spiffe-workload:frontend \
    -dns frontend

# Microservice1
/opt/spire/bin/spire-server entry create \
    -socketPath /tmp/spire-server/private/api.sock \
    -spiffeID spiffe://demo.example.com/microservice1 \
    -parentID spiffe://demo.example.com/spire-agent \
    -selector docker:label:spiffe-workload:microservice1 \
    -dns microservice1

# Microservice2
/opt/spire/bin/spire-server entry create \
    -socketPath /tmp/spire-server/private/api.sock \
    -spiffeID spiffe://demo.example.com/microservice2 \
    -parentID spiffe://demo.example.com/spire-agent \
    -selector docker:label:spiffe-workload:microservice2 \
    -dns microservice2

echo "All entries created"

echo "Generating join token for SPIRE agent..."
TOKEN=$(/opt/spire/bin/spire-server token generate \
    -socketPath /tmp/spire-server/private/api.sock \
    -spiffeID spiffe://demo.example.com/spire-agent \
    -output json | sed -n 's/.*"value":"\([^"]*\)".*/\1/p')

echo "$TOKEN" > /run/spire/join-token/token
echo "Join token written to /run/spire/join-token/token"
