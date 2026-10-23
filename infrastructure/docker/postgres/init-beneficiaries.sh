#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$PGUSER" --dbname postgres \
  --set=runtime="$BENEFICIARY_DB_PASSWORD" --set=migrator="$BENEFICIARY_MIGRATION_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE beneficiary_runtime LOGIN PASSWORD %L', :'runtime') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='beneficiary_runtime') \gexec
SELECT format('CREATE ROLE beneficiary_migrator LOGIN PASSWORD %L', :'migrator') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='beneficiary_migrator') \gexec
SELECT 'CREATE DATABASE beneficiary OWNER beneficiary_migrator' WHERE NOT EXISTS(SELECT FROM pg_database WHERE datname='beneficiary') \gexec
REVOKE ALL ON DATABASE beneficiary FROM PUBLIC;
GRANT CONNECT ON DATABASE beneficiary TO beneficiary_runtime;
SQL
