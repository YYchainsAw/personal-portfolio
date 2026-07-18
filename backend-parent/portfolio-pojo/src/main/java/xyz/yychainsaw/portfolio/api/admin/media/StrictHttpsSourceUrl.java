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
                || authority.indexOf('%') >= 0
                || hasInvalidExplicitPort(authority)
                || host == null
                || host.isBlank()
                || hasInvalidHost(host)
                || parsed.getRawUserInfo() != null
                || parsed.getRawFragment() != null
                || port == 0
                || port > 65535
                || (port == -1 && authority.endsWith(":"))) {
            throw invalid();
        }
        return value;
    }

    private static boolean hasInvalidExplicitPort(String authority) {
        String suffix;
        if (authority.startsWith("[")) {
            int closingBracket = authority.indexOf(']');
            if (closingBracket < 0) {
                return true;
            }
            suffix = authority.substring(closingBracket + 1);
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon < 0) {
                return false;
            }
            suffix = authority.substring(colon);
        }
        if (suffix.isEmpty()) {
            return false;
        }
        return suffix.length() < 2
                || suffix.length() > 6
                || suffix.charAt(0) != ':'
                || !suffix.substring(1).chars().allMatch(StrictHttpsSourceUrl::isAsciiDigit);
    }

    private static boolean hasInvalidHost(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.length() < 3 || !host.substring(1, host.length() - 1)
                    .chars()
                    .allMatch(value -> isAsciiHexDigit(value) || value == ':' || value == '.');
        }
        if (host.length() > 253) {
            return true;
        }
        if (host.chars().allMatch(value -> value == '.' || isAsciiDigit(value))) {
            return !isCanonicalIpv4(host);
        }

        String domain = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (domain.isEmpty()) {
            return true;
        }
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > 63
                    || !isAsciiLetterOrDigit(label.charAt(0))
                    || !isAsciiLetterOrDigit(label.charAt(label.length() - 1))
                    || !label.chars().allMatch(value ->
                            isAsciiLetterOrDigit(value) || value == '-')) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCanonicalIpv4(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty()
                    || (octet.length() > 1 && octet.charAt(0) == '0')
                    || octet.length() > 3
                    || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiHexDigit(int value) {
        return isAsciiDigit(value)
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private static boolean isAsciiDigit(int value) {
        return value >= '0' && value <= '9';
    }

    private static boolean isAsciiLetterOrDigit(int value) {
        return isAsciiDigit(value)
                || (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z');
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("media source URL is invalid");
    }
}
