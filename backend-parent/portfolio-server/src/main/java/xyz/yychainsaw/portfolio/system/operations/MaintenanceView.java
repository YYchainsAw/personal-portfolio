package xyz.yychainsaw.portfolio.system.operations;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record MaintenanceView(
        String type,
        String status,
        Instant startedAt,
        Instant finishedAt,
        String artifactChecksum,
        String errorCategory) {
    private static final List<String> SUPPORTED_TYPES = List.of(
            "DATABASE_BACKUP",
            "MEDIA_BACKUP",
            "ANALYTICS_AGGREGATE",
            "CONTACT_RETENTION",
            "MEDIA_CLEANUP_SCAN",
            "DEPLOYMENT",
            "RESTORE_DRILL");
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED");
    private static final Set<String> SUPPORTED_STATUSES =
            Set.of("RUNNING", "SUCCEEDED", "FAILED");
    private static final Map<String, String> FAILURE_CATEGORIES = Map.of(
            "DATABASE_BACKUP", "DATABASE_BACKUP_FAILED",
            "MEDIA_BACKUP", "MEDIA_BACKUP_FAILED",
            "ANALYTICS_AGGREGATE", "ANALYTICS_AGGREGATION_FAILED",
            "CONTACT_RETENTION", "CONTACT_RETENTION_FAILED",
            "MEDIA_CLEANUP_SCAN", "MEDIA_CLEANUP_FAILED",
            "DEPLOYMENT", "DEPLOYMENT_FAILED",
            "RESTORE_DRILL", "RESTORE_DRILL_FAILED");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public MaintenanceView {
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("maintenance type is invalid");
        }
        if (!SUPPORTED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("maintenance status is invalid");
        }
        Objects.requireNonNull(startedAt, "maintenance start time is required");
        if (TERMINAL_STATUSES.contains(status)) {
            if (finishedAt == null || finishedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("maintenance finish time is invalid");
            }
        } else if (finishedAt != null) {
            throw new IllegalArgumentException("running maintenance cannot be finished");
        }
        if (artifactChecksum != null && !SHA_256.matcher(artifactChecksum).matches()) {
            throw new IllegalArgumentException("maintenance checksum is invalid");
        }
        String expectedCategory = "FAILED".equals(status)
                ? FAILURE_CATEGORIES.get(type)
                : null;
        if (!Objects.equals(expectedCategory, errorCategory)) {
            throw new IllegalArgumentException("maintenance error category is invalid");
        }
    }

    static List<String> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    static boolean supportsType(String type) {
        return SUPPORTED_TYPES.contains(type);
    }

    static String safeErrorCategory(String type, String status) {
        return "FAILED".equals(status) ? FAILURE_CATEGORIES.get(type) : null;
    }
}
