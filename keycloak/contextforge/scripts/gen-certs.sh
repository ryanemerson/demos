#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="$SCRIPT_DIR/../certs"
mkdir -p "$CERT_DIR"
CERT_DIR="$(cd "$CERT_DIR" && pwd)"

if [ -f "$CERT_DIR/server.crt" ] && [ -f "$CERT_DIR/server.key" ]; then
  echo "Certificates already exist in $CERT_DIR — skipping generation."
  exit 0
fi

echo "Generating self-signed CA and server certificate for localhost..."

openssl req -x509 -nodes -newkey rsa:2048 \
  -keyout "$CERT_DIR/ca.key" -out "$CERT_DIR/ca.crt" \
  -days 365 -subj "/CN=Demo CA"

openssl req -nodes -newkey rsa:2048 \
  -keyout "$CERT_DIR/server.key" -out "$CERT_DIR/server.csr" \
  -subj "/CN=localhost"

openssl x509 -req -in "$CERT_DIR/server.csr" \
  -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
  -out "$CERT_DIR/server.crt" -days 365 \
  -extfile <(printf "subjectAltName=DNS:localhost,IP:127.0.0.1")

rm -f "$CERT_DIR/server.csr" "$CERT_DIR/ca.srl"

echo "Creating combined CA bundle for the gateway container..."
GATEWAY_IMAGE="ghcr.io/ibm/mcp-context-forge:v1.0.3"
docker run --rm --entrypoint "" "$GATEWAY_IMAGE" \
  cat /app/.venv/lib64/python3.12/site-packages/certifi/cacert.pem \
  > "$CERT_DIR/ca-bundle.pem"
cat "$CERT_DIR/ca.crt" >> "$CERT_DIR/ca-bundle.pem"

echo "Certificates generated in $CERT_DIR"
