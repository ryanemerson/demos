#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLONE_DIR="${SCRIPT_DIR}/.scim-build"
IMAGE_NAME="ghcr.io/aserto-dev/scim:latest"
REPO_URL="https://github.com/aserto-dev/scim.git"

echo "=== Building aserto-dev/scim Docker image from source ==="

if [ -d "${CLONE_DIR}" ]; then
  echo "  Updating existing clone..."
  git -C "${CLONE_DIR}" fetch --all --prune
  git -C "${CLONE_DIR}" reset --hard origin/main
else
  echo "  Cloning ${REPO_URL}..."
  git clone "${REPO_URL}" "${CLONE_DIR}"
fi

echo ""
echo "=== Compiling Go binary ==="
docker build \
  -f - \
  -t "${IMAGE_NAME}" \
  "${CLONE_DIR}" <<'DOCKERFILE'
FROM golang:1.24-alpine AS builder

RUN apk add --no-cache git

WORKDIR /src
COPY . .

RUN go mod download
RUN CGO_ENABLED=0 go build -o /aserto-scim ./cmd/aserto-scim

FROM alpine

RUN apk add --no-cache bash tzdata

WORKDIR /app
COPY --from=builder /aserto-scim /app/aserto-scim

ENTRYPOINT ["./aserto-scim"]
DOCKERFILE

echo ""
echo "=== Done ==="
echo "Image built: ${IMAGE_NAME}"
echo ""
echo "You can verify with:"
echo "  docker run --rm ${IMAGE_NAME} --help"
