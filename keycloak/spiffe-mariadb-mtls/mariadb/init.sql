-- Drop the password-authenticated user created by MARIADB_USER and recreate
-- with certificate-only authentication (no password, X.509 required)
DROP USER IF EXISTS 'keycloak'@'%';
CREATE USER 'keycloak'@'%' REQUIRE X509;
GRANT ALL PRIVILEGES ON keycloak.* TO 'keycloak'@'%';
