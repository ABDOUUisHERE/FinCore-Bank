#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$PGUSER" --dbname postgres \
  --set=runtime="$NOTIFICATION_DB_PASSWORD" --set=migrator="$NOTIFICATION_MIGRATION_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE notification_runtime LOGIN PASSWORD %L', :'runtime') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='notification_runtime') \gexec
SELECT format('CREATE ROLE notification_migrator LOGIN PASSWORD %L', :'migrator') WHERE NOT EXISTS(SELECT FROM pg_roles WHERE rolname='notification_migrator') \gexec
SELECT 'CREATE DATABASE notification OWNER notification_migrator' WHERE NOT EXISTS(SELECT FROM pg_database WHERE datname='notification') \gexec
REVOKE ALL ON DATABASE notification FROM PUBLIC;
GRANT CONNECT ON DATABASE notification TO notification_runtime;
SQL
