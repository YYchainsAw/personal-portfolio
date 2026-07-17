package xyz.yychainsaw.portfolio.media.staging;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInsert;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public class LocalStagingReservationService implements SmartInitializingSingleton {
    private static final String CLEANUP_JOB_TYPE = "CLEAN_LOCAL_STAGING_OBJECT";
    private static final Duration CLEANUP_DELAY = Duration.ofHours(24);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "application/pdf");

    private final LocalStagingReservationRepository repository;
    private final ScheduledJobInserter jobs;
    private final LocalStorageService storage;
    private final LocalStagingPolicy configuredPolicy;
    private final TransactionTemplate requiresNew;

    public LocalStagingReservationService(
            LocalStagingReservationRepository repository,
            ScheduledJobInserter jobs,
            LocalStorageService storage,
            LocalStagingPolicyProperties properties,
            PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(
                repository, "local staging reservation repository is required");
        this.jobs = Objects.requireNonNull(jobs, "scheduled job inserter is required");
        this.storage = Objects.requireNonNull(storage, "local storage service is required");
        this.configuredPolicy = Objects.requireNonNull(
                properties, "local staging policy properties are required").toPolicy();
        PlatformTransactionManager requiredTransactionManager = Objects.requireNonNull(
                transactionManager, "transaction manager is required");
        this.requiresNew = new TransactionTemplate(requiredTransactionManager);
        this.requiresNew.setName("local-staging-reservation");
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNew.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.requiresNew.setTimeout(10);
    }

    @Override
    public void afterSingletonsInstantiated() {
        initializePolicy(configuredPolicy);
    }

    void initializePolicy(LocalStagingPolicy expected) {
        Objects.requireNonNull(expected, "local staging policy is required");
        try {
            requiresNew.executeWithoutResult(status -> {
                repository.acquirePolicyLock();
                if (repository.findPolicy().isEmpty()) {
                    repository.insertPolicyIfAbsent(expected);
                }
                LocalStagingPolicy stored = repository.findPolicy()
                        .orElseThrow(() -> fixed("LOCAL_STAGING_POLICY_MISMATCH"));
                if (!stored.equals(expected)) {
                    throw fixed("LOCAL_STAGING_POLICY_MISMATCH");
                }
                String volumeId = currentVolumeId();
                if (!repository.claimVolumeId(volumeId)
                        || !repository.volumeMatches(volumeId)) {
                    throw fixed("LOCAL_STAGING_VOLUME_MISMATCH");
                }
            });
        } catch (RuntimeException failure) {
            if (isFixed(failure, "LOCAL_STAGING_POLICY_MISMATCH")
                    || isFixed(failure, "LOCAL_STAGING_VOLUME_MISMATCH")) {
                throw failure;
            }
            throw fixed("LOCAL_STAGING_POLICY_FAILED");
        }
    }

    public LocalStagingReservationReceipt reserve(
            UUID assetId, String sha256, String mimeType) {
        requireReservationIdentity(assetId, sha256, mimeType);
        AtomicInteger completion = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
        AtomicReference<LocalStagingReservation> created = new AtomicReference<>();
        try {
            requiresNew.executeWithoutResult(status -> {
                registerCompletion(completion);
                repository.acquireCapacityLock();
                requireConfiguredPolicy();
                if (repository.hasStalledReservation()) {
                    throw fixed("LOCAL_STAGING_CHAIN_STALLED");
                }
                if (repository.countActiveReservations()
                        >= configuredPolicy.activeCapacity()) {
                    throw fixed("LOCAL_STAGING_CAPACITY_EXHAUSTED");
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("assetId", assetId.toString());
                payload.put("generation", 0L);
                payload.put("mimeType", mimeType);
                payload.put("sha256", sha256);
                String key = "local-staging-cleanup:"
                        + assetId + ":" + sha256 + ":g0";
                ScheduledJobInsert job = jobs.insertAfter(
                        CLEANUP_JOB_TYPE, key, payload, CLEANUP_DELAY);
                LocalStagingReservation reservation = new LocalStagingReservation(
                        assetId,
                        sha256,
                        mimeType,
                        0L,
                        job.jobId(),
                        job.databaseNow(),
                        job.nextRunAt());
                repository.insert(reservation);
                created.set(reservation);
            });
        } catch (RuntimeException failure) {
            if (isFixed(failure, "LOCAL_STAGING_CAPACITY_EXHAUSTED")
                    || isFixed(failure, "LOCAL_STAGING_CHAIN_STALLED")
                    || isFixed(failure, "LOCAL_STAGING_POLICY_MISMATCH")
                    || isFixed(failure, "LOCAL_STAGING_VOLUME_MISMATCH")
                    || isFixed(failure, "LOCAL_STAGING_RESERVATION_COMMIT_UNKNOWN")) {
                throw failure;
            }
            throw fixed("LOCAL_STAGING_RESERVATION_FAILED");
        }

        if (completion.get() != TransactionSynchronization.STATUS_COMMITTED) {
            throw fixed("LOCAL_STAGING_RESERVATION_COMMIT_UNKNOWN");
        }
        LocalStagingReservation reservation = created.get();
        if (reservation == null) {
            throw fixed("LOCAL_STAGING_RESERVATION_COMMIT_UNKNOWN");
        }
        return new LocalStagingReservationReceipt(reservation);
    }

    @Transactional(readOnly = true)
    public LocalStagingReservation authenticateCurrent(
            UUID assetId,
            String sha256,
            String mimeType,
            long generation,
            UUID cleanupJobId) {
        try {
            if (!validReservationIdentity(assetId, sha256, mimeType)
                    || generation < 0
                    || cleanupJobId == null) {
                throw fixed("LOCAL_STAGING_RESERVATION_INVALID");
            }
            requireConfiguredPolicy();
            LocalStagingReservation reservation = repository.findByAssetId(assetId)
                    .orElseThrow(() -> fixed("LOCAL_STAGING_RESERVATION_INVALID"));
            if (!reservation.sha256().equals(sha256)
                    || !reservation.mimeType().equals(mimeType)
                    || reservation.generation() != generation
                    || !reservation.cleanupJobId().equals(cleanupJobId)) {
                throw fixed("LOCAL_STAGING_RESERVATION_INVALID");
            }
            return reservation;
        } catch (RuntimeException failure) {
            if (isFixed(failure, "LOCAL_STAGING_RESERVATION_INVALID")
                    || isFixed(failure, "LOCAL_STAGING_POLICY_MISMATCH")
                    || isFixed(failure, "LOCAL_STAGING_VOLUME_MISMATCH")) {
                throw failure;
            }
            throw fixed("LOCAL_STAGING_RESERVATION_INVALID");
        }
    }

    public boolean releaseExact(LocalStagingReservation expected) {
        Objects.requireNonNull(expected, "expected local staging reservation is required");
        AtomicInteger completion = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
        AtomicReference<Boolean> deleted = new AtomicReference<>();
        try {
            requiresNew.executeWithoutResult(status -> {
                registerCompletion(completion);
                repository.acquireCapacityLock();
                requireConfiguredPolicy();
                deleted.set(repository.deleteExact(expected));
            });
        } catch (RuntimeException failure) {
            if (isFixed(failure, "LOCAL_STAGING_POLICY_MISMATCH")
                    || isFixed(failure, "LOCAL_STAGING_VOLUME_MISMATCH")
                    || isFixed(failure, "LOCAL_STAGING_RESERVATION_COMMIT_UNKNOWN")) {
                throw failure;
            }
            throw fixed("LOCAL_STAGING_RESERVATION_RELEASE_FAILED");
        }
        if (completion.get() != TransactionSynchronization.STATUS_COMMITTED
                || deleted.get() == null) {
            throw fixed("LOCAL_STAGING_RESERVATION_COMMIT_UNKNOWN");
        }
        return deleted.get();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LocalStagingReservation lockCurrentForOuterTransaction(
            LocalStagingReservation expected) {
        Objects.requireNonNull(expected, "expected local staging reservation is required");
        try {
            requireConfiguredPolicy();
            LocalStagingReservation current = repository
                    .findByAssetIdForUpdate(expected.assetId())
                    .orElseThrow(() -> fixed("LOCAL_STAGING_RESERVATION_INVALID"));
            if (!current.equals(expected)) {
                throw fixed("LOCAL_STAGING_RESERVATION_INVALID");
            }
            return current;
        } catch (RuntimeException failure) {
            if (isFixed(failure, "LOCAL_STAGING_RESERVATION_INVALID")
                    || isFixed(failure, "LOCAL_STAGING_POLICY_MISMATCH")
                    || isFixed(failure, "LOCAL_STAGING_VOLUME_MISMATCH")) {
                throw failure;
            }
            throw fixed("LOCAL_STAGING_RESERVATION_INVALID");
        }
    }

    private void requireConfiguredPolicy() {
        LocalStagingPolicy stored = repository.findPolicy()
                .orElseThrow(() -> fixed("LOCAL_STAGING_POLICY_MISMATCH"));
        if (!stored.equals(configuredPolicy)) {
            throw fixed("LOCAL_STAGING_POLICY_MISMATCH");
        }
        String volumeId = currentVolumeId();
        if (!repository.volumeMatches(volumeId)) {
            throw fixed("LOCAL_STAGING_VOLUME_MISMATCH");
        }
    }

    private String currentVolumeId() {
        String volumeId = storage.volumeId();
        if (volumeId == null || !SHA256.matcher(volumeId).matches()) {
            throw fixed("LOCAL_STAGING_VOLUME_MISMATCH");
        }
        return volumeId;
    }

    private static void registerCompletion(AtomicInteger completion) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw fixed("LOCAL_STAGING_RESERVATION_COMMIT_UNKNOWN");
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        completion.set(status);
                    }
                });
    }

    private static void requireReservationIdentity(
            UUID assetId, String sha256, String mimeType) {
        if (!validReservationIdentity(assetId, sha256, mimeType)) {
            throw new IllegalArgumentException("local staging reservation identity is invalid");
        }
    }

    private static boolean validReservationIdentity(
            UUID assetId, String sha256, String mimeType) {
        return assetId != null
                && sha256 != null
                && SHA256.matcher(sha256).matches()
                && MIME_TYPES.contains(mimeType);
    }

    private static IllegalStateException fixed(String code) {
        return new IllegalStateException(code);
    }

    private static boolean isFixed(RuntimeException failure, String code) {
        return failure instanceof IllegalStateException
                && code.equals(failure.getMessage())
                && failure.getCause() == null;
    }
}
