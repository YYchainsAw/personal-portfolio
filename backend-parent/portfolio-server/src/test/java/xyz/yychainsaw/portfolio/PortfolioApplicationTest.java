package xyz.yychainsaw.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.mock.env.MockEnvironment;

class PortfolioApplicationTest {
    @Test
    void selectsServletWebApplicationTypeByDefault() {
        assertThat(PortfolioApplication.webApplicationType(new String[0]))
                .isEqualTo(WebApplicationType.SERVLET);
    }

    @Test
    void acceptsOnlyKnownEqualsFormCliCommandsBeforeContextCreation() {
        for (String command : List.of(
                "admin-bootstrap", "admin-recover", "totp-reencrypt")) {
            String[] args = {"--portfolio.cli.command=" + command};
            assertThat(PortfolioApplication.webApplicationType(args))
                    .isEqualTo(WebApplicationType.NONE);
        }

        List<ArgumentFailure> failures = List.of(
                new ArgumentFailure(null, "application arguments are required"),
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command"},
                        "portfolio CLI command must be supplied exactly once"),
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command="},
                        "portfolio CLI command must be supplied exactly once"),
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command=   "},
                        "portfolio CLI command must be supplied exactly once"),
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command", "admin-recover"},
                        "portfolio CLI accepts only its command option"),
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command=admin-recover", "positional"},
                        "portfolio CLI accepts only its command option"),
                new ArgumentFailure(
                        new String[] {
                            "--portfolio.cli.command=admin-recover",
                            "--spring.flyway.enabled=true"
                        },
                        "portfolio CLI accepts only its command option"),
                new ArgumentFailure(
                        new String[] {
                            "--portfolio.cli.command=admin-recover",
                            "--portfolio.cli.command=totp-reencrypt"
                        },
                        "portfolio CLI command must be supplied exactly once"),
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command=unknown"},
                        "unknown portfolio CLI command"),
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command==admin-recover"},
                        "unknown portfolio CLI command"));
        for (ArgumentFailure failure : failures) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PortfolioApplication.webApplicationType(failure.arguments()))
                    .withMessage(failure.message());
        }

        assertThat(PortfolioApplication.webApplicationType(new String[] {
                    "--spring.profiles.active=development", "ordinary-positional"
                }))
                .isEqualTo(WebApplicationType.SERVLET);
    }

    @Test
    void disablesFlywayOnlyForBackupGatedMaintenanceAtHighestPrecedence() {
        assertThat(PortfolioApplication.disablesFlyway(
                        new String[] {"--portfolio.cli.command=admin-bootstrap"}))
                .isFalse();
        assertThat(PortfolioApplication.disablesFlyway(
                        new String[] {"--portfolio.cli.command=admin-recover"}))
                .isTrue();
        assertThat(PortfolioApplication.disablesFlyway(
                        new String[] {"--portfolio.cli.command=totp-reencrypt"}))
                .isTrue();

        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.flyway.enabled", "true");
        PortfolioApplication.applyMaintenanceFlywayGuard(environment);

        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(environment.getPropertySources().iterator().next().getName())
                .isEqualTo("portfolioMaintenanceFlywayGuard");
    }

    private record ArgumentFailure(String[] arguments, String message) {}
}
