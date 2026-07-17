package xyz.yychainsaw.portfolio.media.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

@Service
public class MediaCleanupCoordinator {
    private static final String DELETE_JOB_TYPE = "DELETE_MEDIA_ASSET";

    private final MediaAssetRepository assets;
    private final MediaVariantRepository variants;
    private final MediaTranslationRepository translations;
    private final ScheduledJobInserter jobs;
    private final AuditService audit;
    private final MediaReferenceResolver references;

    public MediaCleanupCoordinator(
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            MediaTranslationRepository translations,
            ScheduledJobInserter jobs,
            AuditService audit,
            MediaReferenceResolver references) {
        this.assets = Objects.requireNonNull(assets, "media assets are required");
        this.variants = Objects.requireNonNull(variants, "media variants are required");
        this.translations = Objects.requireNonNull(
                translations, "media translations are required");
        this.jobs = Objects.requireNonNull(jobs, "job inserter is required");
        this.audit = Objects.requireNonNull(audit, "audit service is required");
        this.references = Objects.requireNonNull(
                references, "media references are required");
    }

    @Transactional
    public boolean stageForDeletion(UUID assetId, Instant cutoff) {
        Objects.requireNonNull(assetId, "media asset id is required");
        Objects.requireNonNull(cutoff, "media cleanup cutoff is required");
        Optional<MediaAssetRecord> locked = assets.findByIdForUpdate(assetId);
        if (locked.isEmpty()) {
            return false;
        }
        MediaAssetRecord asset = locked.get();
        if (!eligible(asset, MediaStatus.ARCHIVED, cutoff)
                || references.hasReferences(assetId)) {
            return false;
        }
        if (assets.markPendingDelete(assetId, asset.version(), cutoff) != 1) {
            return false;
        }
        jobs.insertAfter(
                DELETE_JOB_TYPE,
                deletionIdempotencyKey(asset),
                deletionPayload(asset, cutoff),
                Duration.ZERO);
        return true;
    }

    @Transactional
    public Optional<MediaDeletionPlan> prepareDeletion(
            MediaDeletionRequest request, Instant maximumSafeCutoff) {
        Objects.requireNonNull(request, "media deletion request is required");
        Objects.requireNonNull(
                maximumSafeCutoff, "maximum media cleanup cutoff is required");
        Optional<MediaAssetRecord> locked = assets.findByIdForUpdate(request.assetId());
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        MediaAssetRecord asset = locked.get();
        if ((asset.status() != MediaStatus.ARCHIVED
                        && asset.status() != MediaStatus.PENDING_DELETE)
                || asset.version() != request.version()
                || !oldEnough(asset, request.cutoff())
                || !oldEnough(asset, maximumSafeCutoff)) {
            return Optional.empty();
        }

        if (references.hasReferences(asset.id())) {
            recordAudit(asset, 0, "REFERENCE_BLOCKED", AuditOutcome.FAILURE);
            return Optional.empty();
        }

        if (asset.status() == MediaStatus.ARCHIVED
                && assets.markPendingDelete(
                        asset.id(), asset.version(), request.cutoff()) != 1) {
            return Optional.empty();
        }

        List<String> objectKeys = new ArrayList<>();
        objectKeys.add(asset.objectKey());
        for (MediaVariantRecord variant : variants.findByAssetId(asset.id())) {
            objectKeys.add(variant.objectKey());
        }
        return Optional.of(new MediaDeletionPlan(asset, request.cutoff(), objectKeys));
    }

    @Transactional
    public boolean finishDeletion(MediaDeletionPlan plan) {
        Objects.requireNonNull(plan, "media deletion plan is required");
        MediaAssetRecord expected = plan.asset();
        Optional<MediaAssetRecord> locked = assets.findByIdForUpdate(expected.id());
        if (locked.isEmpty()) {
            return false;
        }
        MediaAssetRecord current = locked.get();
        if (current.status() != MediaStatus.PENDING_DELETE
                || current.version() != expected.version()
                || !oldEnough(current, plan.cutoff())) {
            return false;
        }
        translations.deleteByAssetId(current.id());
        variants.deleteByAssetId(current.id());
        if (assets.deletePending(current.id(), current.version()) != 1) {
            throw fixedFailure("MEDIA_DELETE_FINALIZE_FAILED");
        }
        recordAudit(current, plan.objectKeys().size(), "DELETED", AuditOutcome.SUCCESS);
        return true;
    }

    @Transactional
    public void auditDeletionFailure(MediaDeletionPlan plan, String result) {
        Objects.requireNonNull(plan, "media deletion plan is required");
        if (!isFailureResult(result)) {
            throw new IllegalArgumentException("media cleanup result is invalid");
        }
        recordAudit(
                plan.asset(),
                plan.objectKeys().size(),
                result,
                AuditOutcome.FAILURE);
    }

    @Transactional
    public void recordDeadLetter(MediaDeletionRequest request) {
        Objects.requireNonNull(request, "media deletion request is required");
        Optional<MediaAssetRecord> locked = assets.findByIdForUpdate(request.assetId());
        if (locked.isEmpty()) {
            return;
        }
        MediaAssetRecord asset = locked.get();
        if (asset.status() != MediaStatus.PENDING_DELETE
                || asset.version() != request.version()) {
            return;
        }
        recordAudit(asset, 0, "DEAD_LETTER_PENDING", AuditOutcome.FAILURE);
    }

    private void recordAudit(
            MediaAssetRecord asset,
            int objectCount,
            String result,
            AuditOutcome outcome) {
        audit.record(new AuditCommand(
                null,
                "MEDIA_PHYSICAL_DELETE",
                "MEDIA_ASSET",
                asset.id().toString(),
                outcome,
                null,
                Map.of(
                        "assetId", asset.id().toString(),
                        "provider", asset.provider().name(),
                        "objectCount", Integer.toString(objectCount),
                        "result", result)));
    }

    private static boolean eligible(
            MediaAssetRecord asset, MediaStatus status, Instant cutoff) {
        return asset.status() == status && oldEnough(asset, cutoff);
    }

    private static boolean oldEnough(MediaAssetRecord asset, Instant cutoff) {
        return asset.archivedAt() != null && !asset.archivedAt().isAfter(cutoff);
    }

    private static String deletionIdempotencyKey(MediaAssetRecord asset) {
        return "media-delete:" + asset.id() + ":" + asset.version();
    }

    private static Map<String, ?> deletionPayload(
            MediaAssetRecord asset, Instant cutoff) {
        return Map.of(
                "assetId", asset.id().toString(),
                "version", asset.version(),
                "cutoffEpochSecond", cutoff.getEpochSecond());
    }

    private static boolean isFailureResult(String result) {
        return "PROVIDER_DELETE_FAILED".equals(result)
                || "STORAGE_LOCATION_MISMATCH".equals(result)
                || "STORAGE_ROUTING_FAILED".equals(result)
                || "DATABASE_FINALIZE_FAILED".equals(result);
    }

    private static IllegalStateException fixedFailure(String message) {
        return new IllegalStateException(message);
    }
}
