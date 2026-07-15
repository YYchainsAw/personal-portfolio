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

BEGIN;

SELECT pg_catalog.set_config(
           'portfolio.bootstrap.migrator_role', :'migrator_user', true
       ) IS NOT NULL AS migrator_role_stored;
SELECT pg_catalog.set_config(
           'portfolio.bootstrap.runtime_role', :'runtime_user', true
       ) IS NOT NULL AS runtime_role_stored;

SELECT pg_catalog.format('CREATE ROLE %I', :'migrator_user')
WHERE NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'migrator_user'
) \gexec
SELECT pg_catalog.format(
    'ALTER ROLE %I LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
    :'migrator_user', :'migrator_password'
) \gexec

SELECT pg_catalog.format('CREATE ROLE %I', :'runtime_user')
WHERE NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = :'runtime_user'
) \gexec
SELECT pg_catalog.format(
    'ALTER ROLE %I LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
    :'runtime_user', :'runtime_password'
) \gexec

SELECT pg_catalog.format('CREATE ROLE %I', 'portfolio_runtime_access')
WHERE NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'portfolio_runtime_access'
) \gexec
SELECT pg_catalog.format(
    'ALTER ROLE %I NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS',
    'portfolio_runtime_access'
) \gexec

DO $bootstrap_topology$
DECLARE
    migrator_name pg_catalog.text := pg_catalog.current_setting(
        'portfolio.bootstrap.migrator_role', false
    );
    runtime_name pg_catalog.text := pg_catalog.current_setting(
        'portfolio.bootstrap.runtime_role', false
    );
    capability_name pg_catalog.text := 'portfolio_runtime_access';
    migrator_oid pg_catalog.oid;
    runtime_oid pg_catalog.oid;
    capability_oid pg_catalog.oid;
    expected_edge_count pg_catalog.int8;
BEGIN
    SELECT bootstrap_role.oid INTO migrator_oid
    FROM pg_catalog.pg_roles AS bootstrap_role
    WHERE bootstrap_role.rolname = migrator_name;

    SELECT bootstrap_role.oid INTO runtime_oid
    FROM pg_catalog.pg_roles AS bootstrap_role
    WHERE bootstrap_role.rolname = runtime_name;

    SELECT bootstrap_role.oid INTO capability_oid
    FROM pg_catalog.pg_roles AS bootstrap_role
    WHERE bootstrap_role.rolname = capability_name;

    IF migrator_oid IS NULL OR runtime_oid IS NULL OR capability_oid IS NULL THEN
        RAISE EXCEPTION 'portfolio bootstrap roles must exist before topology validation'
            USING ERRCODE = '55000';
    END IF;

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

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_shdepend AS dependency
        WHERE dependency.refclassid = 'pg_catalog.pg_authid'::pg_catalog.regclass
          AND dependency.refobjid = runtime_oid
          AND dependency.deptype = 'o'
    ) THEN
        RAISE EXCEPTION 'portfolio runtime must not own database objects'
            USING ERRCODE = '55000';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_auth_members AS membership
        WHERE membership.member = capability_oid
    ) THEN
        RAISE EXCEPTION 'portfolio_runtime_access must not be a member of another role'
            USING ERRCODE = '55000';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_auth_members AS membership
        WHERE membership.member = migrator_oid
    ) THEN
        RAISE EXCEPTION 'portfolio migrator must not have role memberships'
            USING ERRCODE = '55000';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_auth_members AS membership
        WHERE membership.member = runtime_oid
          AND membership.roleid <> capability_oid
    ) THEN
        RAISE EXCEPTION 'portfolio runtime has an unexpected role membership'
            USING ERRCODE = '55000';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_auth_members AS membership
        WHERE membership.roleid = capability_oid
          AND membership.member <> runtime_oid
    ) THEN
        RAISE EXCEPTION 'portfolio_runtime_access has an unexpected member'
            USING ERRCODE = '55000';
    END IF;

    SELECT pg_catalog.count(*) INTO expected_edge_count
    FROM pg_catalog.pg_auth_members AS membership
    WHERE membership.roleid = capability_oid
      AND membership.member = runtime_oid;

    IF expected_edge_count > 1 THEN
        RAISE EXCEPTION 'portfolio runtime capability membership must be unique'
            USING ERRCODE = '55000';
    END IF;

    IF expected_edge_count = 1 AND EXISTS (
        SELECT 1
        FROM pg_catalog.pg_auth_members AS membership
        WHERE membership.roleid = capability_oid
          AND membership.member = runtime_oid
          AND (
              membership.admin_option IS DISTINCT FROM false
              OR membership.inherit_option IS DISTINCT FROM true
              OR membership.set_option IS DISTINCT FROM false
          )
    ) THEN
        RAISE EXCEPTION 'existing portfolio runtime capability membership options are invalid'
            USING ERRCODE = '55000';
    END IF;
