#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <subject_id>"
  exit 1
fi

SUBJECT_ID="$1"
TOPAZ_AUTHZEN_URL="${TOPAZ_AUTHZEN_URL:-http://localhost:9393}"

echo "=== Querying Topaz AuthZEN PDP for subject '${SUBJECT_ID}' ==="
echo ""

RESPONSE=$(curl -sf -X POST "${TOPAZ_AUTHZEN_URL}/access/v1/evaluation" \
  -H "Content-Type: application/json" \
  -d "{
    \"subject\": {
      \"type\": \"identity\",
      \"id\": \"${SUBJECT_ID}\"
    },
    \"action\": {
      \"name\": \"can_read\"
    },
    \"resource\": {
      \"type\": \"document\",
      \"id\": \"doc-1\"
    }
  }")

echo "Response:"
echo "${RESPONSE}" | jq . 2>/dev/null || echo "${RESPONSE}"
