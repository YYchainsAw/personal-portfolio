package xyz.yychainsaw.portfolio.auth.cli;

import java.util.regex.Pattern;

public record TotpKeyReencryptionResult(
        boolean changed,
        int previousKeyVersion,
        int activeKeyVersion,
        String backupSha256) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public TotpKeyReencryptionResult {
        if (previousKeyVersion < 1 || activeKeyVersion < 1) {
            throw new IllegalArgumentException("TOTP key versions must be positive");
        }
        if (changed) {
            if (previousKeyVersion == activeKeyVersion
                    || backupSha256 == null
                    || !SHA_256.matcher(backupSha256).matches()) {
                throw new IllegalArgumentException("invalid changed TOTP key re-encryption result");
            }
        } else if (previousKeyVersion != activeKeyVersion || backupSha256 != null) {
            throw new IllegalArgumentException("invalid unchanged TOTP key re-encryption result");
        }
    }

    @Override
    public String toString() {
        return "TotpKeyReencryptionResult[changed=" + changed
                + ", previousKeyVersion=" + previousKeyVersion
                + ", activeKeyVersion=" + activeKeyVersion
                + ", backupSha256=" + backupSha256 + ']';
    }
}
