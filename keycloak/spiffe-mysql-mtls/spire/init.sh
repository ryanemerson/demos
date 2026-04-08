#!/bin/sh
set -e

echo "Waiting for SPIRE server to be ready..."
until /opt/spire/bin/spire-server healthcheck -socketPath /run/spire/server.sock 2>/dev/null; do
    sleep 1
done
echo "SPIRE server is ready."

# Generate join token first — we need the token value to construct the agent's SPIFFE ID
echo "Generating join token..."
RAW=$(/opt/spire/bin/spire-server token generate -socketPath /run/spire/server.sock)
echo "Raw output: ${RAW}"
TOKEN=$(echo "${RAW}" | sed -n 's/^Token: *//p')
if [ -z "${TOKEN}" ]; then
    echo "ERROR: Failed to extract join token"
    exit 1
fi
echo "Join token: ${TOKEN}"
echo -n "${TOKEN}" > /run/spire/join-token/token
echo "Join token written."

# The agent's SPIFFE ID after join_token attestation is:
#   spiffe://<trust_domain>/spire/agent/join_token/<token_value>
AGENT_ID="spiffe://demo.example.com/spire/agent/join_token/${TOKEN}"
echo "Agent SPIFFE ID will be: ${AGENT_ID}"

# Register workload entries with the agent as parent
echo "Registering workload: keycloak"
/opt/spire/bin/spire-server entry create \
    -socketPath /run/spire/server.sock \
    -spiffeID spiffe://demo.example.com/keycloak \
    -parentID "${AGENT_ID}" \
    -selector docker:label:spiffe-workload:keycloak \
    -dns keycloak

echo "Registering workload: mysql"
/opt/spire/bin/spire-server entry create \
    -socketPath /run/spire/server.sock \
    -spiffeID spiffe://demo.example.com/mysql \
    -parentID "${AGENT_ID}" \
    -selector docker:label:spiffe-workload:mysql \
    -dns mysql

echo "Init complete."
