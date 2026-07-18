package xyz.yychainsaw.portfolio.system.operations;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.system.operations.MaintenanceRunMapper.MaintenanceRunSnapshot;

@Service
public class OperationsStatusService {
    private final MaintenanceRunMapper runs;
    private final Clock clock;

    public OperationsStatusService(MaintenanceRunMapper runs, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "maintenance run query is required");
        this.clock = Objects.requireNonNull(clock, "operations clock is required");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OperationsStatus read() {
        List<MaintenanceRunSnapshot> latest =
                List.copyOf(runs.findLatestAllowlistedRuns());
        Map<String, MaintenanceView> byType = new HashMap<>();
        for (MaintenanceRunSnapshot run : latest) {
            MaintenanceView view = toView(Objects.requireNonNull(
                    run, "maintenance run query returned a null row"));
            MaintenanceView duplicate = byType.putIfAbsent(view.type(), view);
            if (duplicate != null) {
                throw new IllegalStateException("operations query returned duplicate types");
            }
        }

        Instant serverTime = Objects.requireNonNull(
                clock.instant(), "operations clock returned no instant");
        return new OperationsStatus(
                byType.get("DATABASE_BACKUP"),
                byType.get("MEDIA_BACKUP"),
                byType.get("ANALYTICS_AGGREGATE"),
                byType.get("CONTACT_RETENTION"),
                byType.get("MEDIA_CLEANUP_SCAN"),
                byType.get("DEPLOYMENT"),
                byType.get("RESTORE_DRILL"),
                serverTime);
    }

    private static MaintenanceView toView(MaintenanceRunSnapshot run) {
        if (!MaintenanceView.supportsType(run.runType())) {
            throw new IllegalStateException("operations query returned an unsupported type");
        }
        return new MaintenanceView(
                run.runType(),
                run.status(),
                run.startedAt(),
                run.finishedAt(),
                run.artifactChecksum(),
                MaintenanceView.safeErrorCategory(run.runType(), run.status()));
    }
}
