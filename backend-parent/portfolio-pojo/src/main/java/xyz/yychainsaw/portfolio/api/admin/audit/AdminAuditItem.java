package xyz.yychainsaw.portfolio.api.admin.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AdminAuditItem(
        UUID id,
        UUID actorAdminId,
        String action,
        String targetType,
        String targetId,
        String outcome,
        String traceId,
        Map<String, String> metadata,
        Instant timestamp) {
    public AdminAuditItem {
        Objects.requireNonNull(id, "audit id is required");
        Objects.requireNonNull(action, "audit action is required");
        Objects.requireNonNull(targetType, "audit target type is required");
        Objects.requireNonNull(outcome, "audit outcome is required");
        if (!"SUCCESS".equals(outcome) && !"FAILURE".equals(outcome)) {
            throw new IllegalArgumentException("audit outcome is invalid");
        }
        Objects.requireNonNull(traceId, "audit trace id is required");
        metadata = Map.copyOf(Objects.requireNonNull(
                metadata, "audit metadata is required"));
        Objects.requireNonNull(timestamp, "audit timestamp is required");
    }
}
