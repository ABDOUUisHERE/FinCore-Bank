#!/usr/bin/env bash
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  --set=runtime_password="$CUSTOMER_DB_PASSWORD" \
  --set=migration_password="$CUSTOMER_MIGRATION_PASSWORD" \
  --set=identity_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE customer_runtime LOGIN PASSWORD :'runtime_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE customer_migrator LOGIN PASSWORD :'migration_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE keycloak_owner LOGIN PASSWORD :'identity_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE customer OWNER customer_migrator;
CREATE DATABASE keycloak OWNER keycloak_owner;
REVOKE ALL ON DATABASE customer FROM PUBLIC;
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT ON DATABASE customer TO customer_runtime;
\connect customer
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO customer_runtime;
SQL
