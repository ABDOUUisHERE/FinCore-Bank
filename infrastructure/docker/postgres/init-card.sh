#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$PGUSER" --dbname postgres \
  --set=runtime="$CARD_DB_PASSWORD" --set=migrator="$CARD_MIGRATION_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE card_runtime LOGIN PASSWORD %L', :'runtime') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='card_runtime') \gexec
SELECT format('CREATE ROLE card_migrator LOGIN PASSWORD %L', :'migrator') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='card_migrator') \gexec
SELECT 'CREATE DATABASE card OWNER card_migrator' WHERE NOT EXISTS(SELECT FROM pg_database WHERE datname='card') \gexec
REVOKE ALL ON DATABASE card FROM PUBLIC;
GRANT CONNECT ON DATABASE card TO card_runtime;
SQL
