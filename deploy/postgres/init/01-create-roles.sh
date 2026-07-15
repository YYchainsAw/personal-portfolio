#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${PORTFOLIO_DB_MIGRATOR_USER:?PORTFOLIO_DB_MIGRATOR_USER is required}"
: "${PORTFOLIO_DB_MIGRATOR_PASSWORD:?PORTFOLIO_DB_MIGRATOR_PASSWORD is required}"
: "${PORTFOLIO_DB_RUNTIME_USER:?PORTFOLIO_DB_RUNTIME_USER is required}"
: "${PORTFOLIO_DB_RUNTIME_PASSWORD:?PORTFOLIO_DB_RUNTIME_PASSWORD is required}"

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=migrator_user="$PORTFOLIO_DB_MIGRATOR_USER" \
  --set=migrator_password="$PORTFOLIO_DB_MIGRATOR_PASSWORD" \
  --set=runtime_user="$PORTFOLIO_DB_RUNTIME_USER" \
  --set=runtime_password="$PORTFOLIO_DB_RUNTIME_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'migrator_user', :'migrator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migrator_user') \gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'runtime_user', :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_user') \gexec

SELECT format('GRANT CONNECT, CREATE ON DATABASE %I TO %I', current_database(), :'migrator_user') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'runtime_user') \gexec
SELECT format('ALTER ROLE %I SET search_path TO portfolio, public', :'migrator_user') \gexec
SELECT format('ALTER ROLE %I SET search_path TO portfolio, public', :'runtime_user') \gexec
SQL
