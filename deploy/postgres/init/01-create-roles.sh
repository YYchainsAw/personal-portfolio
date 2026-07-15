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

DO $bootstrap_topology_pre$
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

    SELECT pg_catalog.count(*) INTO expected_edge_count
    FROM pg_catalog.pg_auth_members AS membership
    WHERE membership.roleid = capability_oid
      AND membership.member = runtime_oid;

    IF expected_edge_count > 1 THEN
        RAISE EXCEPTION 'portfolio runtime capability membership must be unique'
            USING ERRCODE = '55000';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_auth_members AS membership
        WHERE (
                membership.roleid IN (capability_oid, runtime_oid, migrator_oid)
                OR membership.member IN (capability_oid, runtime_oid, migrator_oid)
              )
          AND NOT (
              membership.roleid = capability_oid
              AND membership.member = runtime_oid
              AND membership.admin_option IS NOT DISTINCT FROM false
              AND membership.inherit_option IS NOT DISTINCT FROM true
              AND membership.set_option IS NOT DISTINCT FROM false
          )
    ) THEN
        RAISE EXCEPTION 'portfolio protected-role membership topology is invalid'
            USING ERRCODE = '55000';
    END IF;
END;
$bootstrap_topology_pre$;

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

DO $bootstrap_schema_precondition$
BEGIN
    IF pg_catalog.to_regnamespace('public') IS NULL THEN
        RAISE EXCEPTION 'public schema must exist before privilege convergence'
            USING ERRCODE = '55000';
    END IF;
END;
$bootstrap_schema_precondition$;

SELECT pg_catalog.format(
    'REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE %I FROM PUBLIC CASCADE',
    pg_catalog.current_database()
) \gexec
SELECT pg_catalog.format(
    'REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE %I FROM %I CASCADE',
    pg_catalog.current_database(), 'portfolio_runtime_access'
) \gexec
SELECT pg_catalog.format(
    'REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE %I FROM %I CASCADE',
    pg_catalog.current_database(), :'runtime_user'
) \gexec
SELECT pg_catalog.format(
    'REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE %I FROM %I CASCADE',
    pg_catalog.current_database(), :'migrator_user'
) \gexec

SELECT pg_catalog.format(
    'REVOKE CREATE ON SCHEMA %I FROM PUBLIC CASCADE', namespace.nspname
)
FROM pg_catalog.pg_namespace AS namespace
WHERE namespace.nspname !~ '^pg_'
  AND namespace.nspname <> 'information_schema'
ORDER BY namespace.nspname \gexec

SELECT pg_catalog.format(
    'REVOKE ALL ON SCHEMA %I FROM %I CASCADE',
    namespace.nspname, 'portfolio_runtime_access'
)
FROM pg_catalog.pg_namespace AS namespace
WHERE namespace.nspname !~ '^pg_'
  AND namespace.nspname <> 'information_schema'
ORDER BY namespace.nspname \gexec

SELECT pg_catalog.format(
    'REVOKE ALL ON SCHEMA %I FROM %I CASCADE',
    namespace.nspname, :'runtime_user'
)
FROM pg_catalog.pg_namespace AS namespace
WHERE namespace.nspname !~ '^pg_'
  AND namespace.nspname <> 'information_schema'
ORDER BY namespace.nspname \gexec

SELECT pg_catalog.format(
    'REVOKE CREATE ON SCHEMA %I FROM %I CASCADE',
    'public', :'migrator_user'
) \gexec

SELECT pg_catalog.format(
    'GRANT USAGE ON SCHEMA %I TO %I',
    namespace.nspname, 'portfolio_runtime_access'
)
FROM pg_catalog.pg_namespace AS namespace
WHERE namespace.nspname = 'portfolio' \gexec

SELECT pg_catalog.format(
    'GRANT CONNECT, CREATE ON DATABASE %I TO %I',
    pg_catalog.current_database(), :'migrator_user'
) \gexec
SELECT pg_catalog.format(
    'GRANT CONNECT ON DATABASE %I TO %I',
    pg_catalog.current_database(), :'runtime_user'
) \gexec

SELECT pg_catalog.format('ALTER ROLE %I RESET ALL', :'migrator_user') \gexec
SELECT pg_catalog.format('ALTER ROLE %I RESET ALL', :'runtime_user') \gexec

SELECT pg_catalog.format(
    'ALTER ROLE %I IN DATABASE %I RESET ALL',
    configured_role.rolname, configured_database.datname
)
FROM pg_catalog.pg_db_role_setting AS role_setting
JOIN pg_catalog.pg_roles AS configured_role
  ON configured_role.oid = role_setting.setrole
JOIN pg_catalog.pg_database AS configured_database
  ON configured_database.oid = role_setting.setdatabase
WHERE role_setting.setdatabase <> 0
  AND configured_role.rolname IN (:'migrator_user', :'runtime_user')
ORDER BY configured_role.rolname, configured_database.datname \gexec