END;
$bootstrap_topology$;

SELECT pg_catalog.format(
    'GRANT %I TO %I WITH ADMIN FALSE, INHERIT TRUE, SET FALSE',
    'portfolio_runtime_access', :'runtime_user'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_auth_members AS membership
    JOIN pg_catalog.pg_roles AS capability_role
      ON capability_role.oid = membership.roleid
    JOIN pg_catalog.pg_roles AS runtime_role
      ON runtime_role.oid = membership.member
    WHERE capability_role.rolname = 'portfolio_runtime_access'
      AND runtime_role.rolname = :'runtime_user'
) \gexec

DO $bootstrap_membership$
DECLARE
    runtime_name pg_catalog.text := pg_catalog.current_setting(
        'portfolio.bootstrap.runtime_role', false
    );
    capability_name pg_catalog.text := 'portfolio_runtime_access';
    runtime_oid pg_catalog.oid;
    capability_oid pg_catalog.oid;
    expected_edge_count pg_catalog.int8;
BEGIN
    SELECT bootstrap_role.oid INTO runtime_oid
    FROM pg_catalog.pg_roles AS bootstrap_role
    WHERE bootstrap_role.rolname = runtime_name;

    SELECT bootstrap_role.oid INTO capability_oid
    FROM pg_catalog.pg_roles AS bootstrap_role
    WHERE bootstrap_role.rolname = capability_name;

    SELECT pg_catalog.count(*) INTO expected_edge_count
    FROM pg_catalog.pg_auth_members AS membership
    WHERE membership.roleid = capability_oid
      AND membership.member = runtime_oid;

    IF expected_edge_count <> 1 OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_auth_members AS membership
        WHERE membership.roleid = capability_oid
          AND membership.member = runtime_oid
          AND (
              membership.admin_option IS DISTINCT FROM false
              OR membership.inherit_option IS DISTINCT FROM true
              OR membership.set_option IS DISTINCT FROM false
          )
    ) THEN
        RAISE EXCEPTION 'portfolio runtime capability membership convergence failed'
            USING ERRCODE = '55000';
    END IF;

    IF pg_catalog.pg_has_role(runtime_oid, capability_oid, 'USAGE') IS DISTINCT FROM true
       OR pg_catalog.pg_has_role(runtime_oid, capability_oid, 'SET') IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'portfolio runtime capability effective access is invalid'
            USING ERRCODE = '42501';
    END IF;
END;
$bootstrap_membership$;

SELECT pg_catalog.format(
    'REVOKE CONNECT, TEMPORARY ON DATABASE %I FROM PUBLIC',
    pg_catalog.current_database()
) \gexec
SELECT pg_catalog.format(
    'REVOKE CREATE, TEMPORARY ON DATABASE %I FROM %I',
    pg_catalog.current_database(), :'runtime_user'
) \gexec
SELECT pg_catalog.format(
    'REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE %I FROM %I',
    pg_catalog.current_database(), 'portfolio_runtime_access'
) \gexec
SELECT pg_catalog.format(
    'REVOKE TEMPORARY ON DATABASE %I FROM %I',
    pg_catalog.current_database(), :'migrator_user'
) \gexec

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT pg_catalog.format('REVOKE CREATE ON SCHEMA public FROM %I', :'migrator_user') \gexec
SELECT pg_catalog.format('REVOKE CREATE ON SCHEMA public FROM %I', :'runtime_user') \gexec
SELECT pg_catalog.format(
    'REVOKE CREATE ON SCHEMA public FROM %I', 'portfolio_runtime_access'
) \gexec

SELECT pg_catalog.format('REVOKE CREATE ON SCHEMA portfolio FROM %I', :'runtime_user')
WHERE pg_catalog.to_regnamespace('portfolio') IS NOT NULL \gexec
SELECT pg_catalog.format(
    'REVOKE CREATE ON SCHEMA portfolio FROM %I', 'portfolio_runtime_access'
)
WHERE pg_catalog.to_regnamespace('portfolio') IS NOT NULL \gexec

SELECT pg_catalog.format(
    'GRANT CONNECT, CREATE ON DATABASE %I TO %I',
    pg_catalog.current_database(), :'migrator_user'
) \gexec
SELECT pg_catalog.format(
    'GRANT CONNECT ON DATABASE %I TO %I',
    pg_catalog.current_database(), :'runtime_user'
) \gexec

