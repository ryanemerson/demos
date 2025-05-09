#!/bin/bash

# === Configuration (env overrideable) ===
OIDC_HOST="${OIDC_HOST:-localhost:8080}"
OIDC_REALM="${OIDC_REALM:-realm1}"
CLIENT_ID="${CLIENT_ID:-backend}"
CLIENT_SECRET="${CLIENT_SECRET:-my-secret}"
API_URL="${API_URL:-http://localhost:8081/api/secure}"

# === Construct Token URL ===
OIDC_TOKEN_URL="http://${OIDC_HOST}/realms/${OIDC_REALM}/protocol/openid-connect/token"

# === Get Access Token ===
echo "🔐 Requesting access token from $OIDC_TOKEN_URL ..."
RESPONSE=$(curl -s -X POST "$OIDC_TOKEN_URL" \
  -d "grant_type=client_credentials" \
  -d "client_id=$CLIENT_ID" \
  -d "client_secret=$CLIENT_SECRET")

ACCESS_TOKEN=$(echo "$RESPONSE" | jq -r .access_token)

if [ "$ACCESS_TOKEN" == "null" ] || [ -z "$ACCESS_TOKEN" ]; then
  echo "❌ Failed to retrieve access token:"
  echo "$RESPONSE"
  exit 1
fi

echo "✅ Access token retrieved."

# === Call Protected API ===
echo "🌐 Calling API at $API_URL ..."
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
    "$API_URL"
printf "\n"
