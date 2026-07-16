package xyz.yychainsaw.portfolio.api.admin.media;

import java.net.URI;

/** Shared structural validation for optional media attribution URLs. */
public final class StrictHttpsSourceUrl {
    private static final int MAXIMUM_LENGTH = 2048;

    private StrictHttpsSourceUrl() { }

    public static String requireValidNullable(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > MAXIMUM_LENGTH || !value.equals(value.trim())) {
            throw invalid();
        }

        URI parsed;
        try {
            parsed = URI.create(value);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }

        String authority = parsed.getRawAuthority();
        String host = parsed.getHost();
        int port = parsed.getPort();
        if (!"https".equalsIgnoreCase(parsed.getScheme())
                || parsed.isOpaque()
                || authority == null
                || authority.isBlank()
                || host == null
                || host.isBlank()
                || parsed.getRawUserInfo() != null
                || parsed.getRawFragment() != null
                || port == 0
                || port > 65535
                || (port == -1 && authority.endsWith(":"))) {
            throw invalid();
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("media source URL is invalid");
    }
}
