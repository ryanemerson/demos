#!/bin/bash
set -euo pipefail

SERVER_ID_FILE="./data/server-id"

if [ ! -f "$SERVER_ID_FILE" ]; then
  echo "Server ID file not found at $SERVER_ID_FILE"
  echo "Run 'docker compose up -d' first and wait for gateway-init to complete."
  exit 1
fi

SERVER_ID=$(cat "$SERVER_ID_FILE")
export MCP_SERVER_URL="http://localhost:4444/servers/${SERVER_ID}/mcp"
echo "MCP Server URL: $MCP_SERVER_URL"

cd chatbot
mvn quarkus:dev -Dquarkus.args=""
