package xyz.yychainsaw.portfolio.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.SessionRepository;
import org.springframework.web.context.WebApplicationContext;
import xyz.yychainsaw.portfolio.PortfolioApplication;
import xyz.yychainsaw.portfolio.audit.JdbcAuditService;
import xyz.yychainsaw.portfolio.auth.RecoveryCodeService;
import xyz.yychainsaw.portfolio.auth.cli.AdminBootstrapService;
import xyz.yychainsaw.portfolio.auth.cli.AdminCliRunner;
import xyz.yychainsaw.portfolio.auth.cli.AdminRecoveryService;
import xyz.yychainsaw.portfolio.auth.cli.DatabaseRestorePointService;
import xyz.yychainsaw.portfolio.auth.cli.PgDumpRestorePointService;
import xyz.yychainsaw.portfolio.auth.cli.RecoveryProperties;
import xyz.yychainsaw.portfolio.auth.cli.SecretConsole;
import xyz.yychainsaw.portfolio.auth.cli.TotpKeyReencryptionService;
import xyz.yychainsaw.portfolio.auth.persistence.AdminUserRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionCleanupJob;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionRepository;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.config.SecurityConfiguration;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@Isolated
class AdminAuthenticationNonWebTest extends PostgresIntegrationTestBase {
    @org.junit.jupiter.api.Test
    void explicitNoneContextWithoutCliCommandKeepsCliInfrastructureAndOmitsServletAuth()
            throws Exception {
        SpringApplication application = new SpringApplication(
                PortfolioApplication.class, NonWebTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(contextProperties());

        try (ConfigurableApplicationContext context = application.run()) {
            CountingSecretConsole console = context.getBean(CountingSecretConsole.class);
            ApplicationArguments arguments = context.getBean(ApplicationArguments.class);

            assertThat(arguments.getSourceArgs()).isEmpty();
            assertThat(arguments.containsOption("portfolio.cli.command")).isFalse();
            assertThat(context).isNotInstanceOf(WebApplicationContext.class)
                    .isNotInstanceOf(ServletWebServerApplicationContext.class);

            assertThat(context.getBeansOfType(AdminAuthenticationService.class)).isEmpty();
            assertThat(context.getBeansOfType(AdminSecuritySettingsService.class)).isEmpty();
            assertThat(context.getBeansOfType(AdminAuthController.class)).isEmpty();
            assertThat(context.getBeansOfType(AdminSecurityController.class)).isEmpty();
            assertThat(context.getBeansOfType(AdminSecuritySettingsController.class)).isEmpty();
            assertThat(context.getBeansOfType(SecurityCurrentAdminProvider.class)).isEmpty();
            assertThat(context.getBeansOfType(LoginSubjectHasher.class)).isEmpty();
            assertThat(context.getBeansOfType(SecurityProblemWriter.class)).isEmpty();
            assertThat(context.getBeansOfType(SessionMetadataEnforcementFilter.class)).isEmpty();
            assertThat(context.getBeansOfType(SessionPersistenceConcurrencyFilter.class)).isEmpty();
            assertThat(context.getBeansOfType(SecurityConfiguration.class)).isEmpty();
            assertThat(context.getBeansOfType(SessionRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(AdminSessionCleanupJob.class)).isEmpty();

            assertThat(context.getBean(AdminCliRunner.class)).isNotNull();
            assertThat(context.getBean(AdminBootstrapService.class)).isNotNull();
            assertThat(context.getBean(AdminRecoveryService.class)).isNotNull();
            assertThat(context.getBean(TotpKeyReencryptionService.class)).isNotNull();
            assertThat(context.getBean(DatabaseRestorePointService.class))
                    .isInstanceOf(PgDumpRestorePointService.class);
            assertThat(context.getBean(RecoveryProperties.class)).isNotNull();
            assertThat(context.getBean(AdminUserRepository.class)).isNotNull();
            assertThat(context.getBean(RecoveryCodeRepository.class)).isNotNull();
            assertThat(context.getBean(RecoveryCodeService.class)).isNotNull();
            assertThat(context.getBean(AdminSessionRepository.class)).isNotNull();
            assertThat(context.getBean(AdminSessionService.class)).isNotNull();
            assertThat(context.getBean(JdbcAuditService.class)).isNotNull();
            assertThat(context.getBean(JdbcTemplate.class)).isNotNull();

            assertThat(console.interactions()).isZero();
        }
    }

    private static Map<String, Object> contextProperties() {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        String databaseUrl = POSTGRES.getJdbcUrl() + separator + "currentSchema=portfolio";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("PORTFOLIO_DB_URL", databaseUrl);
        properties.put("PORTFOLIO_DB_MIGRATOR_URL", databaseUrl);
        properties.put("PORTFOLIO_DB_RUNTIME_USER", "test_runtime");
        properties.put("PORTFOLIO_DB_RUNTIME_PASSWORD", "runtime_test_password");
        properties.put("PORTFOLIO_DB_MIGRATOR_USER", "test_migrator");
        properties.put("PORTFOLIO_DB_MIGRATOR_PASSWORD", "migrator_test_password");
        properties.put("PORTFOLIO_TOTP_ACTIVE_KEY_VERSION", "1");
        properties.put(
                "PORTFOLIO_TOTP_KEY_RING",
                "1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        properties.put("PORTFOLIO_SESSION_COOKIE_SECURE", "false");
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.datasource.url", databaseUrl);
        properties.put("spring.datasource.username", "test_runtime");
        properties.put("spring.datasource.password", "runtime_test_password");
        properties.put("spring.flyway.url", databaseUrl);
        properties.put("spring.flyway.user", "test_migrator");
        properties.put("spring.flyway.password", "migrator_test_password");
        properties.put("spring.session.jdbc.cleanup-cron", "-");
        properties.put("portfolio.security.session.cleanup-interval", "PT24H");
        properties.put("portfolio.security.totp.active-key-version", "1");
        properties.put(
                "portfolio.security.totp.key-ring",
                "1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        properties.put("server.servlet.session.cookie.secure", "false");
        return Map.copyOf(properties);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NonWebTestConfiguration {
        @Bean
        @Primary
        CountingSecretConsole countingSecretConsole() {
            return new CountingSecretConsole();
        }
    }

    static final class CountingSecretConsole implements SecretConsole {
        private final AtomicInteger interactions = new AtomicInteger();

        @Override
        public String readLine(String prompt) {
            interactions.incrementAndGet();
            throw new AssertionError("NONE context unexpectedly requested console input");
        }

        @Override
        public char[] readSecret(String prompt) {
            interactions.incrementAndGet();
            throw new AssertionError("NONE context unexpectedly requested a secret");
        }

        @Override
        public void println(String value) {
            interactions.incrementAndGet();
            throw new AssertionError("NONE context unexpectedly wrote to the console");
        }

        int interactions() {
            return interactions.get();
        }
    }
}
