#!/bin/bash
set -e

FAKE_CERT_DIR=/opt/fake-certs
mkdir -p "$FAKE_CERT_DIR"

# Extract the CA subject DN from the SPIFFE bundle
CA_SUBJECT=$(openssl x509 -in /opt/spiffe-certs/bundle.pem -noout -subject -nameopt RFC2253 | sed 's/subject=//')
echo "SPIFFE CA subject: $CA_SUBJECT"

# Convert RFC2253 (reverse order) to openssl -subj format (forward order)
# RFC2253: serialNumber=...,O=SPIFFE,C=US -> /C=US/O=SPIFFE/serialNumber=...
SUBJ=$(echo "$CA_SUBJECT" | tr ',' '\n' | tac | sed 's/^ *//' | sed 's/^/\//' | tr -d '\n')
echo "Fake CA subject: $SUBJ"

# Generate a fake CA with the same DN as the real SPIFFE CA
openssl req -x509 -newkey rsa:2048 \
  -keyout "$FAKE_CERT_DIR/fake-ca-key.pem" \
  -out "$FAKE_CERT_DIR/fake-ca-cert.pem" \
  -days 3650 -nodes -subj "$SUBJ" 2>/dev/null

# Generate a client cert signed by the fake CA
openssl req -newkey rsa:2048 \
  -keyout "$FAKE_CERT_DIR/invalid-mtls-key.pem" \
  -out "$FAKE_CERT_DIR/client.csr" \
  -nodes -subj "${SUBJ}/CN=frontend-invalid" 2>/dev/null

openssl x509 -req \
  -in "$FAKE_CERT_DIR/client.csr" \
  -CA "$FAKE_CERT_DIR/fake-ca-cert.pem" \
  -CAkey "$FAKE_CERT_DIR/fake-ca-key.pem" \
  -CAcreateserial \
  -out "$FAKE_CERT_DIR/invalid-mtls-cert.pem" \
  -days 3650 2>/dev/null

# Clean up intermediate files
rm -f "$FAKE_CERT_DIR/fake-ca-key.pem" "$FAKE_CERT_DIR/fake-ca-cert.pem" \
      "$FAKE_CERT_DIR/fake-ca-cert.srl" "$FAKE_CERT_DIR/client.csr"

echo "Generated invalid mTLS certificate:"
openssl x509 -in "$FAKE_CERT_DIR/invalid-mtls-cert.pem" -noout -issuer -subject

exec java -jar quarkus-run.jar
