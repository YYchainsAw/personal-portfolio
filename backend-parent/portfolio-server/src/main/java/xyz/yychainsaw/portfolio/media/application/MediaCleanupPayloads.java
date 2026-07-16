package xyz.yychainsaw.portfolio.media.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.UUID;

final class MediaCleanupPayloads {
    private MediaCleanupPayloads() {}

    static Instant parseScan(JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.size() != 1) {
            throw invalidScan();
        }
        JsonNode cutoff = payload.get("cutoffEpochSecond");
        if (!integralLong(cutoff)) {
            throw invalidScan();
        }
        try {
            return Instant.ofEpochSecond(cutoff.longValue());
        } catch (DateTimeException | ArithmeticException invalid) {
            throw invalidScan();
        }
    }

    static MediaDeletionRequest parseDeletion(JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.size() != 3) {
            throw invalidDeletion();
        }
        JsonNode assetIdNode = payload.get("assetId");
        JsonNode versionNode = payload.get("version");
        JsonNode cutoffNode = payload.get("cutoffEpochSecond");
        if (assetIdNode == null
                || !assetIdNode.isTextual()
                || !integralLong(versionNode)
                || versionNode.longValue() < 0
                || !integralLong(cutoffNode)) {
            throw invalidDeletion();
        }
        try {
            String encodedId = assetIdNode.textValue();
            UUID assetId = UUID.fromString(encodedId);
            if (!assetId.toString().equals(encodedId)) {
                throw invalidDeletion();
            }
            return new MediaDeletionRequest(
                    assetId,
                    versionNode.longValue(),
                    Instant.ofEpochSecond(cutoffNode.longValue()));
        } catch (DateTimeException | IllegalArgumentException invalid) {
            if (invalid.getClass() == IllegalArgumentException.class
                    && "MEDIA_DELETE_PAYLOAD_INVALID".equals(invalid.getMessage())) {
                throw invalid;
            }
            throw invalidDeletion();
        }
    }

    private static boolean integralLong(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong();
    }

    static IllegalArgumentException invalidScan() {
        return new IllegalArgumentException("MEDIA_CLEANUP_SCAN_PAYLOAD_INVALID");
    }

    static IllegalArgumentException invalidDeletion() {
        return new IllegalArgumentException("MEDIA_DELETE_PAYLOAD_INVALID");
    }
}
