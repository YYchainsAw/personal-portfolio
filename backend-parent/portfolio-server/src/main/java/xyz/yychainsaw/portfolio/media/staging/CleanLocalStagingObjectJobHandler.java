package xyz.yychainsaw.portfolio.media.staging;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.system.job.JobExecutionContext;
import xyz.yychainsaw.portfolio.system.job.JobHandler;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class CleanLocalStagingObjectJobHandler implements JobHandler {
    private final LocalStagingReservationRepository reservations;
    private final MediaAssetRepository assets;
    private final LocalStagingSuccessorService successors;
    private final LocalStagingObjectCleanupPort cleanup;

    public CleanLocalStagingObjectJobHandler(
            LocalStagingReservationRepository reservations,
            MediaAssetRepository assets,
            LocalStagingSuccessorService successors,
            LocalStagingObjectCleanupPort cleanup) {
        this.reservations = Objects.requireNonNull(
                reservations, "local staging reservations are required");
        this.assets = Objects.requireNonNull(assets, "media assets are required");
        this.successors = Objects.requireNonNull(
                successors, "local staging successors are required");
        this.cleanup = Objects.requireNonNull(cleanup, "local staging cleanup is required");
    }

    @Override
    public String jobType() {
        return LocalStagingCleanupPayload.JOB_TYPE;
    }

    @Override
    public void handle(JsonNode payload) {
        throw fixed("LOCAL_STAGING_CLEANUP_CONTEXT_REQUIRED");
    }

    @Override
    public void handle(JobExecutionContext context, JsonNode payload) throws Exception {
        JobExecutionContext requiredContext = requireContext(context);
        LocalStagingCleanupPayload parsed = requirePayload(payload);
        Optional<LocalStagingReservation> found =
                reservations.findByAssetId(parsed.assetId());
        if (found.isEmpty()) {
            return;
        }
        LocalStagingReservation current = found.get();
        if (isStale(current, parsed, requiredContext)) {
            return;
        }
        requireImmutableIdentity(current, parsed);

        Optional<MediaAssetRecord> asset = assets.findById(parsed.assetId());
        if (asset.isPresent() && asset.get().status() == MediaStatus.PROCESSING) {
            successors.scheduleFromHandler(current);
            return;
        }
        asset.ifPresent(record -> requireExactTerminalAsset(current, record));
        LocalStagingObjectCleanupResult result = cleanup.cleanup(current, asset);
        if (result == LocalStagingObjectCleanupResult.DEFERRED) {
            successors.scheduleFromHandler(current);
            return;
        }
        if (result != LocalStagingObjectCleanupResult.CLEANED) {
            throw fixed("LOCAL_STAGING_CLEANUP_OUTCOME_INVALID");
        }
    }

    @Override
    public void onDeadLetter(JsonNode payload, String safeSummaryCode) {
        throw fixed("LOCAL_STAGING_CLEANUP_CONTEXT_REQUIRED");
    }

    @Override
    public void onDeadLetter(
            JobExecutionContext context, JsonNode payload, String safeSummaryCode) {
        JobExecutionContext requiredContext;
        LocalStagingCleanupPayload parsed;
        try {
            requiredContext = requireContext(context);
            parsed = LocalStagingCleanupPayload.parse(payload);
        } catch (RuntimeException poison) {
            return;
        }

        Optional<LocalStagingReservation> found =
                reservations.findByAssetId(parsed.assetId());
        if (found.isEmpty()) {
            return;
        }
        LocalStagingReservation current = found.get();
        if (isStale(current, parsed, requiredContext)
                || !immutableIdentityMatches(current, parsed)) {
            return;
        }
        successors.scheduleFromDeadLetter(current);
    }

    private static JobExecutionContext requireContext(JobExecutionContext context) {
        if (context == null) {
            throw fixed("LOCAL_STAGING_CLEANUP_CONTEXT_REQUIRED");
        }
        return context;
    }

    private static LocalStagingCleanupPayload requirePayload(JsonNode payload) {
        try {
            return LocalStagingCleanupPayload.parse(payload);
        } catch (RuntimeException invalid) {
            throw fixed("LOCAL_STAGING_CLEANUP_PAYLOAD_INVALID");
        }
    }

    private static boolean isStale(
            LocalStagingReservation current,
            LocalStagingCleanupPayload payload,
            JobExecutionContext context) {
        return current.generation() != payload.generation()
                || !current.cleanupJobId().equals(context.jobId());
    }

    private static void requireImmutableIdentity(
            LocalStagingReservation current, LocalStagingCleanupPayload payload) {
        if (!immutableIdentityMatches(current, payload)) {
            throw fixed("LOCAL_STAGING_CLEANUP_IDENTITY_MISMATCH");
        }
    }

    private static boolean immutableIdentityMatches(
            LocalStagingReservation current, LocalStagingCleanupPayload payload) {
        return current.assetId().equals(payload.assetId())
                && current.sha256().equals(payload.sha256())
                && current.mimeType().equals(payload.mimeType());
    }

    private static void requireExactTerminalAsset(
            LocalStagingReservation reservation, MediaAssetRecord asset) {
        String expectedOriginal = MediaObjectKeys.originalKey(
                reservation.assetId(), reservation.sha256(), reservation.mimeType());
        if (!asset.id().equals(reservation.assetId())
                || asset.status() == MediaStatus.PROCESSING
                || asset.provider() != StorageProvider.LOCAL
                || asset.bucket() != null
                || asset.region() != null
                || !asset.objectKey().equals(expectedOriginal)
                || !asset.sha256().equals(reservation.sha256())
                || !asset.mimeType().equals(reservation.mimeType())) {
            throw fixed("LOCAL_STAGING_CLEANUP_ASSET_MISMATCH");
        }
    }

    private static IllegalStateException fixed(String code) {
        return new IllegalStateException(code);
    }
}
