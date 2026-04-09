#!/bin/bash
set -e

# Fix certificate permissions for mssql user (uid 10001)
chmod 600 /opt/spiffe-certs/server.key
chmod 644 /opt/spiffe-certs/server.crt /opt/spiffe-certs/ca.crt
chown 10001:0 /opt/spiffe-certs/server.key /opt/spiffe-certs/server.crt /opt/spiffe-certs/ca.crt

# Configure TLS via mssql.conf
mkdir -p /var/opt/mssql
cat > /var/opt/mssql/mssql.conf <<EOF
[network]
tlscert = /opt/spiffe-certs/server.crt
tlskey = /opt/spiffe-certs/server.key
forceencryption = 1
EOF
chown -R 10001:0 /var/opt/mssql

# Start SQL Server in the background as mssql user
/opt/mssql/bin/sqlservr &
MSSQL_PID=$!

# Wait for SQL Server to be ready
echo "Waiting for SQL Server to start..."
for i in $(seq 1 90); do
    if /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "${MSSQL_SA_PASSWORD}" -C -Q "SELECT 1" > /dev/null 2>&1; then
        break
    fi
    sleep 1
done

# Create keycloak database and login
echo "Creating keycloak database and login..."
/opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "${MSSQL_SA_PASSWORD}" -C -Q "
IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = 'keycloak')
    CREATE DATABASE keycloak;
"

/opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "${MSSQL_SA_PASSWORD}" -C -Q "
IF NOT EXISTS (SELECT name FROM sys.server_principals WHERE name = 'keycloak')
    CREATE LOGIN keycloak WITH PASSWORD = 'Keycloak1!', CHECK_POLICY = OFF;
"

/opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "${MSSQL_SA_PASSWORD}" -C -d keycloak -Q "
IF NOT EXISTS (SELECT name FROM sys.database_principals WHERE name = 'keycloak')
BEGIN
    CREATE USER keycloak FOR LOGIN keycloak;
    ALTER ROLE db_owner ADD MEMBER keycloak;
END
"

echo "Database initialization complete."

# Wait for SQL Server process
wait ${MSSQL_PID}
