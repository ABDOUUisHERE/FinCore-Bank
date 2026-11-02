#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$PGUSER" --dbname postgres \
  --set=runtime="$AUDIT_DB_PASSWORD" --set=migrator="$AUDIT_MIGRATION_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE audit_runtime LOGIN PASSWORD %L', :'runtime') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='audit_runtime') \gexec
SELECT format('CREATE ROLE audit_migrator LOGIN PASSWORD %L', :'migrator') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='audit_migrator') \gexec
SELECT 'CREATE DATABASE audit OWNER audit_migrator' WHERE NOT EXISTS(SELECT FROM pg_database WHERE datname='audit') \gexec
REVOKE ALL ON DATABASE audit FROM PUBLIC;
GRANT CONNECT ON DATABASE audit TO audit_runtime;
SQL
