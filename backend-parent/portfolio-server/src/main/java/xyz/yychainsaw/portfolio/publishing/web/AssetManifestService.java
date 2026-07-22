package xyz.yychainsaw.portfolio.publishing.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import xyz.yychainsaw.portfolio.publishing.config.PublicRenderProperties;

public interface AssetManifestService {
    String entryJs();

    List<String> css();

    Optional<String> asset(String source);
}

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
final class ViteAssetManifestService implements AssetManifestService {
    private static final String ENTRY = "src/main.ts";

    private final String entryJs;
    private final List<String> css;
    private final Map<String, String> assets;

    ViteAssetManifestService(
            PublicRenderProperties properties,
            ResourceLoader resources,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(properties, "public render properties are required");
        Objects.requireNonNull(resources, "resource loader is required");
        Objects.requireNonNull(objectMapper, "object mapper is required");

        Resource manifest = resources.getResource(properties.viteManifest());
        JsonNode document = readDocument(manifest, objectMapper);
        JsonNode entry = requiredEntry(document);
        requireExactSource(entry);
        requireEntryFlag(entry);
        this.entryJs = publicAssetPath(
                requiredText(entry, "file"), ".js", "entry file");
        this.css = readCss(entry);
        this.assets = readAssets(document);
    }

    @Override
    public String entryJs() {
        return entryJs;
    }

    @Override
    public List<String> css() {
        return css;
    }

    @Override
    public Optional<String> asset(String source) {
        return Optional.ofNullable(assets.get(Objects.requireNonNull(
                source, "asset source is required")));
    }

    private static JsonNode readDocument(Resource manifest, ObjectMapper objectMapper) {
        if (manifest == null || !manifest.exists() || !manifest.isReadable()) {
            throw invalid("resource is missing or unreadable");
        }
        try (InputStream input = manifest.getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            if (root == null || !root.isObject()) {
                throw invalid("root must be an object");
            }
            return root;
        } catch (IOException malformed) {
            throw new IllegalStateException("Vite manifest cannot be read", malformed);
        }
    }

    private static JsonNode requiredEntry(JsonNode document) {
        JsonNode entry = document.get(ENTRY);
        if (entry == null || !entry.isObject()) {
            throw invalid("entry src/main.ts is missing");
        }
        return entry;
    }

    private static void requireExactSource(JsonNode entry) {
        if (!ENTRY.equals(requiredText(entry, "src"))) {
            throw invalid("entry source must be src/main.ts");
        }
    }

    private static void requireEntryFlag(JsonNode entry) {
        JsonNode flag = entry.get("isEntry");
        if (flag == null || !flag.isBoolean() || !flag.booleanValue()) {
            throw invalid("src/main.ts must be the entry");
        }
    }

    private static List<String> readCss(JsonNode entry) {
        JsonNode css = entry.get("css");
        if (css == null || !css.isArray()) {
            throw invalid("entry CSS list is missing");
        }
        List<String> paths = new ArrayList<>(css.size());
        for (JsonNode path : css) {
            if (path == null || !path.isTextual()) {
                throw invalid("entry CSS path must be a string");
            }
            paths.add(publicAssetPath(path.textValue(), ".css", "CSS path"));
        }
        return List.copyOf(paths);
    }

    private static Map<String, String> readAssets(JsonNode document) {
        Map<String, String> paths = new LinkedHashMap<>();
        document.properties().forEach(manifestEntry -> {
            String source = manifestEntry.getKey();
            JsonNode value = manifestEntry.getValue();
            if (!source.startsWith("src/assets/")
                    || value == null
                    || !value.isObject()
                    || !source.equals(optionalText(value, "src"))) {
                return;
            }

            String extension = sourceExtension(source);
            paths.put(source, publicAssetPath(
                    requiredText(value, "file"), extension, "asset file"));
        });
        return Map.copyOf(paths);
    }

    private static String optionalText(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String sourceExtension(String source) {
        int slash = source.lastIndexOf('/');
        int dot = source.lastIndexOf('.');
        if (dot <= slash || dot == source.length() - 1) {
            throw invalid("asset source has no file type");
        }
        return source.substring(dot);
    }

    private static String requiredText(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("entry " + field + " is missing");
        }
        return value.textValue();
    }

    private static String publicAssetPath(
            String value, String extension, String field) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || !value.startsWith("assets/")
                || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0
                || containsUnsafeCharacter(value)) {
            throw invalid(field + " is unsafe");
        }

        URI path;
        try {
            path = URI.create(value);
        } catch (IllegalArgumentException invalidPath) {
            throw invalid(field + " is unsafe");
        }
        if (path.isAbsolute()
                || path.getRawAuthority() != null
                || path.getRawQuery() != null
                || path.getRawFragment() != null
                || !value.equals(path.getRawPath())) {
            throw invalid(field + " is unsafe");
        }

        String[] segments = value.split("/", -1);
        if (segments.length < 2) {
            throw invalid(field + " is unsafe");
        }
        for (String segment : segments) {
            String decoded = decodeSegment(segment, field);
            if (segment.isEmpty()
                    || decoded.equals(".")
                    || decoded.equals("..")
                    || decoded.indexOf('/') >= 0
                    || decoded.indexOf('\\') >= 0
                    || decoded.indexOf(':') >= 0
                    || containsUnsafeCharacter(decoded)) {
                throw invalid(field + " is unsafe");
            }
        }
        String filename = segments[segments.length - 1];
        if (!filename.endsWith(extension) || filename.length() <= extension.length()) {
            throw invalid(field + " has the wrong file type");
        }
        return '/' + value;
    }

    private static String decodeSegment(String segment, String field) {
        String decoded = segment;
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
            throw invalid(field + " is unsafe");
        }
        if (decoded.indexOf('%') >= 0) {
            throw invalid(field + " is unsafe");
        }
        return decoded;
    }

    private static boolean containsUnsafeCharacter(String value) {
        return value.chars().anyMatch(character ->
                Character.isISOControl(character) || Character.isWhitespace(character));
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("Vite manifest " + detail);
    }
}
