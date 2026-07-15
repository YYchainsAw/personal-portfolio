#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${PORTFOLIO_DB_MIGRATOR_USER:?PORTFOLIO_DB_MIGRATOR_USER is required}"
: "${PORTFOLIO_DB_MIGRATOR_PASSWORD:?PORTFOLIO_DB_MIGRATOR_PASSWORD is required}"
: "${PORTFOLIO_DB_RUNTIME_USER:?PORTFOLIO_DB_RUNTIME_USER is required}"
: "${PORTFOLIO_DB_RUNTIME_PASSWORD:?PORTFOLIO_DB_RUNTIME_PASSWORD is required}"

readonly PORTFOLIO_RUNTIME_ACCESS_ROLE='portfolio_runtime_access'

if [[ "$POSTGRES_USER" == "$PORTFOLIO_DB_MIGRATOR_USER" ||
      "$POSTGRES_USER" == "$PORTFOLIO_DB_RUNTIME_USER" ||
      "$PORTFOLIO_DB_MIGRATOR_USER" == "$PORTFOLIO_DB_RUNTIME_USER" ]]; then
  printf '%s\n' 'POSTGRES_USER, PORTFOLIO_DB_MIGRATOR_USER, and PORTFOLIO_DB_RUNTIME_USER must be pairwise distinct' >&2
  exit 1
fi

if [[ "$POSTGRES_USER" == "$PORTFOLIO_RUNTIME_ACCESS_ROLE" ||
      "$PORTFOLIO_DB_MIGRATOR_USER" == "$PORTFOLIO_RUNTIME_ACCESS_ROLE" ||
      "$PORTFOLIO_DB_RUNTIME_USER" == "$PORTFOLIO_RUNTIME_ACCESS_ROLE" ]]; then
  printf '%s\n' 'owner, migrator, and runtime login names must not use reserved role portfolio_runtime_access' >&2
  exit 1
fi

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" <<'SQL'
\getenv migrator_user PORTFOLIO_DB_MIGRATOR_USER
\getenv migrator_password PORTFOLIO_DB_MIGRATOR_PASSWORD
\getenv runtime_user PORTFOLIO_DB_RUNTIME_USER
\getenv runtime_password PORTFOLIO_DB_RUNTIME_PASSWORD

SELECT format('CREATE ROLE %I', :'migrator_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migrator_user') \gexec
SELECT format('ALTER ROLE %I LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'migrator_user', :'migrator_password') \gexec

SELECT format('CREATE ROLE %I', :'runtime_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_user') \gexec
SELECT format('ALTER ROLE %I LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'runtime_user', :'runtime_password') \gexec

SELECT format('CREATE ROLE %I', 'portfolio_runtime_access')
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'portfolio_runtime_access') \gexec
SELECT format('ALTER ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', 'portfolio_runtime_access') \gexec

DO $$
DECLARE
    capability_oid pg_catalog.oid;
BEGIN
    SELECT capability_role.oid INTO capability_oid
    FROM pg_catalog.pg_roles AS capability_role
    WHERE capability_role.rolname = 'portfolio_runtime_access';

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_shdepend AS dependency
        WHERE dependency.refclassid = 'pg_catalog.pg_authid'::pg_catalog.regclass
          AND dependency.refobjid = capability_oid
          AND dependency.deptype = 'o'
    ) THEN
        RAISE EXCEPTION 'portfolio_runtime_access must not own database objects'
            USING ERRCODE = '55000';
    END IF;
END;
$$;

SELECT format('GRANT %I TO %I WITH ADMIN FALSE, INHERIT TRUE, SET FALSE', 'portfolio_runtime_access', :'runtime_user') \gexec
SELECT format('REVOKE CONNECT, TEMPORARY ON DATABASE %I FROM PUBLIC', current_database()) \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('GRANT CONNECT, CREATE ON DATABASE %I TO %I', current_database(), :'migrator_user') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'runtime_user') \gexec
SELECT format('ALTER ROLE %I SET search_path TO portfolio, public', :'migrator_user') \gexec
SELECT format('ALTER ROLE %I SET search_path TO portfolio, public', :'runtime_user') \gexec
SQL
