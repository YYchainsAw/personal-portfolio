package xyz.yychainsaw.portfolio.media.staging;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record LocalStagingCleanupPayload(
        UUID assetId, long generation, String mimeType, String sha256) {
    public static final String JOB_TYPE = "CLEAN_LOCAL_STAGING_OBJECT";

    private static final Set<String> FIELDS =
            Set.of("assetId", "generation", "mimeType", "sha256");
    private static final Set<String> MIME_TYPES =
            Set.of("image/jpeg", "image/png", "application/pdf");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public LocalStagingCleanupPayload {
        if (assetId == null
                || generation < 0
                || !MIME_TYPES.contains(mimeType)
                || sha256 == null
                || !SHA256.matcher(sha256).matches()) {
            throw invalid();
        }
    }

    public static LocalStagingCleanupPayload parse(JsonNode payload) {
        if (payload == null
                || !payload.isObject()
                || payload.size() != FIELDS.size()
                || !FIELDS.stream().allMatch(payload::has)) {
            throw invalid();
        }

        JsonNode assetIdNode = payload.get("assetId");
        JsonNode generationNode = payload.get("generation");
        JsonNode mimeTypeNode = payload.get("mimeType");
        JsonNode sha256Node = payload.get("sha256");
        if (!isText(assetIdNode)
                || generationNode == null
                || !generationNode.isIntegralNumber()
                || !generationNode.canConvertToLong()
                || !isText(mimeTypeNode)
                || !isText(sha256Node)) {
            throw invalid();
        }

        UUID assetId;
        try {
            assetId = UUID.fromString(assetIdNode.textValue());
        } catch (IllegalArgumentException invalidUuid) {
            throw invalid();
        }
        if (!assetId.toString().equals(assetIdNode.textValue())) {
            throw invalid();
        }

        return new LocalStagingCleanupPayload(
                assetId,
                generationNode.longValue(),
                mimeTypeNode.textValue(),
                sha256Node.textValue());
    }

    public Map<String, Object> toJobPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assetId", assetId.toString());
        payload.put("generation", generation);
        payload.put("mimeType", mimeType);
        payload.put("sha256", sha256);
        return Collections.unmodifiableMap(payload);
    }

    public String idempotencyKey() {
        return "local-staging-cleanup:" + assetId + ':' + sha256 + ":g" + generation;
    }

    @Override
    public String toString() {
        return "LocalStagingCleanupPayload[redacted]";
    }

    private static boolean isText(JsonNode node) {
        return node != null && node.isTextual();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("local staging cleanup payload is invalid");
    }
}
