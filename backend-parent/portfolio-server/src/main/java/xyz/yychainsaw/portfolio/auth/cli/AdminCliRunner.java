package xyz.yychainsaw.portfolio.auth.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AdminCliRunner implements ApplicationRunner {
    private static final String COMMAND_OPTION = "portfolio.cli.command";
    private static final String COMMAND = "admin-bootstrap";
    private static final String INPUT_CANCELLED = "administrator CLI input was cancelled";

    private final SecretConsole console;
    private final AdminBootstrapService bootstrap;

    public AdminCliRunner(SecretConsole console, AdminBootstrapService bootstrap) {
        if (console == null) {
            throw new IllegalArgumentException("secret console is required");
        }
        if (bootstrap == null) {
            throw new IllegalArgumentException("bootstrap service is required");
        }
        this.console = console;
        this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!arguments.containsOption(COMMAND_OPTION)) {
            return;
        }

        List<String> values = arguments.getOptionValues(COMMAND_OPTION);
        long occurrences = Arrays.stream(arguments.getSourceArgs())
                .filter(AdminCliRunner::isCommandToken)
                .count();
        if (occurrences != 1
                || values == null
                || values.size() != 1
                || values.get(0) == null
                || values.get(0).isBlank()) {
            throw new IllegalArgumentException(
                    "portfolio CLI command must be supplied exactly once");
        }
        if (!arguments.getOptionNames().equals(Set.of(COMMAND_OPTION))
                || !arguments.getNonOptionArgs().isEmpty()) {
            throw new IllegalArgumentException(
                    "portfolio CLI accepts only its command option");
        }
        if (!COMMAND.equals(values.get(0))) {
            throw new IllegalArgumentException("unknown portfolio CLI command");
        }

        runBootstrap();
    }

    private void runBootstrap() {
        String username = requireLine(
                        console.readLine("Administrator username: "))
                .strip();
        char[] password = requireSecret(console.readSecret("New password: "));
        try {
            char[] confirmation = null;
            try {
                confirmation = requireSecret(console.readSecret("Repeat password: "));
                if (!Arrays.equals(password, confirmation)) {
                    throw new IllegalArgumentException("passwords differ");
                }
            } finally {
                wipe(confirmation);
            }

            AdminBootstrapService.Enrollment prepared = bootstrap.prepare(username, password);
            try (AdminBootstrapService.Enrollment enrollment = prepared) {
                console.println("Add this provisioning URI to the authenticator:");
                console.println(enrollment.provisioningUri());

                char[] totpCode = requireSecret(
                        console.readSecret("Current six-digit TOTP: "));
                try {
                    bootstrap.complete(enrollment, totpCode);
                } finally {
                    wipe(totpCode);
                }

                console.println(
                        "Store these one-time recovery codes offline; they will not be shown again:");
                List<String> recoveryCodes = enrollment.takePlaintextRecoveryCodes();
                for (String recoveryCode : recoveryCodes) {
                    console.println(recoveryCode);
                }
            }
        } finally {
            wipe(password);
        }
    }

    private static boolean isCommandToken(String argument) {
        return argument.equals("--" + COMMAND_OPTION)
                || argument.startsWith("--" + COMMAND_OPTION + '=');
    }

    private static String requireLine(String value) {
        if (value == null) {
            throw new IllegalStateException(INPUT_CANCELLED);
        }
        return value;
    }

    private static char[] requireSecret(char[] value) {
        if (value == null) {
            throw new IllegalStateException(INPUT_CANCELLED);
        }
        return value;
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
