#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "============================================"
echo " Building all project dependencies"
echo "============================================"

echo ""
echo "--- [1/3] Maven: compiling Java modules ---"
mvn -f "${SCRIPT_DIR}/pom.xml" clean install -q
echo "  Maven build complete."

echo ""
echo "--- [2/3] Docker: building SCIM service image from source ---"
"${SCRIPT_DIR}/build-scim-image.sh"

echo ""
echo "--- [3/3] Docker Compose: building middleware image ---"
docker compose -f "${SCRIPT_DIR}/docker-compose.yml" build ssf-middleware
echo "  Middleware image built."

echo ""
echo "============================================"
echo " Build complete"
echo "============================================"
echo ""
echo "Next steps:"
echo "  docker compose up        # start all services"
echo "  ./setup/setup.sh         # import Topaz directory model"
echo "  ./create-user.sh alice   # create a user"
echo "  ./query-pdp.sh alice     # query the PDP"
