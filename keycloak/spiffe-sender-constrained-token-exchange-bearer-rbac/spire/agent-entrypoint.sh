#!/bin/sh
set -e

echo "Waiting for join token..."
while [ ! -f /run/spire/join-token/token ]; do
    sleep 1
done

JOIN_TOKEN=$(cat /run/spire/join-token/token)
echo "Join token found, starting SPIRE agent..."

exec /opt/spire/bin/spire-agent run \
    -config /opt/spire/conf/agent/agent.conf \
    -joinToken "$JOIN_TOKEN"
