#!/bin/sh
set -e

echo "Installing dependencies..."
apk add --no-cache curl jq >/dev/null 2>&1

echo "Waiting for MCP server on port 8085..."
for i in $(seq 1 30); do
  HTTP_CODE=$(curl -o /dev/null -s -w '%{http_code}' --connect-timeout 2 http://localhost:8085/ 2>/dev/null || echo "000")
  if [ "$HTTP_CODE" != "000" ]; then
    echo "  MCP server is accepting connections."
    break
  fi
  if [ "$i" = "30" ]; then
    echo "  WARNING: MCP server may not be ready, proceeding anyway..."
  fi
  sleep 2
done

echo "Authenticating with ContextForge gateway..."
LOGIN_RESPONSE=$(curl -s -X POST http://localhost:4444/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"admin"}')

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.access_token // empty')
if [ -z "$TOKEN" ]; then
  echo "Login failed:"
  echo "$LOGIN_RESPONSE" | jq . 2>/dev/null || echo "$LOGIN_RESPONSE"
  exit 1
fi
echo "  Authenticated successfully."

echo "Registering MCP server with ContextForge gateway..."

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:4444/gateways \
  -H "X-MCP-Gateway-Auth: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "mcp-server",
    "url": "http://localhost:8085/mcp/sse",
    "description": "Demo MCP Server with identity and secret tools",
    "transport": "SSE",
    "auth_type": "none",
    "passthrough_headers": ["Authorization"]
  }')

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 300 ]; then
  echo "MCP server registered successfully (HTTP $HTTP_CODE):"
  echo "$BODY" | jq . 2>/dev/null || echo "$BODY"
else
  echo "Registration failed (HTTP $HTTP_CODE):"
  echo "$BODY" | jq . 2>/dev/null || echo "$BODY"
  exit 1
fi
