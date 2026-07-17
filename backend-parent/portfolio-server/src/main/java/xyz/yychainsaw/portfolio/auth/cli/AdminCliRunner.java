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
    private static final String BOOTSTRAP_COMMAND = "admin-bootstrap";
    private static final String RECOVERY_COMMAND = "admin-recover";
    private static final String REENCRYPT_COMMAND = "totp-reencrypt";
    private static final String IMPORT_COMMAND = "import";
    private static final String INPUT_CANCELLED = "administrator CLI input was cancelled";
    private static final String RECOVERY_CONFIRMATION = "RECOVER ADMIN";
    private static final String REENCRYPT_CONFIRMATION = "REENCRYPT TOTP KEY";

    private final SecretConsole console;
    private final AdminBootstrapService bootstrap;
    private final AdminRecoveryService recovery;
    private final TotpKeyReencryptionService reencrypt;

    public AdminCliRunner(
            SecretConsole console,
            AdminBootstrapService bootstrap,
            AdminRecoveryService recovery,
            TotpKeyReencryptionService reencrypt) {
        if (console == null) {
            throw new IllegalArgumentException("secret console is required");
        }
        if (bootstrap == null) {
            throw new IllegalArgumentException("bootstrap service is required");
        }
        if (recovery == null) {
            throw new IllegalArgumentException("recovery service is required");
        }
        if (reencrypt == null) {
            throw new IllegalArgumentException("TOTP re-encryption service is required");
        }
        this.console = console;
        this.bootstrap = bootstrap;
        this.recovery = recovery;
        this.reencrypt = reencrypt;
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
        if (IMPORT_COMMAND.equals(values.get(0))) {
            return;
        }
        if (!arguments.getOptionNames().equals(Set.of(COMMAND_OPTION))
                || !arguments.getNonOptionArgs().isEmpty()) {
            throw new IllegalArgumentException(
                    "portfolio CLI accepts only its command option");
        }
        switch (values.get(0)) {
            case BOOTSTRAP_COMMAND -> runBootstrap();
            case RECOVERY_COMMAND -> runRecovery();
            case REENCRYPT_COMMAND -> runReencryption();
            default -> throw new IllegalArgumentException("unknown portfolio CLI command");
        }
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

    private void runRecovery() {
        String confirmation = requireLine(console.readLine(
                "Type RECOVER ADMIN to create a dump and replace administrator credentials: "));
        if (!RECOVERY_CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("administrator recovery was not confirmed");
        }

        char[] password = requireSecret(console.readSecret("New password: "));
        try {
            char[] repeated = null;
            try {
                repeated = requireSecret(console.readSecret("Repeat password: "));
                if (!Arrays.equals(password, repeated)) {
                    throw new IllegalArgumentException("passwords differ");
                }
            } finally {
                wipe(repeated);
            }

            AdminRecoveryService.Enrollment prepared = recovery.prepare(password);
            try (AdminRecoveryService.Enrollment enrollment = prepared) {
                console.println(
                        "Database restore point SHA-256: " + enrollment.backupSha256());
                console.println("Add this provisioning URI to the authenticator:");
                console.println(enrollment.provisioningUri());

                char[] totpCode = requireSecret(
                        console.readSecret("Current six-digit TOTP: "));
                try {
                    recovery.complete(enrollment, totpCode);
                } finally {
                    wipe(totpCode);
                }

                console.println(
                        "Store these one-time recovery codes offline; they will not be shown again:");
                for (String recoveryCode : enrollment.takePlaintextRecoveryCodes()) {
                    console.println(recoveryCode);
                }
            }
        } finally {
            wipe(password);
        }
    }

    private void runReencryption() {
        String confirmation = requireLine(console.readLine(
                "Type REENCRYPT TOTP KEY to create a dump and re-encrypt the TOTP key: "));
        if (!REENCRYPT_CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("TOTP key re-encryption was not confirmed");
        }

        TotpKeyReencryptionResult result = reencrypt.reencryptToActiveKey();
        if (result.changed()) {
            console.println("TOTP key re-encryption completed.");
        } else {
            console.println("TOTP key already uses the active encryption key.");
        }
        console.println("TOTP key version: " + result.previousKeyVersion()
                + " -> " + result.activeKeyVersion());
        if (result.changed()) {
            console.println("Database restore point SHA-256: " + result.backupSha256());
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
