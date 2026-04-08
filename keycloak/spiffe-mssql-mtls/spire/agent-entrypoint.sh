#!/bin/sh
set -e

echo "Waiting for join token..."
while [ ! -f /run/spire/join-token/token ]; do
    sleep 1
done

TOKEN=$(cat /run/spire/join-token/token)
echo "Starting SPIRE agent with join token..."
exec /opt/spire/bin/spire-agent run -config /opt/spire/conf/agent.conf -joinToken "${TOKEN}"
