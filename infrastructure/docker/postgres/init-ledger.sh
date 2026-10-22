#!/bin/sh
set -eu
# Separate, repeatable bootstrap supports an existing Phase 1 volume. No data reset.
psql -v ON_ERROR_STOP=1 --username "$PGUSER" --dbname postgres \
  --set=runtime_password="$LEDGER_DB_PASSWORD" --set=migration_password="$LEDGER_MIGRATION_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE ledger_runtime LOGIN PASSWORD %L', :'runtime_password') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ledger_runtime') \gexec
SELECT format('CREATE ROLE ledger_migrator LOGIN PASSWORD %L', :'migration_password') WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ledger_migrator') \gexec
SELECT 'CREATE DATABASE ledger OWNER ledger_migrator' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='ledger') \gexec
REVOKE ALL ON DATABASE ledger FROM PUBLIC;
GRANT CONNECT ON DATABASE ledger TO ledger_runtime;
SQL
