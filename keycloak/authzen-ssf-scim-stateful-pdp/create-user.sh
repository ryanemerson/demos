#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <username> [role1] [role2] ..."
  exit 1
fi

USERNAME="$1"
shift
ROLES=("$@")

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
REALM="demo"

echo "=== Obtaining admin access token ==="
TOKEN=$(curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli" \
  -d "username=admin" \
  -d "password=admin" \
  -d "grant_type=password" | jq -r '.access_token')

USER_JSON=$(jq -n --arg username "$USERNAME" '{username: $username, enabled: true}')

echo "=== Creating user '${USERNAME}' in realm '${REALM}' ==="

HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${USER_JSON}")

if [ "$HTTP_STATUS" = "201" ]; then
  echo "  User '${USERNAME}' created successfully."
elif [ "$HTTP_STATUS" = "409" ]; then
  echo "  User '${USERNAME}' already exists."
else
  echo "  Failed to create user. HTTP status: ${HTTP_STATUS}"
  exit 1
fi

if [ ${#ROLES[@]} -gt 0 ]; then
  USER_ID=$(curl -sf "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=${USERNAME}&exact=true" \
    -H "Authorization: Bearer ${TOKEN}" | jq -r '.[0].id')

  for ROLE_NAME in "${ROLES[@]}"; do
    echo "=== Assigning realm role '${ROLE_NAME}' to user '${USERNAME}' ==="

    ROLE_JSON=$(curl -s -w "\n%{http_code}" \
      "${KEYCLOAK_URL}/admin/realms/${REALM}/roles/${ROLE_NAME}" \
      -H "Authorization: Bearer ${TOKEN}")
    ROLE_STATUS=$(echo "$ROLE_JSON" | tail -1)
    ROLE_BODY=$(echo "$ROLE_JSON" | sed '$d')

    if [ "$ROLE_STATUS" != "200" ]; then
      echo "  Error: realm role '${ROLE_NAME}' does not exist (HTTP ${ROLE_STATUS})"
      exit 1
    fi

    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
      "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${USER_ID}/role-mappings/realm" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "[${ROLE_BODY}]")

    if [ "$HTTP_STATUS" = "204" ]; then
      echo "  Role '${ROLE_NAME}' assigned to '${USERNAME}'."
    else
      echo "  Failed to assign role. HTTP status: ${HTTP_STATUS}"
      exit 1
    fi
  done
fi

echo ""
echo "The SCIM SSF event listener will push the user to the Topaz PDP."
echo "You can verify with: ./query-pdp.sh ${USERNAME}"
