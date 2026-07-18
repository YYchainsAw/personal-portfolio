package xyz.yychainsaw.portfolio.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class AuditMetadataRedactor {
    private static final int MAXIMUM_VALUE_CODE_POINTS = 128;
    private static final List<String> ALLOWED_KEYS = List.of(
            "stage",
            "next",
            "method",
            "reason",
            "channel",
            "backupSha256",
            "recoveryCodeCount",
            "revokedOtherSessions",
            "staleActor",
            "fromKeyVersion",
            "toKeyVersion",
            "previousStatus",
            "newStatus",
            "previousEmailStatus",
            "newEmailStatus",
            "createdDate");

    private final ObjectMapper json;

    AuditMetadataRedactor(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "object mapper is required");
    }

    Map<String, String> redact(String rawJson) {
        try {
            JsonNode source = json.readTree(Objects.requireNonNull(
                    rawJson, "stored audit metadata is required"));
            if (source == null || !source.isObject()) {
                throw new IllegalStateException("stored audit metadata is unavailable");
            }
            Map<String, String> safe = new LinkedHashMap<>();
            for (String key : ALLOWED_KEYS) {
                JsonNode value = source.get(key);
                if (value != null && value.isTextual()) {
                    safe.put(key, truncate(value.textValue()));
                }
            }
            return Collections.unmodifiableMap(safe);
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalStateException("stored audit metadata is unavailable");
        }
    }

    private static String truncate(String value) {
        int count = value.codePointCount(0, value.length());
        if (count <= MAXIMUM_VALUE_CODE_POINTS) {
            return value;
        }
        int end = value.offsetByCodePoints(0, MAXIMUM_VALUE_CODE_POINTS);
        return value.substring(0, end);
    }
}
