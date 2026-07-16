package xyz.yychainsaw.portfolio.auth.session;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class TrustedClientAddressResolver {
    static final String UNKNOWN = "unknown";

    private final Set<String> trustedPeers;

    public TrustedClientAddressResolver(SessionProperties properties) {
        Objects.requireNonNull(properties, "properties are required");
        Set<String> canonical = new HashSet<>();
        for (String configured : properties.trustedProxyAddresses()) {
            ParsedAddress address = parseStrictLiteral(configured);
            if (address == null) {
                throw new IllegalArgumentException(
                        "trusted proxy address must be a strict IP literal");
            }
            if (!canonical.add(address.canonical())) {
                throw new IllegalArgumentException(
                        "trusted proxy addresses must be canonically distinct");
            }
        }
        trustedPeers = Set.copyOf(canonical);
    }

    public String resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request is required");
        ParsedAddress directPeer = parseStrictLiteral(request.getRemoteAddr());
        if (directPeer == null) {
            return UNKNOWN;
        }
        if (!trustedPeers.contains(directPeer.canonical())) {
            return directPeer.canonical();
        }

        List<String> forwarded = Collections.list(request.getHeaders("X-Real-IP"));
        if (forwarded.size() != 1) {
            return UNKNOWN;
        }
        ParsedAddress client = parseStrictLiteral(forwarded.get(0));
        return client == null ? UNKNOWN : client.canonical();
    }

    static ParsedAddress parseStrictLiteral(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) {
            return null;
        }
        if (value.indexOf(':') < 0) {
            return parseIpv4(value);
        }
        if (value.indexOf('.') >= 0 || !value.matches("[0-9A-Fa-f:]+")) {
            return null;
        }
        try {
            InetAddress parsed = InetAddress.getByName(value);
            if (!(parsed instanceof Inet6Address) || parsed.getAddress().length != 16) {
                return null;
            }
            byte[] bytes = parsed.getAddress();
            return new ParsedAddress(bytes, canonicalIpv6(bytes), false);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private static ParsedAddress parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            int octet = 0;
            for (int character = 0; character < part.length(); character++) {
                char digit = part.charAt(character);
                if (digit < '0' || digit > '9') {
                    return null;
                }
                octet = octet * 10 + (digit - '0');
            }
            if (octet > 255) {
                return null;
            }
            bytes[index] = (byte) octet;
            if (index > 0) {
                canonical.append('.');
            }
            canonical.append(octet);
        }
        return new ParsedAddress(bytes, canonical.toString(), true);
    }

    static String canonicalIpv6(byte[] bytes) {
        int[] groups = new int[8];
        for (int index = 0; index < groups.length; index++) {
            groups[index] = (Byte.toUnsignedInt(bytes[index * 2]) << 8)
                    | Byte.toUnsignedInt(bytes[index * 2 + 1]);
        }

        int bestStart = -1;
        int bestLength = 0;
        for (int index = 0; index < groups.length;) {
            if (groups[index] != 0) {
                index++;
                continue;
            }
            int end = index;
            while (end < groups.length && groups[end] == 0) {
                end++;
            }
            int length = end - index;
            if (length >= 2 && length > bestLength) {
                bestStart = index;
                bestLength = length;
            }
            index = end;
        }

        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < groups.length; index++) {
            if (index == bestStart) {
                canonical.append("::");
                index += bestLength - 1;
                continue;
            }
            if (!canonical.isEmpty() && canonical.charAt(canonical.length() - 1) != ':') {
                canonical.append(':');
            }
            canonical.append(Integer.toHexString(groups[index]));
        }
        return canonical.toString();
    }

    static record ParsedAddress(byte[] bytes, String canonical, boolean ipv4) {
        ParsedAddress {
            bytes = Arrays.copyOf(bytes, bytes.length);
            Objects.requireNonNull(canonical, "canonical address is required");
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        boolean loopback() {
            if (ipv4) {
                return Byte.toUnsignedInt(bytes[0]) == 127;
            }
            for (int index = 0; index < bytes.length - 1; index++) {
                if (bytes[index] != 0) {
                    return false;
                }
            }
            return bytes[bytes.length - 1] == 1;
        }

        @Override
        public String toString() {
            return "ParsedAddress[address=<redacted>]";
        }
    }
}
