package xyz.yychainsaw.portfolio.support;

import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("integration")
@ActiveProfiles("test")
public abstract class PostgresIntegrationTestBase {
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-bookworm")
                    .withDatabaseName("portfolio_test")
                    .withUsername("test_owner")
                    .withPassword("test_owner_password")
                    .withInitScript("db/test/00-test-roles.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = POSTGRES.getJdbcUrl() + "?currentSchema=portfolio";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "portfolio_runtime");
        registry.add("spring.datasource.password", () -> "runtime_test_password");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> "portfolio_migrator");
        registry.add("spring.flyway.password", () -> "migrator_test_password");
        registry.add("portfolio.recovery.host", POSTGRES::getHost);
        registry.add("portfolio.recovery.port", POSTGRES::getFirstMappedPort);
        registry.add("portfolio.recovery.database", POSTGRES::getDatabaseName);
        registry.add("portfolio.recovery.username", () -> "portfolio_migrator");
        registry.add("portfolio.recovery.password", () -> "migrator_test_password");
        registry.add("portfolio.security.totp.active-key-version", () -> "1");
        registry.add("portfolio.security.totp.key-ring",
                () -> "1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        registry.add("server.servlet.session.cookie.secure", () -> "false");
    }

    protected static DriverManagerDataSource migratorDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl() + "?currentSchema=portfolio");
        dataSource.setUsername("portfolio_migrator");
        dataSource.setPassword("migrator_test_password");
        return dataSource;
    }

    protected static JdbcClient migratorJdbc() {
        return JdbcClient.create(migratorDataSource());
    }
}