DO $bootstrap_privileges$
DECLARE
    migrator_name pg_catalog.text := pg_catalog.current_setting(
        'portfolio.bootstrap.migrator_role', false
    );
    runtime_name pg_catalog.text := pg_catalog.current_setting(
        'portfolio.bootstrap.runtime_role', false
    );
    capability_name pg_catalog.text := 'portfolio_runtime_access';
    database_name pg_catalog.text := pg_catalog.current_database();
    migrator_oid pg_catalog.oid;
    runtime_oid pg_catalog.oid;
    capability_oid pg_catalog.oid;
    public_schema_oid pg_catalog.oid;
    portfolio_schema_oid pg_catalog.oid;
BEGIN
    SELECT bootstrap_role.oid INTO migrator_oid
    FROM pg_catalog.pg_roles AS bootstrap_role
    WHERE bootstrap_role.rolname = migrator_name;

    SELECT bootstrap_role.oid INTO runtime_oid
    FROM pg_catalog.pg_roles AS bootstrap_role
    WHERE bootstrap_role.rolname = runtime_name;

    SELECT bootstrap_role.oid INTO capability_oid
    FROM pg_catalog.pg_roles AS bootstrap_role
    WHERE bootstrap_role.rolname = capability_name;

    SELECT namespace.oid INTO public_schema_oid
    FROM pg_catalog.pg_namespace AS namespace
    WHERE namespace.nspname = 'public';

    SELECT namespace.oid INTO portfolio_schema_oid
    FROM pg_catalog.pg_namespace AS namespace
    WHERE namespace.nspname = 'portfolio';

    IF migrator_oid IS NULL OR runtime_oid IS NULL OR capability_oid IS NULL
       OR public_schema_oid IS NULL THEN
        RAISE EXCEPTION 'portfolio bootstrap privilege principals must exist'
            USING ERRCODE = '55000';
    END IF;

    IF pg_catalog.has_database_privilege(runtime_oid, database_name, 'CONNECT')
           IS DISTINCT FROM true
       OR pg_catalog.has_database_privilege(runtime_oid, database_name, 'CREATE')
           IS DISTINCT FROM false
       OR pg_catalog.has_database_privilege(runtime_oid, database_name, 'TEMPORARY')
           IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'portfolio runtime database privileges are invalid'
            USING ERRCODE = '42501';
    END IF;

    IF pg_catalog.has_database_privilege(capability_oid, database_name, 'CONNECT')
           IS DISTINCT FROM false
       OR pg_catalog.has_database_privilege(capability_oid, database_name, 'CREATE')
           IS DISTINCT FROM false
       OR pg_catalog.has_database_privilege(capability_oid, database_name, 'TEMPORARY')
           IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'portfolio runtime capability database privileges are invalid'
            USING ERRCODE = '42501';
    END IF;

    IF pg_catalog.has_database_privilege(migrator_oid, database_name, 'CONNECT')
           IS DISTINCT FROM true
       OR pg_catalog.has_database_privilege(migrator_oid, database_name, 'CREATE')
           IS DISTINCT FROM true
       OR pg_catalog.has_database_privilege(migrator_oid, database_name, 'TEMPORARY')
           IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'portfolio migrator database privileges are invalid'
            USING ERRCODE = '42501';
    END IF;

    IF pg_catalog.has_schema_privilege(migrator_oid, public_schema_oid, 'CREATE')
           IS DISTINCT FROM false
       OR pg_catalog.has_schema_privilege(runtime_oid, public_schema_oid, 'CREATE')
           IS DISTINCT FROM false
       OR pg_catalog.has_schema_privilege(capability_oid, public_schema_oid, 'CREATE')
           IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'portfolio bootstrap roles must not create in public schema'
            USING ERRCODE = '42501';
    END IF;

    IF portfolio_schema_oid IS NOT NULL AND (
        pg_catalog.has_schema_privilege(runtime_oid, portfolio_schema_oid, 'CREATE')
            IS DISTINCT FROM false
        OR pg_catalog.has_schema_privilege(capability_oid, portfolio_schema_oid, 'CREATE')
            IS DISTINCT FROM false
    ) THEN
        RAISE EXCEPTION 'runtime roles must not create in portfolio schema'
            USING ERRCODE = '42501';
    END IF;
END;
$bootstrap_privileges$;

SELECT pg_catalog.format(
    'ALTER ROLE %I SET search_path TO portfolio, public', :'migrator_user'
) \gexec
SELECT pg_catalog.format(
    'ALTER ROLE %I SET search_path TO portfolio, public', :'runtime_user'
) \gexec

COMMIT;
SQL