SELECT pg_catalog.format(
    'ALTER ROLE %I SET search_path TO portfolio, public', :'migrator_user'
) \gexec
SELECT pg_catalog.format(
    'ALTER ROLE %I SET search_path TO portfolio, public', :'runtime_user'
) \gexec

DO $bootstrap_postconditions$
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
    database_oid pg_catalog.oid;
    public_schema_oid pg_catalog.oid;
    portfolio_schema_oid pg_catalog.oid;
    incident_edge_count pg_catalog.int8;
    expected_edge_count pg_catalog.int8;
    protected_database_acl_count pg_catalog.int8;
    capability_schema_acl_count pg_catalog.int8;
    migrator_setting_rows pg_catalog.int8;
    runtime_setting_rows pg_catalog.int8;
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

    SELECT target_database.oid INTO database_oid
    FROM pg_catalog.pg_database AS target_database
    WHERE target_database.datname = database_name;

    SELECT namespace.oid INTO public_schema_oid
    FROM pg_catalog.pg_namespace AS namespace
    WHERE namespace.nspname = 'public';

    SELECT namespace.oid INTO portfolio_schema_oid
    FROM pg_catalog.pg_namespace AS namespace
    WHERE namespace.nspname = 'portfolio';

    IF migrator_oid IS NULL OR runtime_oid IS NULL OR capability_oid IS NULL
       OR database_oid IS NULL OR public_schema_oid IS NULL THEN
        RAISE EXCEPTION 'portfolio bootstrap privilege principals must exist'
            USING ERRCODE = '55000';
    END IF;

    SELECT pg_catalog.count(*) INTO incident_edge_count
    FROM pg_catalog.pg_auth_members AS membership
    WHERE membership.roleid IN (capability_oid, runtime_oid, migrator_oid)
       OR membership.member IN (capability_oid, runtime_oid, migrator_oid);

    SELECT pg_catalog.count(*) INTO expected_edge_count
    FROM pg_catalog.pg_auth_members AS membership
    WHERE membership.roleid = capability_oid
      AND membership.member = runtime_oid;

    IF incident_edge_count <> 1 OR expected_edge_count <> 1 OR EXISTS (
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
        RAISE EXCEPTION 'portfolio protected-role membership convergence failed'
            USING ERRCODE = '55000';
    END IF;

    IF pg_catalog.pg_has_role(runtime_oid, capability_oid, 'USAGE') IS DISTINCT FROM true
       OR pg_catalog.pg_has_role(runtime_oid, capability_oid, 'SET') IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'portfolio runtime capability effective access is invalid'
            USING ERRCODE = '42501';
    END IF;

    SELECT pg_catalog.count(*) INTO protected_database_acl_count
    FROM pg_catalog.pg_database AS target_database
    CROSS JOIN LATERAL pg_catalog.aclexplode(target_database.datacl) AS privilege
    WHERE target_database.oid = database_oid
      AND (
          privilege.grantee = 0
          OR privilege.grantee IN (capability_oid, runtime_oid, migrator_oid)
      )
      AND privilege.privilege_type IN ('CONNECT', 'CREATE', 'TEMPORARY');

    IF protected_database_acl_count <> 3 OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_database AS target_database
        CROSS JOIN LATERAL pg_catalog.aclexplode(target_database.datacl) AS privilege
        WHERE target_database.oid = database_oid
          AND (
              privilege.grantee = 0
              OR privilege.grantee IN (capability_oid, runtime_oid, migrator_oid)
          )
          AND privilege.privilege_type IN ('CONNECT', 'CREATE', 'TEMPORARY')
          AND NOT (
              (
                  privilege.grantee = runtime_oid
                  AND privilege.privilege_type = 'CONNECT'
                  AND privilege.is_grantable IS NOT DISTINCT FROM false
              )
              OR (
                  privilege.grantee = migrator_oid
                  AND privilege.privilege_type IN ('CONNECT', 'CREATE')
                  AND privilege.is_grantable IS NOT DISTINCT FROM false
              )
          )
    ) THEN
        RAISE EXCEPTION 'portfolio direct database ACL convergence failed'
            USING ERRCODE = '42501';
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

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace AS namespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(namespace.nspacl) AS privilege
        WHERE namespace.nspname !~ '^pg_'
          AND namespace.nspname <> 'information_schema'
          AND privilege.grantee = 0
          AND privilege.privilege_type = 'CREATE'
    ) THEN
        RAISE EXCEPTION 'PUBLIC retains direct CREATE on a user schema'
            USING ERRCODE = '42501';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace AS namespace
        CROSS JOIN LATERAL pg_catalog.aclexplode(namespace.nspacl) AS privilege
        WHERE namespace.nspname !~ '^pg_'
          AND namespace.nspname <> 'information_schema'
          AND privilege.grantee = runtime_oid
    ) THEN
        RAISE EXCEPTION 'portfolio runtime retains a direct user-schema ACL'
            USING ERRCODE = '42501';
    END IF;

    SELECT pg_catalog.count(*) INTO capability_schema_acl_count
    FROM pg_catalog.pg_namespace AS namespace
    CROSS JOIN LATERAL pg_catalog.aclexplode(namespace.nspacl) AS privilege
    WHERE namespace.nspname !~ '^pg_'
      AND namespace.nspname <> 'information_schema'
      AND privilege.grantee = capability_oid;

    IF capability_schema_acl_count <>
           (CASE WHEN portfolio_schema_oid IS NULL THEN 0 ELSE 1 END)
       OR EXISTS (
            SELECT 1
            FROM pg_catalog.pg_namespace AS namespace
            CROSS JOIN LATERAL pg_catalog.aclexplode(namespace.nspacl) AS privilege
            WHERE namespace.nspname !~ '^pg_'
              AND namespace.nspname <> 'information_schema'
              AND privilege.grantee = capability_oid
              AND NOT (
                  portfolio_schema_oid IS NOT NULL
                  AND namespace.oid = portfolio_schema_oid
                  AND privilege.privilege_type = 'USAGE'
                  AND privilege.is_grantable IS NOT DISTINCT FROM false
              )
       ) THEN
        RAISE EXCEPTION 'portfolio capability direct schema ACL convergence failed'
            USING ERRCODE = '42501';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace AS namespace
        WHERE namespace.nspname !~ '^pg_'
          AND namespace.nspname <> 'information_schema'
          AND (
              pg_catalog.has_schema_privilege(runtime_oid, namespace.oid, 'CREATE')
                  IS DISTINCT FROM false
              OR pg_catalog.has_schema_privilege(capability_oid, namespace.oid, 'CREATE')
                  IS DISTINCT FROM false
          )
    ) THEN
        RAISE EXCEPTION 'runtime roles can create in a user schema'
            USING ERRCODE = '42501';
    END IF;

    IF pg_catalog.has_schema_privilege(migrator_oid, public_schema_oid, 'CREATE')
           IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'portfolio migrator can create in public schema'
            USING ERRCODE = '42501';
    END IF;

    IF portfolio_schema_oid IS NOT NULL AND (
        pg_catalog.has_schema_privilege(capability_oid, portfolio_schema_oid, 'USAGE')
            IS DISTINCT FROM true
        OR pg_catalog.has_schema_privilege(runtime_oid, portfolio_schema_oid, 'USAGE')
            IS DISTINCT FROM true
    ) THEN
        RAISE EXCEPTION 'runtime roles lack portfolio schema USAGE'
            USING ERRCODE = '42501';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles AS bootstrap_role
        WHERE bootstrap_role.oid = migrator_oid
          AND bootstrap_role.rolcanlogin
          AND bootstrap_role.rolinherit
          AND NOT bootstrap_role.rolsuper
          AND NOT bootstrap_role.rolcreatedb
          AND NOT bootstrap_role.rolcreaterole
          AND NOT bootstrap_role.rolreplication
          AND NOT bootstrap_role.rolbypassrls
    ) OR NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles AS bootstrap_role
        WHERE bootstrap_role.oid = runtime_oid
          AND bootstrap_role.rolcanlogin
          AND bootstrap_role.rolinherit
          AND NOT bootstrap_role.rolsuper
          AND NOT bootstrap_role.rolcreatedb
          AND NOT bootstrap_role.rolcreaterole
          AND NOT bootstrap_role.rolreplication
          AND NOT bootstrap_role.rolbypassrls
    ) OR NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles AS bootstrap_role
        WHERE bootstrap_role.oid = capability_oid
          AND NOT bootstrap_role.rolcanlogin
          AND NOT bootstrap_role.rolinherit
          AND NOT bootstrap_role.rolsuper
          AND NOT bootstrap_role.rolcreatedb
          AND NOT bootstrap_role.rolcreaterole
          AND NOT bootstrap_role.rolreplication
          AND NOT bootstrap_role.rolbypassrls
    ) THEN
        RAISE EXCEPTION 'portfolio bootstrap role attributes are invalid'
            USING ERRCODE = '55000';
    END IF;

    SELECT pg_catalog.count(*) INTO migrator_setting_rows
    FROM pg_catalog.pg_db_role_setting AS role_setting
    WHERE role_setting.setrole = migrator_oid;

    SELECT pg_catalog.count(*) INTO runtime_setting_rows
    FROM pg_catalog.pg_db_role_setting AS role_setting
    WHERE role_setting.setrole = runtime_oid;

    IF migrator_setting_rows <> 1 OR runtime_setting_rows <> 1 OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_db_role_setting AS role_setting
        WHERE role_setting.setrole IN (migrator_oid, runtime_oid)
          AND (
              role_setting.setdatabase <> 0
              OR pg_catalog.cardinality(role_setting.setconfig) IS DISTINCT FROM 1
              OR (role_setting.setconfig)[1]
                  IS DISTINCT FROM 'search_path=portfolio, public'
          )
    ) THEN
        RAISE EXCEPTION 'portfolio login role settings convergence failed'
            USING ERRCODE = '55000';
    END IF;
END;
$bootstrap_postconditions$;

COMMIT;
SQL
