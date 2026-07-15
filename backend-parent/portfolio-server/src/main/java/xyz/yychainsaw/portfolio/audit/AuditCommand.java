package xyz.yychainsaw.portfolio.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import xyz.yychainsaw.portfolio.common.trace.TraceIds;

public record AuditCommand(
        UUID actorAdminId,
        String action,
        String targetType,
        String targetId,
        AuditOutcome outcome,
        String traceId,
        Map<String, String> metadata) {
    private static final int ACTION_MAX_LENGTH = 96;
    private static final int TARGET_TYPE_MAX_LENGTH = 64;
    private static final int TARGET_ID_MAX_LENGTH = 128;
    private static final int TRACE_ID_MAX_LENGTH = 64;

    public AuditCommand {
        action = requireTextWithinLimit(action, "action", ACTION_MAX_LENGTH);
        targetType = requireTextWithinLimit(
                targetType, "targetType", TARGET_TYPE_MAX_LENGTH);
        if (targetId != null) {
            targetId = requireTextWithinLimit(targetId, "targetId", TARGET_ID_MAX_LENGTH);
        }
        Objects.requireNonNull(outcome, "outcome");
        traceId = traceId == null || traceId.isBlank() ? TraceIds.current() : traceId;
        traceId = requireWithinLimit(traceId, "traceId", TRACE_ID_MAX_LENGTH);
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    private static String requireTextWithinLimit(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return requireWithinLimit(value, name, maxLength);
    }

    private static String requireWithinLimit(String value, String name, int maxLength) {
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(
                    name + " must be at most " + maxLength + " characters");
        }
        return value;
    }
}
