package xyz.yychainsaw.portfolio.media.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.system.job.JobHandler;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(
        prefix = "portfolio.media.cleanup",
        name = "enabled",
        havingValue = "true")
public final class ArchivedMediaCleanupJobHandler implements JobHandler {
    private final MediaCleanupCoordinator coordinator;
    private final MediaLifecycleBarrier barrier;
    private final StorageRouter storageRouter;
    private final Clock clock;

    public ArchivedMediaCleanupJobHandler(
            MediaCleanupCoordinator coordinator,
            MediaLifecycleBarrier barrier,
            StorageRouter storageRouter,
            Clock clock,
            MediaReferenceResolver references) {
        this.coordinator = Objects.requireNonNull(
                coordinator, "media cleanup coordinator is required");
        this.barrier = Objects.requireNonNull(
                barrier, "media lifecycle barrier is required");
        this.storageRouter = Objects.requireNonNull(
                storageRouter, "storage router is required");
        this.clock = Objects.requireNonNull(clock, "media cleanup clock is required");
        Objects.requireNonNull(references, "media references are required")
                .requireCheckerForCleanup();
    }

    @Override
    public String jobType() {
        return "DELETE_MEDIA_ASSET";
    }

    @Override
    public void handle(JsonNode payload) throws Exception {
        MediaDeletionRequest request = parseSafeRequest(payload);
        AutoCloseable lease = Objects.requireNonNull(
                barrier.acquireExclusiveDeletionLease(),
                "media lifecycle barrier returned no lease");
        try (lease) {
            Optional<MediaDeletionPlan> prepared = coordinator.prepareDeletion(
                    request, maximumSafeCutoff());
            if (prepared.isEmpty()) {
                return;
            }
            MediaDeletionPlan plan = prepared.get();
            StorageService storage;
            try {
                storage = storageRouter.require(plan.asset().provider());
            } catch (RuntimeException routingFailure) {
                fail(plan, "STORAGE_ROUTING_FAILED");
                return;
            }
            if (!locationMatches(plan.asset(), storage)) {
                fail(plan, "STORAGE_LOCATION_MISMATCH");
            }
            try {
                for (String objectKey : plan.objectKeys()) {
                    storage.delete(objectKey);
                }
            } catch (RuntimeException providerFailure) {
                fail(plan, "PROVIDER_DELETE_FAILED");
            }
            try {
                if (!coordinator.finishDeletion(plan)) {
                    throw new IllegalStateException("media deletion fence changed");
                }
            } catch (RuntimeException databaseFailure) {
                fail(plan, "DATABASE_FINALIZE_FAILED");
            }
        }
    }

    @Override
    public void onDeadLetter(JsonNode payload, String safeSummaryCode) {
        MediaDeletionRequest request;
        try {
            request = MediaCleanupPayloads.parseDeletion(payload);
        } catch (IllegalArgumentException poisonPayload) {
            return;
        }
        coordinator.recordDeadLetter(request);
    }

    private MediaDeletionRequest parseSafeRequest(JsonNode payload) {
        MediaDeletionRequest request = MediaCleanupPayloads.parseDeletion(payload);
        if (request.cutoff().isAfter(maximumSafeCutoff())) {
            throw MediaCleanupPayloads.invalidDeletion();
        }
        return request;
    }

    private Instant maximumSafeCutoff() {
        try {
            return Objects.requireNonNull(
                            clock.instant(), "media cleanup clock returned no instant")
                    .minus(MediaCleanupScanJobHandler.RETENTION);
        } catch (DateTimeException | ArithmeticException invalid) {
            throw MediaCleanupPayloads.invalidDeletion();
        }
    }

    private static boolean locationMatches(
            MediaAssetRecord asset, StorageService storage) {
        try {
            StorageLocation expected = new StorageLocation(
                    asset.provider(), asset.bucket(), asset.region());
            return asset.provider() == storage.provider()
                    && expected.equals(storage.location());
        } catch (RuntimeException invalidLocation) {
            return false;
        }
    }

    private void fail(MediaDeletionPlan plan, String result) {
        try {
            coordinator.auditDeletionFailure(plan, result);
        } catch (RuntimeException ignored) {
            // The durable retry remains authoritative if failure auditing is unavailable.
        }
        throw new IllegalStateException("MEDIA_DELETE_FAILED");
    }
}
