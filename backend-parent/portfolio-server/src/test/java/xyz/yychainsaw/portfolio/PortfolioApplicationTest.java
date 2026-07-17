package xyz.yychainsaw.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.importer.ImportIssue;
import xyz.yychainsaw.portfolio.content.importer.ImportReport;
import xyz.yychainsaw.portfolio.content.importer.PortfolioImportCli;
import xyz.yychainsaw.portfolio.content.importer.PortfolioImportService;

@ExtendWith(OutputCaptureExtension.class)
class PortfolioApplicationTest {
    private static final String IMPORT_SHA256 = "a".repeat(64);
    private static final String IMPORT_INPUT = "runtime/import/portfolio-v1.json";
    private static final String IMPORT_ASSET_ROOT = "frontend/public";
    private static final ObjectMapper JSON = new ObjectMapper();

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
    void acceptsOnlyTheExactImportOptionSetInAnyOrderBeforeContextCreation() {
        String[] arguments = importArguments();

        assertThat(PortfolioApplication.webApplicationType(arguments))
                .isEqualTo(WebApplicationType.NONE);
        assertThat(PortfolioApplication.disablesFlyway(arguments)).isFalse();

        String[] reordered = {
            "--portfolio.import.commit=false",
            "--portfolio.import.asset-root=frontend/public",
            "--portfolio.cli.command=import",
            "--portfolio.import.sha256=" + IMPORT_SHA256,
            "--portfolio.import.input=runtime/import/portfolio-v1.json"
        };
        assertThat(PortfolioApplication.webApplicationType(reordered))
                .isEqualTo(WebApplicationType.NONE);
        assertThat(PortfolioApplication.webApplicationType(replace(
                        arguments,
                        "--portfolio.import.commit=false",
                        "--portfolio.import.commit=true")))
                .isEqualTo(WebApplicationType.NONE);
    }

