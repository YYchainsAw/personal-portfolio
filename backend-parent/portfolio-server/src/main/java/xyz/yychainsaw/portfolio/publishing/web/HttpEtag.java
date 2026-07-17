package xyz.yychainsaw.portfolio.publishing.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

public final class HttpEtag {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private HttpEtag() {
    }

    public static String api(String revisionChecksum, LocaleCode locale) {
        if (revisionChecksum == null || !SHA256.matcher(revisionChecksum).matches()) {
            throw new IllegalArgumentException("revision checksum is invalid");
        }
        String wireLocale = Objects.requireNonNull(locale, "locale is required").value();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (revisionChecksum + '\n' + wireLocale)
                            .getBytes(StandardCharsets.UTF_8));
            return '"' + HexFormat.of().formatHex(digest) + '"';
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
