package xyz.yychainsaw.portfolio.auth.crypto;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.exceptions.TimeProviderException;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class TotpService {
    public static final String PROVISIONING_URI_PREFIX = "otpauth" + "://totp/";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final TotpProperties properties;
    private final TotpEnvelopeCrypto crypto;
    private final SecretGenerator secrets;
    private final CodeGenerator codes;
    private final TimeProvider time;

    public TotpService(
            TotpProperties properties,
            TotpEnvelopeCrypto crypto,
            SecretGenerator secrets,
            CodeGenerator codes,
            TimeProvider time) {
        if (properties == null || crypto == null || secrets == null || codes == null || time == null) {
            throw new IllegalArgumentException("TOTP service dependencies are required");
        }
        this.properties = properties;
        this.crypto = crypto;
        this.secrets = secrets;
        this.codes = codes;
        this.time = time;
    }

    public Enrollment beginEnrollment(UUID adminId, String username) {
        if (adminId == null) {
            throw new IllegalArgumentException("admin ID is required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        String secret = secrets.generate();
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("generated TOTP secret is required");
        }

        String encodedIssuer = percentEncode(properties.issuer());
        String uri = PROVISIONING_URI_PREFIX + encodedIssuer + ':' + percentEncode(username)
                + "?secret=" + percentEncode(secret)
                + "&issuer=" + encodedIssuer
                + "&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(secret, crypto.encrypt(adminId, secret), uri);
    }

    public boolean verifyEnrollment(String plaintextSecret, String code) {
        if (!isWellFormedCode(code)) {
            return false;
        }
        if (plaintextSecret == null || plaintextSecret.isBlank()) {
            throw new IllegalArgumentException("TOTP plaintext is required");
        }
        return verifySecret(plaintextSecret, code);
    }

    public boolean verify(UUID adminId, EncryptedTotpSecret encrypted, String code) {
        if (!isWellFormedCode(code)) {
            return false;
        }
        return verifySecret(crypto.decrypt(adminId, encrypted), code);
    }

    private boolean verifySecret(String secret, String code) {
        long counter;
        try {
            counter = time.getTime() / 30;
        } catch (TimeProviderException ignored) {
            throw new IllegalStateException("TOTP time lookup failed");
        }

        byte[] submitted = code.getBytes(StandardCharsets.US_ASCII);
        boolean matched = false;
        try {
            for (long offset = -1; offset <= 1; offset++) {
                byte[] candidate = asciiCode(codes.generate(secret, counter + offset));
                try {
                    matched |= MessageDigest.isEqual(candidate, submitted);
                } finally {
                    Arrays.fill(candidate, (byte) 0);
                }
            }
            return matched;
        } catch (CodeGenerationException ignored) {
            throw new IllegalStateException("TOTP generation failed");
        } finally {
            Arrays.fill(submitted, (byte) 0);
        }
    }

    private static byte[] asciiCode(String generated) {
        if (!isWellFormedCode(generated)) {
            throw new IllegalStateException("TOTP generation failed");
        }
        return generated.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean isWellFormedCode(String code) {
        if (code == null || code.length() != 6) {
            return false;
        }
        for (int index = 0; index < code.length(); index++) {
            char value = code.charAt(index);
            if (value < '0' || value > '9') {
                return false;
            }
        }
        return true;
    }

    private static String percentEncode(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        try {
            StringBuilder encoded = new StringBuilder(utf8.length * 3);
            for (byte item : utf8) {
                int octet = item & 0xff;
                if (isUnreserved(octet)) {
                    encoded.append((char) octet);
                } else {
                    encoded.append('%').append(HEX[octet >>> 4]).append(HEX[octet & 0x0f]);
                }
            }
            return encoded.toString();
        } finally {
            Arrays.fill(utf8, (byte) 0);
        }
    }

    private static boolean isUnreserved(int value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '-'
                || value == '.'
                || value == '_'
                || value == '~';
    }

    public record Enrollment(
            String plaintextSecret,
            EncryptedTotpSecret encryptedSecret,
            String provisioningUri) {
        @Override
        public String toString() {
            return "Enrollment[plaintextSecret=<redacted>, encryptedSecret=<redacted>, "
                    + "provisioningUri=<redacted>]";
        }
    }
}