    @Test
    void rejectsEveryMalformedImportInvocationBeforeContextCreation() {
        List<ArgumentFailure> failures = List.of(
                new ArgumentFailure(
                        new String[] {"--portfolio.cli.command=import"},
                        "portfolio import CLI arguments are invalid"),
                new ArgumentFailure(
                        new String[] {
                            "--portfolio.cli.command=import",
                            "--portfolio.import.input=runtime/import/portfolio-v1.json",
                            "--portfolio.import.asset-root=frontend/public",
                            "--portfolio.import.sha256=" + IMPORT_SHA256
                        },
                        "portfolio import CLI arguments are invalid"),
                new ArgumentFailure(
                        append(importArguments(), "--portfolio.import.commit=true"),
                        "portfolio import CLI arguments are invalid"),
                new ArgumentFailure(
                        replace(
                                importArguments(),
                                "--portfolio.import.commit=false",
                                "--portfolio.import.input=another.json"),
                        "portfolio import CLI arguments are invalid"),
                new ArgumentFailure(
                        replace(
                                importArguments(),
                                "--portfolio.import.input=runtime/import/portfolio-v1.json",
                                "--portfolio.import.input"),
                        "portfolio import CLI arguments are invalid"),
                new ArgumentFailure(
                        replace(
                                importArguments(),
                                "--portfolio.import.input=runtime/import/portfolio-v1.json",
                                "--portfolio.import.input="),
                        "portfolio import CLI arguments are invalid"),
                new ArgumentFailure(
                        replace(
                                importArguments(),
                                "--portfolio.import.sha256=" + IMPORT_SHA256,
                                "--portfolio.import.sha256=" + IMPORT_SHA256.toUpperCase()),
                        "portfolio import CLI arguments are invalid"),
                new ArgumentFailure(
                        replace(
                                importArguments(),
                                "--portfolio.import.commit=false",
                                "--portfolio.import.commit=FALSE"),
                        "portfolio import CLI arguments are invalid"),
                new ArgumentFailure(
                        replace(
                                importArguments(),
                                "--portfolio.import.commit=false",
                                "--portfolio.import.unknown=false"),
                        "portfolio import CLI arguments are invalid"),
                new ArgumentFailure(
                        append(importArguments(), "positional"),
                        "portfolio import CLI arguments are invalid"),
                new ArgumentFailure(
                        append(importArguments(), "--spring.main.web-application-type=none"),
                        "portfolio import CLI arguments are invalid"),
                new ArgumentFailure(
                        new String[] {
                            "--portfolio.import.input=runtime/import/portfolio-v1.json",
                            "--portfolio.import.asset-root=frontend/public",
                            "--portfolio.import.sha256=" + IMPORT_SHA256,
                            "--portfolio.import.commit=false"
                        },
                        "portfolio import CLI arguments are invalid"));

        for (ArgumentFailure failure : failures) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PortfolioApplication.webApplicationType(failure.arguments()))
                    .withMessage(failure.message())
                    .withNoCause();
        }
    }

    @Test
    void configuresOnlyTheImportLaunchForMachineReadableOutput() {
        SpringApplication importApplication =
                new SpringApplication(PortfolioApplication.class);

        PortfolioApplication.configureApplication(importApplication, importArguments());

        assertThat(importApplication.getWebApplicationType())
                .isEqualTo(WebApplicationType.NONE);
        Object importProperties = ReflectionTestUtils.getField(
                importApplication, "properties");
        assertThat(ReflectionTestUtils.getField(importProperties, "bannerMode"))
                .isEqualTo(Banner.Mode.OFF);
        assertThat(ReflectionTestUtils.getField(importProperties, "logStartupInfo"))
                .isEqualTo(false);
        ApplicationListener<?> silencer = importApplication.getListeners().stream()
                .filter(listener -> listener.getClass().getSimpleName()
                        .equals("ImportLoggingSilencer"))
                .findFirst()
                .orElseThrow();
        assertThat(silencer).isInstanceOf(Ordered.class);
        assertThat(((Ordered) silencer).getOrder())
                .isGreaterThan(LoggingApplicationListener.DEFAULT_ORDER);

        SpringApplication maintenanceApplication =
                new SpringApplication(PortfolioApplication.class);
        PortfolioApplication.configureApplication(
                maintenanceApplication,
                new String[] {"--portfolio.cli.command=admin-bootstrap"});
        assertThat(maintenanceApplication.getListeners())
                .noneMatch(listener -> listener.getClass().getSimpleName()
                        .equals("ImportLoggingSilencer"));
    }

    @Test
    void importCliPrintsOneCompactWarningReportAndReturnsZero(CapturedOutput output)
            throws Exception {
        PortfolioImportService service = mock(PortfolioImportService.class);
        ImportReport report = new ImportReport(
                IMPORT_SHA256,
                false,
                3,
                4,
                8,
                List.of(new ImportIssue(
                        ImportIssue.Severity.PUBLISH_WARNING,
                        "identity.email",
                        "PLACEHOLDER_CONTENT_PRESENT",
                        "Replace the placeholder before publication")));
        when(service.dryRun(
                        Path.of(IMPORT_INPUT),
                        Path.of(IMPORT_ASSET_ROOT),
                        IMPORT_SHA256))
                .thenReturn(report);
        PortfolioImportCli cli = importCli(service, false);

        cli.run(null);

        assertThat(output.getOut())
                .isEqualTo(JSON.writeValueAsString(report) + System.lineSeparator());
        assertThat(cli.getExitCode()).isZero();
        verify(service).dryRun(
                Path.of(IMPORT_INPUT), Path.of(IMPORT_ASSET_ROOT), IMPORT_SHA256);
    }

    @Test
    void importCliReturnsTwoForAReportedStructureError(CapturedOutput output)
            throws Exception {
        PortfolioImportService service = mock(PortfolioImportService.class);
        ImportReport report = new ImportReport(
                IMPORT_SHA256,
                false,
                0,
                0,
                0,
                List.of(new ImportIssue(
                        ImportIssue.Severity.STRUCTURE_ERROR,
                        "$",
                        "IMPORT_JSON_INVALID",
                        "Portfolio import JSON is invalid")));
        when(service.dryRun(
                        Path.of(IMPORT_INPUT),
                        Path.of(IMPORT_ASSET_ROOT),
                        IMPORT_SHA256))
                .thenReturn(report);
        PortfolioImportCli cli = importCli(service, false);

        cli.run(null);

        assertThat(output.getOut())
                .isEqualTo(JSON.writeValueAsString(report) + System.lineSeparator());
        assertThat(cli.getExitCode()).isEqualTo(2);
    }

    @Test
    void importCliReturnsThreeAndPrintsNoConflictDetail(CapturedOutput output)
            throws Exception {
        PortfolioImportService service = mock(PortfolioImportService.class);
        when(service.commit(
                        Path.of(IMPORT_INPUT),
                        Path.of(IMPORT_ASSET_ROOT),
                        IMPORT_SHA256))
                .thenThrow(new DomainException(
                        "IMPORT_ALREADY_COMPLETED", HttpStatus.CONFLICT, Map.of()));
        PortfolioImportCli cli = importCli(service, true);
        ImportReport safeConflict = new ImportReport(
                IMPORT_SHA256, false, 0, 0, 0, List.of());

        cli.run(null);

        assertThat(output.getOut())
                .isEqualTo(JSON.writeValueAsString(safeConflict) + System.lineSeparator())
                .doesNotContain("IMPORT_ALREADY_COMPLETED")
                .doesNotContain(IMPORT_INPUT)
                .doesNotContain(IMPORT_ASSET_ROOT);
        assertThat(cli.getExitCode()).isEqualTo(3);
        verify(service).commit(
                Path.of(IMPORT_INPUT), Path.of(IMPORT_ASSET_ROOT), IMPORT_SHA256);
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
        assertThat(PortfolioApplication.disablesFlyway(importArguments())).isFalse();

        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.flyway.enabled", "true");
        PortfolioApplication.applyMaintenanceFlywayGuard(environment);

        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(environment.getPropertySources().iterator().next().getName())
                .isEqualTo("portfolioMaintenanceFlywayGuard");
    }

    private static String[] importArguments() {
        return new String[] {
            "--portfolio.cli.command=import",
            "--portfolio.import.input=" + IMPORT_INPUT,
            "--portfolio.import.asset-root=" + IMPORT_ASSET_ROOT,
            "--portfolio.import.sha256=" + IMPORT_SHA256,
            "--portfolio.import.commit=false"
        };
    }

    private static PortfolioImportCli importCli(
            PortfolioImportService service, boolean commit) {
        return new PortfolioImportCli(
                service,
                JSON.copy().enable(SerializationFeature.INDENT_OUTPUT),
                IMPORT_INPUT,
                IMPORT_ASSET_ROOT,
                IMPORT_SHA256,
                commit);
    }

    private static String[] append(String[] values, String value) {
        String[] result = java.util.Arrays.copyOf(values, values.length + 1);
        result[values.length] = value;
        return result;
    }

    private static String[] replace(String[] values, String expected, String replacement) {
        String[] result = values.clone();
        for (int index = 0; index < result.length; index++) {
            if (result[index].equals(expected)) {
                result[index] = replacement;
                return result;
            }
        }
        throw new AssertionError("test argument was not found");
    }

    private record ArgumentFailure(String[] arguments, String message) {}
}
