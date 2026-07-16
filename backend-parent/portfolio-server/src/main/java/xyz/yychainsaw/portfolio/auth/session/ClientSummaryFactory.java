package xyz.yychainsaw.portfolio.auth.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.auth.session.TrustedClientAddressResolver.ParsedAddress;

@Component
public final class ClientSummaryFactory {
    private static final int MAXIMUM_USER_AGENT_UNITS = 512;

    private final TrustedClientAddressResolver addresses;

    public ClientSummaryFactory(TrustedClientAddressResolver addresses) {
        this.addresses = Objects.requireNonNull(addresses, "addresses are required");
    }

    public String create(HttpServletRequest request) {
        Objects.requireNonNull(request, "request is required");
        String userAgent = request.getHeader("User-Agent");
        String bounded = userAgent == null
                ? ""
                : userAgent.substring(0, Math.min(userAgent.length(), MAXIMUM_USER_AGENT_UNITS));
        return browser(bounded) + "/" + operatingSystem(bounded)
                + " @ " + mask(addresses.resolve(request));
    }

    private static String browser(String userAgent) {
        if (userAgent.contains("Edg/")) {
            return "Edge";
        }
        if (userAgent.contains("Chrome/") || userAgent.contains("CriOS/")) {
            return "Chrome";
        }
        if (userAgent.contains("Firefox/") || userAgent.contains("FxiOS/")) {
            return "Firefox";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari";
        }
        return "Other";
    }

    private static String operatingSystem(String userAgent) {
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("iPhone") || userAgent.contains("iPad")) {
            return "iOS";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Mac OS")) {
            return "macOS";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return "Other";
    }

    private static String mask(String value) {
        if (TrustedClientAddressResolver.UNKNOWN.equals(value)) {
            return TrustedClientAddressResolver.UNKNOWN;
        }
        ParsedAddress address = TrustedClientAddressResolver.parseStrictLiteral(value);
        if (address == null) {
            return TrustedClientAddressResolver.UNKNOWN;
        }
        if (address.loopback()) {
            return "local";
        }
        byte[] bytes = address.bytes();
        if (address.ipv4()) {
            return Byte.toUnsignedInt(bytes[0]) + "."
                    + Byte.toUnsignedInt(bytes[1]) + "."
                    + Byte.toUnsignedInt(bytes[2]) + ".x";
        }
        Arrays.fill(bytes, 8, bytes.length, (byte) 0);
        return TrustedClientAddressResolver.canonicalIpv6(bytes);
    }
}
