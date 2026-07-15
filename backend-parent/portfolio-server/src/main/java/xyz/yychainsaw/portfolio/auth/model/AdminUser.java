package xyz.yychainsaw.portfolio.auth.model;

import java.time.Instant;
import java.util.UUID;
import xyz.yychainsaw.portfolio.auth.crypto.EncryptedTotpSecret;

public record AdminUser(
        UUID id,
        String username,
        String passwordHash,
        AdminStatus status,
        EncryptedTotpSecret totpSecret,
        Instant lastLoginAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public AdminUser {
        require(id != null, "admin id is required");
        require(username != null, "username is required");
        require(username.equals(username.strip()), "username must already be trimmed");
        int usernameLength = username.codePointCount(0, username.length());
        require(usernameLength >= 3 && usernameLength <= 64,
                "username must contain between 3 and 64 code points");
        require(passwordHash != null
                        && !passwordHash.isBlank()
                        && passwordHash.length() <= 255,
                "password hash must contain between 1 and 255 characters");
        require(status != null, "admin status is required");
        require(totpSecret != null, "encrypted TOTP secret is required");
        require(version >= 0, "version must not be negative");
        require(createdAt != null, "created timestamp is required");
        require(updatedAt != null, "updated timestamp is required");
    }

    @Override
    public String toString() {
        return "AdminUser[id=" + id
                + ", username=" + username
                + ", passwordHash=<redacted>"
                + ", status=" + status
                + ", totpSecret=<redacted>"
                + ", lastLoginAt=" + lastLoginAt
                + ", version=" + version
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + ']';
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
