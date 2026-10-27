#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$PGUSER" --dbname postgres \
  --set=runtime="$FRAUD_DB_PASSWORD" --set=migrator="$FRAUD_MIGRATION_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE fraud_runtime LOGIN PASSWORD %L', :'runtime') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='fraud_runtime') \gexec
SELECT format('CREATE ROLE fraud_migrator LOGIN PASSWORD %L', :'migrator') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='fraud_migrator') \gexec
SELECT 'CREATE DATABASE fraud OWNER fraud_migrator' WHERE NOT EXISTS(SELECT FROM pg_database WHERE datname='fraud') \gexec
REVOKE ALL ON DATABASE fraud FROM PUBLIC;
GRANT CONNECT ON DATABASE fraud TO fraud_runtime;
SQL
