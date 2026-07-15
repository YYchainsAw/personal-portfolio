package xyz.yychainsaw.portfolio.auth.crypto;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

public record EncryptedTotpSecret(int keyVersion, byte[] nonce, byte[] ciphertext)
        implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public EncryptedTotpSecret {
        if (keyVersion < 1
                || nonce == null
                || nonce.length != 12
                || ciphertext == null
                || ciphertext.length < 17) {
            throw new IllegalArgumentException("invalid encrypted TOTP secret");
        }
        nonce = Arrays.copyOf(nonce, nonce.length);
        ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
    }

    @Override
    public byte[] nonce() {
        return Arrays.copyOf(nonce, nonce.length);
    }

    @Override
    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }

    @Override
    public boolean equals(Object candidate) {
        return this == candidate
                || candidate instanceof EncryptedTotpSecret other
                        && keyVersion == other.keyVersion
                        && Arrays.equals(nonce, other.nonce)
                        && Arrays.equals(ciphertext, other.ciphertext);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(keyVersion);
        result = 31 * result + Arrays.hashCode(nonce);
        return 31 * result + Arrays.hashCode(ciphertext);
    }

    @Override
    public String toString() {
        return "EncryptedTotpSecret[keyVersion=" + keyVersion
                + ", nonce=<redacted>, ciphertext=<redacted>]";
    }
}
