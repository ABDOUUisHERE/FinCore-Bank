#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$PGUSER" --dbname postgres \
  --set=runtime="$REPORTING_DB_PASSWORD" --set=migrator="$REPORTING_MIGRATION_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE reporting_runtime LOGIN PASSWORD %L', :'runtime') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='reporting_runtime') \gexec
SELECT format('CREATE ROLE reporting_migrator LOGIN PASSWORD %L', :'migrator') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='reporting_migrator') \gexec
SELECT 'CREATE DATABASE reporting OWNER reporting_migrator' WHERE NOT EXISTS(SELECT FROM pg_database WHERE datname='reporting') \gexec
REVOKE ALL ON DATABASE reporting FROM PUBLIC;
GRANT CONNECT ON DATABASE reporting TO reporting_runtime;
SQL
