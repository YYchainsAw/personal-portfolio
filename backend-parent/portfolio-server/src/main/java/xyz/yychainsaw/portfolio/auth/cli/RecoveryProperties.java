package xyz.yychainsaw.portfolio.auth.cli;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.recovery")
public record RecoveryProperties(
        Path directory,
        String pgDumpBin,
        String username,
        String password,
        Duration timeout) {
    private static final int MAXIMUM_PATH_LENGTH = 4096;
    private static final int MAXIMUM_USERNAME_LENGTH = 128;
    private static final int MAXIMUM_PASSWORD_LENGTH = 4096;
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(10);

    public RecoveryProperties {
        if (directory == null
                || !directory.isAbsolute()
                || !directory.equals(directory.normalize())
                || !validText(directory.toString(), MAXIMUM_PATH_LENGTH, false)) {
            throw new IllegalArgumentException("recovery directory must be an absolute normalized path");
        }
        pgDumpBin = requireText(pgDumpBin, MAXIMUM_PATH_LENGTH, "pg_dump binary is required");
        username = requireText(username, MAXIMUM_USERNAME_LENGTH, "recovery database user is required");
        if (password == null
                || password.isEmpty()
                || password.length() > MAXIMUM_PASSWORD_LENGTH
                || password.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("recovery database password is required");
        }
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException("pg_dump timeout must be positive and at most ten minutes");
        }
    }

    @Override
    public String toString() {
        return "RecoveryProperties[directory=<redacted>, pgDumpBin=<redacted>, username=<redacted>, "
                + "password=<redacted>, timeout=" + timeout + ']';
    }

    private static String requireText(String value, int maximumLength, String message) {
        if (!validText(value, maximumLength, true)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static boolean validText(String value, int maximumLength, boolean requireTrimmedValue) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            return false;
        }
        if (requireTrimmedValue && value.trim().isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
