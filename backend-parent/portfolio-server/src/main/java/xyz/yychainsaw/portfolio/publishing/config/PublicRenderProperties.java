package xyz.yychainsaw.portfolio.publishing.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.render")
public record PublicRenderProperties(
        String releaseId,
        String viteManifest,
        int templateSchemaVersion,
        URI publicBaseUrl) {
    private static final Pattern RELEASE_ID =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public PublicRenderProperties {
        releaseId = requireReleaseId(releaseId);
        viteManifest = requireManifestLocation(viteManifest);
        if (templateSchemaVersion <= 0) {
            throw new IllegalArgumentException(
                    "public render template schema version must be positive");
        }
        publicBaseUrl = requirePublicBaseUrl(publicBaseUrl);
    }

    private static String requireReleaseId(String value) {
        if (value == null || !RELEASE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("public render release id is invalid");
        }
        return value;
    }

    private static String requireManifestLocation(String value) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || containsControl(value)) {
            throw invalidManifestLocation();
        }
        if (value.startsWith("classpath:/")) {
            requireSafeResourcePath(value.substring("classpath:/".length()));
            return value;
        }
        if (value.startsWith("file:")) {
            URI location;
            try {
                location = URI.create(value);
            } catch (IllegalArgumentException invalid) {
                throw invalidManifestLocation();
            }
            if (!location.isAbsolute()
                    || !"file".equalsIgnoreCase(location.getScheme())
                    || location.getRawAuthority() != null
                    || location.getRawQuery() != null
                    || location.getRawFragment() != null
                    || location.getRawPath() == null
                    || location.getRawPath().isBlank()) {
                throw invalidManifestLocation();
            }
            requireSafeResourcePath(location.getRawPath());
            return value;
        }
        throw invalidManifestLocation();
    }

    private static void requireSafeResourcePath(String value) {
        if (value == null
                || value.isBlank()
                || value.indexOf('\\') >= 0
                || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0
                || containsControl(value)) {
            throw invalidManifestLocation();
        }
        for (String segment : value.split("/", -1)) {
            String decoded = decodeResourceSegment(segment);
            if (decoded.equals(".")
                    || decoded.equals("..")
                    || decoded.indexOf('/') >= 0
                    || decoded.indexOf('\\') >= 0
                    || containsControl(decoded)) {
                throw invalidManifestLocation();
            }
        }
    }

    private static String decodeResourceSegment(String value) {
        String decoded = value;
        try {
            for (int pass = 0; pass < 3; pass++) {
                String next = URLDecoder.decode(
                        decoded.replace("+", "%2B"), StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    return decoded;
                }
                decoded = next;
            }
        } catch (IllegalArgumentException invalidEncoding) {
            throw invalidManifestLocation();
        }
        if (decoded.indexOf('%') >= 0) {
            throw invalidManifestLocation();
        }
        return decoded;
    }

    private static URI requirePublicBaseUrl(URI value) {
        URI url = Objects.requireNonNull(value, "public base URL is required");
        String path = url.getRawPath();
        if (!url.isAbsolute()
                || !"https".equalsIgnoreCase(url.getScheme())
                || url.getHost() == null
                || url.getRawAuthority() == null
                || url.getRawUserInfo() != null
                || url.getRawQuery() != null
                || url.getRawFragment() != null
                || path != null && !path.isEmpty() && !path.equals("/")
                || url.getPort() == 0
                || url.getPort() > 65_535) {
            throw new IllegalArgumentException("public base URL must be an HTTPS origin");
        }
        return URI.create("https://" + url.getRawAuthority());
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character ->
                Character.isISOControl(character) || Character.isWhitespace(character));
    }

    private static IllegalArgumentException invalidManifestLocation() {
        return new IllegalArgumentException("Vite manifest location is invalid");
    }
}
