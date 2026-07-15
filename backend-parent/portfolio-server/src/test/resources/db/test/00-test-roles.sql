CREATE ROLE portfolio_migrator LOGIN PASSWORD 'migrator_test_password';
CREATE ROLE portfolio_runtime LOGIN PASSWORD 'runtime_test_password';
GRANT CONNECT ON DATABASE portfolio_test TO portfolio_migrator, portfolio_runtime;
GRANT CREATE ON DATABASE portfolio_test TO portfolio_migrator;
ALTER ROLE portfolio_migrator SET search_path TO portfolio, public;
ALTER ROLE portfolio_runtime SET search_path TO portfolio, public;
