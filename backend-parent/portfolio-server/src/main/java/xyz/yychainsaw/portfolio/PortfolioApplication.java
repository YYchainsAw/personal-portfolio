package xyz.yychainsaw.portfolio;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class PortfolioApplication {
    private static final String COMMAND_PREFIX = "--portfolio.cli.command=";
    private static final String IMPORT_COMMAND = "import";
    private static final String IMPORT_OPTION_PREFIX = "--portfolio.import.";
    private static final String IMPORT_ARGUMENTS_INVALID =
            "portfolio import CLI arguments are invalid";
    private static final Set<String> IMPORT_OPTIONS = Set.of(
            "portfolio.import.input",
            "portfolio.import.asset-root",
            "portfolio.import.sha256",
            "portfolio.import.commit");
    private static final Set<String> COMMANDS = Set.of(
            "admin-bootstrap", "admin-recover", "totp-reencrypt");
    private static final Set<String> BACKUP_GATED_COMMANDS = Set.of(
            "admin-recover", "totp-reencrypt");
    private static final String FLYWAY_GUARD = "portfolioMaintenanceFlywayGuard";

    public static void main(String[] args) {
        LaunchPlan plan = launchPlan(args);
        boolean cli = plan.webApplicationType() == WebApplicationType.NONE;
        SpringApplication application = new SpringApplication(PortfolioApplication.class);
        configureApplication(application, plan);
        ConfigurableApplicationContext context = application.run(args);
        if (cli) {
            int exitCode = SpringApplication.exit(context);
            System.exit(exitCode);
        }
    }

    static WebApplicationType webApplicationType(String[] args) {
        return launchPlan(args).webApplicationType();
    }

    static boolean disablesFlyway(String[] args) {
        return launchPlan(args).disableFlyway();
    }

    static void configureApplication(SpringApplication application, String[] args) {
        if (application == null) {
            throw new IllegalArgumentException("Spring application is required");
        }
        configureApplication(application, launchPlan(args));
    }

    private static void configureApplication(
            SpringApplication application, LaunchPlan plan) {
        application.setWebApplicationType(plan.webApplicationType());
        if (plan.disableFlyway()) {
            application.addInitializers(context ->
                    applyMaintenanceFlywayGuard(context.getEnvironment()));
        }
        if (plan.machineReadable()) {
            application.setBannerMode(Banner.Mode.OFF);
            application.setLogStartupInfo(false);
            application.addListeners(new ImportLoggingSilencer());
        }
    }

    static void applyMaintenanceFlywayGuard(ConfigurableEnvironment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment is required");
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
                FLYWAY_GUARD, Map.of("spring.flyway.enabled", "false")));
    }

    private static LaunchPlan launchPlan(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("application arguments are required");
        }
        long commandTokens = Arrays.stream(args)
                .filter(PortfolioApplication::isCommandToken)
                .count();
        if (commandTokens == 0) {
            if (Arrays.stream(args).anyMatch(PortfolioApplication::isImportOptionToken)) {
                throw importArgumentsInvalid();
            }
            return new LaunchPlan(WebApplicationType.SERVLET, false, false);
        }
        if (commandTokens != 1) {
            throw new IllegalArgumentException(
                    "portfolio CLI command must be supplied exactly once");
        }
        String token = Arrays.stream(args)
                .filter(PortfolioApplication::isCommandToken)
                .findFirst()
                .orElseThrow();
        if (token == null || !token.startsWith(COMMAND_PREFIX)) {
            if (args.length != 1) {
                throw new IllegalArgumentException(
                        "portfolio CLI accepts only its command option");
            }
            throw new IllegalArgumentException(
                    "portfolio CLI command must be supplied exactly once");
        }
        String command = token.substring(COMMAND_PREFIX.length());
        if (IMPORT_COMMAND.equals(command)) {
            validateImportArguments(args);
            return new LaunchPlan(WebApplicationType.NONE, false, true);
        }
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "portfolio CLI accepts only its command option");
        }
        if (command.isBlank()) {
            throw new IllegalArgumentException(
                    "portfolio CLI command must be supplied exactly once");
        }
        if (!COMMANDS.contains(command)) {
            throw new IllegalArgumentException("unknown portfolio CLI command");
        }
        return new LaunchPlan(
                WebApplicationType.NONE,
                BACKUP_GATED_COMMANDS.contains(command),
                false);
    }

    private static void validateImportArguments(String[] args) {
        if (args.length != IMPORT_OPTIONS.size() + 1) {
            throw importArgumentsInvalid();
        }
        Map<String, String> values = new HashMap<>();
        for (String argument : args) {
            if (argument == null) {
                throw importArgumentsInvalid();
            }
            if (argument.equals(COMMAND_PREFIX + IMPORT_COMMAND)) {
                continue;
            }
            if (!argument.startsWith("--")) {
                throw importArgumentsInvalid();
            }
            int separator = argument.indexOf('=');
            if (separator <= 2) {
                throw importArgumentsInvalid();
            }
            String name = argument.substring(2, separator);
            String value = argument.substring(separator + 1);
            if (!IMPORT_OPTIONS.contains(name)
                    || values.putIfAbsent(name, value) != null) {
                throw importArgumentsInvalid();
            }
        }
        if (!values.keySet().equals(IMPORT_OPTIONS)
                || values.get("portfolio.import.input").isBlank()
                || values.get("portfolio.import.asset-root").isBlank()
                || !values.get("portfolio.import.sha256").matches("[0-9a-f]{64}")
                || !(values.get("portfolio.import.commit").equals("true")
                        || values.get("portfolio.import.commit").equals("false"))) {
            throw importArgumentsInvalid();
        }
    }

    private static boolean isCommandToken(String argument) {
        return argument != null
                && (argument.equals("--portfolio.cli.command")
                        || argument.startsWith(COMMAND_PREFIX));
    }

    private static boolean isImportOptionToken(String argument) {
        return argument != null && argument.startsWith(IMPORT_OPTION_PREFIX);
    }

    private static IllegalArgumentException importArgumentsInvalid() {
        return new IllegalArgumentException(IMPORT_ARGUMENTS_INVALID);
    }

    private record LaunchPlan(
            WebApplicationType webApplicationType,
            boolean disableFlyway,
            boolean machineReadable) {
    }

    private static final class ImportLoggingSilencer
            implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
        private static final String PROPERTY_SOURCE = "portfolioImportLoggingGuard";

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            event.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    PROPERTY_SOURCE, Map.of("logging.level.root", "OFF")));
            LoggingSystem.get(PortfolioApplication.class.getClassLoader())
                    .setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.OFF);
        }

        @Override
        public int getOrder() {
            return LoggingApplicationListener.DEFAULT_ORDER + 1;
        }
    }
}
