package xyz.yychainsaw.portfolio.media.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.system.job.JobHandler;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(
        prefix = "portfolio.media.cleanup",
        name = "enabled",
        havingValue = "true")
public final class MediaCleanupScanJobHandler implements JobHandler {
    static final Duration RETENTION = Duration.ofDays(30);
    static final int SCAN_BATCH_SIZE = 256;
    private static final int MAXIMUM_PAGES_PER_RUN = 16;

    private final MediaAssetRepository assets;
    private final MediaCleanupCoordinator coordinator;
    private final Clock clock;

    public MediaCleanupScanJobHandler(
            MediaAssetRepository assets,
            MediaCleanupCoordinator coordinator,
            Clock clock,
            MediaReferenceResolver references) {
        this.assets = Objects.requireNonNull(assets, "media assets are required");
        this.coordinator = Objects.requireNonNull(
                coordinator, "media cleanup coordinator is required");
        this.clock = Objects.requireNonNull(clock, "media cleanup clock is required");
        Objects.requireNonNull(references, "media references are required")
                .requireCheckerForCleanup();
    }

    @Override
    public String jobType() {
        return "MEDIA_CLEANUP_SCAN";
    }

    @Override
    public void handle(JsonNode payload) {
        Instant cutoff = MediaCleanupPayloads.parseScan(payload);
        if (cutoff.isAfter(maximumSafeCutoff())) {
            throw MediaCleanupPayloads.invalidScan();
        }
        boolean candidateFailed = false;
        try {
            UUID cursor = null;
            for (int page = 0; page < MAXIMUM_PAGES_PER_RUN; page++) {
                List<UUID> candidates = List.copyOf(
                        assets.findArchivedIdsAtOrBefore(
                                cutoff, cursor, SCAN_BATCH_SIZE));
                if (candidates.isEmpty()) {
                    break;
                }
                for (UUID candidate : candidates) {
                    if (candidate == null || candidate.equals(cursor)) {
                        throw new IllegalStateException("invalid media cleanup candidate");
                    }
                    try {
                        coordinator.stageForDeletion(candidate, cutoff);
                    } catch (RuntimeException isolatedFailure) {
                        candidateFailed = true;
                    }
                    cursor = candidate;
                }
                if (candidates.size() < SCAN_BATCH_SIZE) {
                    break;
                }
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("MEDIA_CLEANUP_SCAN_FAILED");
        }
        if (candidateFailed) {
            throw new IllegalStateException("MEDIA_CLEANUP_SCAN_FAILED");
        }
    }

    private Instant maximumSafeCutoff() {
        try {
            return Objects.requireNonNull(
                            clock.instant(), "media cleanup clock returned no instant")
                    .minus(RETENTION);
        } catch (DateTimeException | ArithmeticException invalid) {
            throw MediaCleanupPayloads.invalidScan();
        }
    }
}
