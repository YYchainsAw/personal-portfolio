CREATE SCHEMA IF NOT EXISTS portfolio AUTHORIZATION CURRENT_USER;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace AS namespace
        WHERE namespace.nspname = 'portfolio'
          AND namespace.nspowner = (
              SELECT migration_role.oid
              FROM pg_catalog.pg_roles AS migration_role
              WHERE migration_role.rolname = CURRENT_USER
          )
    ) THEN
        RAISE EXCEPTION 'portfolio schema must be owned by current migration role'
            USING ERRCODE = '42501';
    END IF;
END;
$$;

REVOKE ALL ON SCHEMA portfolio FROM PUBLIC;
GRANT USAGE ON SCHEMA portfolio TO portfolio_runtime_access;
REVOKE CREATE ON SCHEMA portfolio FROM portfolio_runtime_access;

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA portfolio;

CREATE OR REPLACE FUNCTION portfolio.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = portfolio, pg_temp
AS $$
BEGIN
    NEW.updated_at = clock_timestamp();
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION portfolio.set_updated_at() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION portfolio.set_updated_at() TO portfolio_runtime_access;

ALTER DEFAULT PRIVILEGES
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA portfolio
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA portfolio
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
