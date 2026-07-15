CREATE SCHEMA IF NOT EXISTS portfolio AUTHORIZATION portfolio_migrator;
REVOKE ALL ON SCHEMA portfolio FROM PUBLIC;
GRANT USAGE ON SCHEMA portfolio TO portfolio_runtime;

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
GRANT EXECUTE ON FUNCTION portfolio.set_updated_at() TO portfolio_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE portfolio_migrator IN SCHEMA portfolio
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE portfolio_migrator IN SCHEMA portfolio
    REVOKE ALL ON FUNCTIONS FROM PUBLIC;
