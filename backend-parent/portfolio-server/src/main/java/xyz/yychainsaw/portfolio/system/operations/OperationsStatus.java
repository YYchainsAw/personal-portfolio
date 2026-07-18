package xyz.yychainsaw.portfolio.system.operations;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record OperationsStatus(
        MaintenanceView databaseBackup,
        MaintenanceView mediaBackup,
        MaintenanceView analyticsAggregation,
        MaintenanceView contactRetention,
        MaintenanceView mediaCleanup,
        MaintenanceView deployment,
        MaintenanceView restoreDrill,
        Instant serverTime) {
    public OperationsStatus {
        Objects.requireNonNull(serverTime, "operations server time is required");
    }
}
