#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "${PGUSER:-$POSTGRES_USER}" --dbname postgres \
  --set=account_runtime="$ACCOUNT_DB_PASSWORD" --set=account_migrator="$ACCOUNT_MIGRATION_PASSWORD" \
  --set=payment_runtime="$PAYMENT_DB_PASSWORD" --set=payment_migrator="$PAYMENT_MIGRATION_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE account_runtime LOGIN PASSWORD %L', :'account_runtime') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='account_runtime') \gexec
SELECT format('CREATE ROLE account_migrator LOGIN PASSWORD %L', :'account_migrator') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='account_migrator') \gexec
SELECT format('CREATE ROLE payment_runtime LOGIN PASSWORD %L', :'payment_runtime') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='payment_runtime') \gexec
SELECT format('CREATE ROLE payment_migrator LOGIN PASSWORD %L', :'payment_migrator') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='payment_migrator') \gexec
SELECT 'CREATE DATABASE account OWNER account_migrator' WHERE NOT EXISTS(SELECT FROM pg_database WHERE datname='account') \gexec
SELECT 'CREATE DATABASE payment OWNER payment_migrator' WHERE NOT EXISTS(SELECT FROM pg_database WHERE datname='payment') \gexec
REVOKE ALL ON DATABASE account,payment FROM PUBLIC;
GRANT CONNECT ON DATABASE account TO account_runtime;
GRANT CONNECT ON DATABASE payment TO payment_runtime;
SQL
