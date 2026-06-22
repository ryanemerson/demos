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

echo "Creating gateway users..."

# Create alice (admin user)
ALICE_RESP=$(curl -s -w "\n%{http_code}" -X POST http://localhost:4444/auth/email/admin/users \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@demo.com","password":"Gw8#Pz!mK4vXr2@Lq9jN5k","full_name":"Alice Admin","is_admin":true}')
ALICE_CODE=$(echo "$ALICE_RESP" | tail -1)
if [ "$ALICE_CODE" -ge 200 ] && [ "$ALICE_CODE" -lt 300 ]; then
  echo "  Created alice@demo.com (admin)"
else
  echo "  alice@demo.com already exists or creation returned HTTP $ALICE_CODE (continuing)"
fi

# Create bob (regular user)
BOB_RESP=$(curl -s -w "\n%{http_code}" -X POST http://localhost:4444/auth/email/admin/users \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"email":"bob@demo.com","password":"Gw8#Pz!mK4vXr2@Lq9jN5k","full_name":"Bob User","is_admin":false}')
BOB_CODE=$(echo "$BOB_RESP" | tail -1)
if [ "$BOB_CODE" -ge 200 ] && [ "$BOB_CODE" -lt 300 ]; then
  echo "  Created bob@demo.com (user)"
else
  echo "  bob@demo.com already exists or creation returned HTTP $BOB_CODE (continuing)"
fi

echo "Registering MCP gateway connection..."

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:4444/gateways \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "mcp-server",
    "url": "http://localhost:8085/mcp/sse",
    "description": "Demo MCP Server with identity and secret tools",
    "transport": "SSE",
    "auth_type": "oauth",
    "oauth_config": {
      "grant_type": "client_credentials",
      "client_id": "mcp-gateway",
      "client_secret": "mcp-gateway-secret",
      "token_url": "https://localhost:8443/realms/demo/protocol/openid-connect/token",
      "scopes": ["openid"]
    },
    "passthrough_headers": ["X-Forwarded-User-Email", "X-Forwarded-User-Id", "X-Forwarded-User-Admin", "X-Forwarded-User-Roles"]
  }')

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 300 ]; then
  echo "  Gateway registered (HTTP $HTTP_CODE)"
else
  echo "Gateway registration failed (HTTP $HTTP_CODE):"
  echo "$BODY" | jq . 2>/dev/null || echo "$BODY"
  exit 1
fi

echo "Waiting for tool discovery..."
TOOL_IDS=""
for i in $(seq 1 15); do
  TOOLS=$(curl -s http://localhost:4444/tools -H "Authorization: Bearer $TOKEN")
  TOOL_IDS=$(echo "$TOOLS" | jq -r '[.[] | .id] | join(",")')
  TOOL_COUNT=$(echo "$TOOL_IDS" | tr ',' '\n' | grep -c .)
  if [ "$TOOL_COUNT" -ge 2 ]; then
    echo "  Discovered $TOOL_COUNT tools"
    break
  fi
  sleep 2
done

if [ -z "$TOOL_IDS" ]; then
  echo "  WARNING: No tools discovered, creating server without tool associations"
fi

TOOL_ARRAY=$(echo "$TOOL_IDS" | tr ',' '\n' | jq -R . | jq -s .)

echo "Creating OAuth-protected virtual server..."
SERVER_RESP=$(curl -s -w "\n%{http_code}" -X POST http://localhost:4444/servers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"server\": {
      \"name\": \"mcp-server\",
      \"description\": \"Demo MCP Server with identity and secret tools\",
      \"oauth_enabled\": true,
      \"oauth_config\": {
        \"authorization_servers\": [\"https://localhost:8443/realms/demo\"],
        \"resource\": \"mcp-gateway\"
      },
      \"associated_tools\": $TOOL_ARRAY
    }
  }")

SERVER_CODE=$(echo "$SERVER_RESP" | tail -1)
SERVER_BODY=$(echo "$SERVER_RESP" | sed '$d')

if [ "$SERVER_CODE" -ge 200 ] && [ "$SERVER_CODE" -lt 300 ]; then
  SERVER_ID=$(echo "$SERVER_BODY" | jq -r '.id // empty')
  mkdir -p /data
  echo "$SERVER_ID" > /data/server-id
  echo "  Server created with OAuth enabled"
  echo "  Server ID: $SERVER_ID"
  echo "  MCP URL: http://localhost:4444/servers/$SERVER_ID/mcp"
else
  echo "Server creation failed (HTTP $SERVER_CODE):"
  echo "$SERVER_BODY" | jq . 2>/dev/null || echo "$SERVER_BODY"
  exit 1
fi
