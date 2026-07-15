package xyz.yychainsaw.portfolio.auth.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class TotpEnvelopeCrypto {
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final String AUTHENTICATION_FAILURE = "TOTP ciphertext authentication failed";

    private final int activeVersion;
    private final Map<Integer, SecretKeySpec> keys;
    private final SecureRandom random;

    @Autowired
    public TotpEnvelopeCrypto(TotpProperties properties) {
        this(properties, new SecureRandom());
    }

    TotpEnvelopeCrypto(TotpProperties properties, SecureRandom random) {
        if (properties == null || random == null) {
            throw new IllegalArgumentException("TOTP crypto dependencies are required");
        }
        this.activeVersion = properties.activeKeyVersion();
        this.keys = parse(properties.keyRing());
        this.random = random;
        if (!keys.containsKey(activeVersion)) {
            throw new IllegalArgumentException("active TOTP key version is absent from key ring");
        }
    }

    public int activeKeyVersion() {
        return activeVersion;
    }

    public EncryptedTotpSecret encrypt(UUID adminId, String plaintext) {
        requireAdminId(adminId);
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("TOTP plaintext is required");
        }
        byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            return encryptBytes(adminId, activeVersion, plaintextBytes);
        } finally {
            Arrays.fill(plaintextBytes, (byte) 0);
        }
    }

    public String decrypt(UUID adminId, EncryptedTotpSecret encrypted) {
        requireAdminId(adminId);
        if (encrypted == null) {
            throw new IllegalArgumentException("encrypted TOTP secret is required");
        }
        byte[] plaintextBytes = decryptBytes(adminId, encrypted);
        try {
            return strictUtf8(plaintextBytes);
        } finally {
            Arrays.fill(plaintextBytes, (byte) 0);
        }
    }

    public EncryptedTotpSecret reencrypt(UUID adminId, EncryptedTotpSecret encrypted) {
        requireAdminId(adminId);
        if (encrypted == null) {
            throw new IllegalArgumentException("encrypted TOTP secret is required");
        }
        byte[] plaintextBytes = decryptBytes(adminId, encrypted);
        try {
            return encryptBytes(adminId, activeVersion, plaintextBytes);
        } finally {
            Arrays.fill(plaintextBytes, (byte) 0);
        }
    }

    private EncryptedTotpSecret encryptBytes(UUID adminId, int keyVersion, byte[] plaintextBytes) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    keys.get(keyVersion),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(adminId, keyVersion));
            return new EncryptedTotpSecret(keyVersion, nonce, cipher.doFinal(plaintextBytes));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP encryption failed", exception);
        }
    }

    private byte[] decryptBytes(UUID adminId, EncryptedTotpSecret encrypted) {
        int keyVersion = encrypted.keyVersion();
        SecretKeySpec key = keys.get(keyVersion);
        if (key == null) {
            throw new SecurityException(AUTHENTICATION_FAILURE);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(GCM_TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(aad(adminId, keyVersion));
            return cipher.doFinal(encrypted.ciphertext());
        } catch (AEADBadTagException exception) {
            throw new SecurityException(AUTHENTICATION_FAILURE, exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP decryption failed", exception);
        }
    }

    private static String strictUtf8(byte[] plaintextBytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintextBytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("TOTP secret decoding failed", exception);
        }
    }

    private static byte[] aad(UUID adminId, int keyVersion) {
        return ("portfolio-admin-totp:v1|key=" + keyVersion + "|admin=" + adminId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static Map<Integer, SecretKeySpec> parse(String keyRing) {
        Map<Integer, SecretKeySpec> parsed = new HashMap<>();
        for (String entry : keyRing.split(",", -1)) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2 || !parts[0].matches("[1-9][0-9]*") || parts[1].isEmpty()) {
                throw invalidKeyRing();
            }

            int version;
            try {
                version = Integer.parseInt(parts[0]);
            } catch (NumberFormatException exception) {
                throw invalidKeyRing();
            }
            if (parsed.containsKey(version)) {
                throw invalidKeyRing();
            }

            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(parts[1]);
            } catch (IllegalArgumentException exception) {
                throw invalidKeyRing();
            }
            try {
                if (decoded.length != 32 || containsKeyMaterial(parsed, decoded)) {
                    throw invalidKeyRing();
                }
                parsed.put(version, new SecretKeySpec(decoded, "AES"));
            } finally {
                Arrays.fill(decoded, (byte) 0);
            }
        }
        return Map.copyOf(parsed);
    }

    private static boolean containsKeyMaterial(Map<Integer, SecretKeySpec> parsed, byte[] candidate) {
        for (SecretKeySpec key : parsed.values()) {
            byte[] existing = key.getEncoded();
            try {
                if (MessageDigest.isEqual(existing, candidate)) {
                    return true;
                }
            } finally {
                Arrays.fill(existing, (byte) 0);
            }
        }
        return false;
    }

    private static IllegalArgumentException invalidKeyRing() {
        return new IllegalArgumentException("invalid TOTP key ring");
    }

    private static void requireAdminId(UUID adminId) {
        if (adminId == null) {
            throw new IllegalArgumentException("admin ID is required");
        }
    }
}
