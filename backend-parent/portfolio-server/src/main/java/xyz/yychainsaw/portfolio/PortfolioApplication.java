package xyz.yychainsaw.portfolio;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class PortfolioApplication {
    private static final String COMMAND_PREFIX = "--portfolio.cli.command=";
    private static final Set<String> COMMANDS = Set.of(
            "admin-bootstrap", "admin-recover", "totp-reencrypt");
    private static final Set<String> BACKUP_GATED_COMMANDS = Set.of(
            "admin-recover", "totp-reencrypt");
    private static final String FLYWAY_GUARD = "portfolioMaintenanceFlywayGuard";

    public static void main(String[] args) {
        LaunchPlan plan = launchPlan(args);
        boolean cli = plan.webApplicationType() == WebApplicationType.NONE;
        SpringApplication application = new SpringApplication(PortfolioApplication.class);
        application.setWebApplicationType(plan.webApplicationType());
        if (plan.disableFlyway()) {
            application.addInitializers(context ->
                    applyMaintenanceFlywayGuard(context.getEnvironment()));
        }
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
                .filter(argument -> argument != null
                        && (argument.equals("--portfolio.cli.command")
                                || argument.startsWith(COMMAND_PREFIX)))
                .count();
        if (commandTokens == 0) {
            return new LaunchPlan(WebApplicationType.SERVLET, false);
        }
        if (commandTokens != 1) {
            throw new IllegalArgumentException(
                    "portfolio CLI command must be supplied exactly once");
        }
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "portfolio CLI accepts only its command option");
        }
        String token = args[0];
        if (token == null || !token.startsWith(COMMAND_PREFIX)) {
            throw new IllegalArgumentException(
                    "portfolio CLI command must be supplied exactly once");
        }
        String command = token.substring(COMMAND_PREFIX.length());
        if (command.isBlank()) {
            throw new IllegalArgumentException(
                    "portfolio CLI command must be supplied exactly once");
        }
        if (!COMMANDS.contains(command)) {
            throw new IllegalArgumentException("unknown portfolio CLI command");
        }
        return new LaunchPlan(
                WebApplicationType.NONE, BACKUP_GATED_COMMANDS.contains(command));
    }

    private record LaunchPlan(WebApplicationType webApplicationType, boolean disableFlyway) {
    }
}
