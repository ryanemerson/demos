#!/bin/bash
set -e

WALLET_DIR=/opt/oracle/oradata/wallet

echo "Setting up Oracle TLS with SPIFFE certificates..."

# Create wallet directory (remove any existing wallet from a previous run)
rm -rf ${WALLET_DIR}
mkdir -p ${WALLET_DIR}

# Create an Oracle auto-login wallet
orapki wallet create -wallet ${WALLET_DIR} -pwd WalletPasswd123 -auto_login

# Add the CA certificate as a trusted certificate
orapki wallet add -wallet ${WALLET_DIR} -trusted_cert -cert /opt/spiffe-certs/ca.crt -pwd WalletPasswd123

# Create PKCS12 from server cert and key, then import into wallet
openssl pkcs12 -export \
    -in /opt/spiffe-certs/server.crt \
    -inkey /opt/spiffe-certs/server.key \
    -out /tmp/server.p12 \
    -password pass:ServerP12Pass \
    -name oracle_server

orapki wallet import_pkcs12 -wallet ${WALLET_DIR} -pkcs12file /tmp/server.p12 -pkcs12pwd ServerP12Pass -pwd WalletPasswd123
rm -f /tmp/server.p12

# Write initial sqlnet.ora with wallet location only (no authentication restrictions yet,
# so that local sqlplus commands below can still connect via OS authentication)
cat > ${ORACLE_HOME}/network/admin/sqlnet.ora <<EOF
WALLET_LOCATION = (SOURCE = (METHOD = FILE) (METHOD_DATA = (DIRECTORY = ${WALLET_DIR})))
SSL_VERSION = 1.2
EOF

# Add TCPS listener on port 2484 alongside TCP on 1521
cat > ${ORACLE_HOME}/network/admin/listener.ora <<EOF
LISTENER =
  (DESCRIPTION_LIST =
    (DESCRIPTION =
      (ADDRESS = (PROTOCOL = TCP)(HOST = 0.0.0.0)(PORT = 1521)))
    (DESCRIPTION =
      (ADDRESS = (PROTOCOL = TCPS)(HOST = 0.0.0.0)(PORT = 2484)))
  )

WALLET_LOCATION = (SOURCE = (METHOD = FILE) (METHOD_DATA = (DIRECTORY = ${WALLET_DIR})))
SSL_CLIENT_AUTHENTICATION = TRUE
EOF

# Stop and start listener to pick up TCPS configuration
lsnrctl stop
lsnrctl start

# Force immediate service re-registration with the new listener
echo "ALTER SYSTEM REGISTER;" | sqlplus -S / as sysdba

# Configure keycloak user for SSL certificate-based authentication (no password).
# The DN must match the client certificate's subject exactly.
sqlplus -S / as sysdba <<'EOSQL'
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER USER keycloak IDENTIFIED EXTERNALLY AS 'CN=keycloak,O=SPIRE,C=US';
EOSQL

# Now apply the full sqlnet.ora with SSL client authentication and TCPS auth services
cat > ${ORACLE_HOME}/network/admin/sqlnet.ora <<EOF
WALLET_LOCATION = (SOURCE = (METHOD = FILE) (METHOD_DATA = (DIRECTORY = ${WALLET_DIR})))
SSL_CLIENT_AUTHENTICATION = TRUE
SQLNET.AUTHENTICATION_SERVICES = (TCPS,NTS)
SSL_VERSION = 1.2
EOF

echo "Oracle TLS setup complete. TCPS listener available on port 2484."
