package xyz.yychainsaw.portfolio.media.application;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class MediaIngestService {
    private static final Duration MINIMUM_TRANSACTION_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_TRANSACTION_TIMEOUT = Duration.ofSeconds(120);

    private final MediaFileInspector inspector;
    private final StorageRouter storageRouter;
    private final MediaAssetRepository assets;
    private final BackgroundJobService jobs;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final Supplier<UUID> uuidGenerator;
    private final LocalMediaIngestCoordinator localIngest;

    @Autowired
    public MediaIngestService(
            MediaFileInspector inspector,
            StorageRouter storageRouter,
            MediaAssetRepository assets,
            BackgroundJobService jobs,
            AuditService audit,
            PlatformTransactionManager transactionManager,
            LocalMediaIngestCoordinator localIngest,
            @Value("${portfolio.media.ingest.transaction-timeout:PT30S}")
                    String transactionTimeout) {
        this(
                inspector,
                storageRouter,
                assets,
                jobs,
                audit,
                newTransactions(transactionManager, transactionTimeout),
                UUID::randomUUID,
                Objects.requireNonNull(localIngest, "local media ingest is required"),
                true);
    }

    MediaIngestService(
            MediaFileInspector inspector,
            StorageRouter storageRouter,
            MediaAssetRepository assets,
            BackgroundJobService jobs,
            AuditService audit,
            TransactionTemplate transactions,
            Supplier<UUID> uuidGenerator) {
        this(
                inspector,
                storageRouter,
                assets,
                jobs,
                audit,
                transactions,
                uuidGenerator,
                null,
                false);
    }

    MediaIngestService(
            MediaFileInspector inspector,
            StorageRouter storageRouter,
            MediaAssetRepository assets,
            BackgroundJobService jobs,
            AuditService audit,
            TransactionTemplate transactions,
            Supplier<UUID> uuidGenerator,
            LocalMediaIngestCoordinator localIngest) {
        this(
                inspector,
                storageRouter,
                assets,
                jobs,
                audit,
                transactions,
                uuidGenerator,
                localIngest,
                true);
    }

    private MediaIngestService(
            MediaFileInspector inspector,
            StorageRouter storageRouter,
            MediaAssetRepository assets,
            BackgroundJobService jobs,
            AuditService audit,
            TransactionTemplate transactions,
            Supplier<UUID> uuidGenerator,
            LocalMediaIngestCoordinator localIngest,
            boolean requireLocalIngest) {
        this.inspector = Objects.requireNonNull(inspector, "media inspector is required");
        this.storageRouter =
                Objects.requireNonNull(storageRouter, "storage router is required");
        this.assets = Objects.requireNonNull(assets, "media repository is required");
        this.jobs = Objects.requireNonNull(jobs, "job service is required");
        this.audit = Objects.requireNonNull(audit, "audit service is required");
        this.transactions =
                Objects.requireNonNull(transactions, "transaction template is required");
        if (transactions.getPropagationBehavior()
                != TransactionDefinition.PROPAGATION_REQUIRED) {
            throw new IllegalArgumentException(
                    "media transaction template must use REQUIRED propagation");
        }
        this.uuidGenerator =
                Objects.requireNonNull(uuidGenerator, "UUID generator is required");
        this.localIngest = requireLocalIngest
                ? Objects.requireNonNull(localIngest, "local media ingest is required")
                : localIngest;
    }

    static TransactionTemplate newTransactions(
            PlatformTransactionManager transactionManager, String timeoutValue) {
        PlatformTransactionManager requiredManager = Objects.requireNonNull(
                transactionManager, "transaction manager is required");
        Duration timeout;
        try {
            if (timeoutValue == null || !timeoutValue.equals(timeoutValue.trim())) {
                throw invalidTransactionTimeout();
            }
            timeout = Duration.parse(timeoutValue);
            if (timeout.compareTo(MINIMUM_TRANSACTION_TIMEOUT) < 0
                    || timeout.compareTo(MAXIMUM_TRANSACTION_TIMEOUT) > 0
                    || timeout.getNano() != 0) {
                throw invalidTransactionTimeout();
            }
        } catch (RuntimeException invalid) {
            throw invalidTransactionTimeout();
        }
        TransactionTemplate transactions = new TransactionTemplate(requiredManager);
        transactions.setName("media-ingest");
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(Math.toIntExact(timeout.getSeconds()));
        return transactions;
    }

    public MediaAssetView ingest(UploadMediaCommand command, UUID actorId) {
        if (command == null) {
            throw requestInvalid();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            closeInboundBestEffort(command);
            throw new IllegalStateException("media ingest requires no ambient transaction");
        }
        if (actorId == null) {
            closeInboundBestEffort(command);
            throw new IllegalArgumentException("media actor is required");
        }
        if (command.input() == null) {
            throw requestInvalid();
        }

        InspectedMedia media = inspector.inspect(command);
        AtomicBoolean mediaClosed = new AtomicBoolean();
        try {
            Writer writer = requireWriter();
            UUID assetId = requireAssetId();
            String stagingKey = MediaObjectKeys.stagingKey(
                    assetId, media.sha256(), media.mimeType());
            String originalKey = MediaObjectKeys.originalKey(
                    assetId, media.sha256(), media.mimeType());
            if (writer.location().provider() == StorageProvider.LOCAL) {
                return ingestLocal(
                        writer,
                        stagingKey,
                        originalKey,
                        media,
                        mediaClosed,
                        assetId,
                        actorId);
            }
            return ingestRemote(
                    writer,
                    stagingKey,
                    originalKey,
                    media,
                    mediaClosed,
                    assetId,
                    actorId);
        } finally {
            if (!mediaClosed.get()) {
                try {
                    media.close();
                } catch (IOException | RuntimeException ignored) {
                    // Process-level temp maintenance owns refused deletion.
                }
            }
        }
    }

    private Writer requireWriter() {
        try {
            StorageService storage = Objects.requireNonNull(
                    storageRouter.defaultWriter(), "storage writer is unavailable");
            StorageProvider provider = Objects.requireNonNull(
                    storage.provider(), "storage provider is unavailable");
            StorageLocation location = Objects.requireNonNull(
                    storage.location(), "storage location is unavailable");
            if (provider != location.provider()) {
                throw uploadFailed();
            }
            return new Writer(storage, location);
        } catch (RuntimeException dependencyFailure) {
            throw uploadFailed();
        }
    }

    private UUID requireAssetId() {
        try {
            return Objects.requireNonNull(
                    uuidGenerator.get(), "UUID generator returned no value");
        } catch (RuntimeException dependencyFailure) {
            throw uploadFailed();
        }
    }

    private MediaAssetView ingestLocal(
            Writer writer,
            String stagingKey,
            String originalKey,
            InspectedMedia media,
            AtomicBoolean mediaClosed,
            UUID assetId,
            UUID actorId) {
        LocalMediaIngestSession session = null;
        MediaAssetView view = null;
        DomainException failure = null;
        try {
            if (localIngest == null) {
                throw uploadFailed();
            }
            session = localIngest.open(
                    writer.storage(),
                    writer.location(),
                    assetId,
                    stagingKey,
                    media.sha256(),
                    media.mimeType());
            view = persistLocalAtomically(
                    session,
                    writer.location(),
                    stagingKey,
                    originalKey,
                    media,
                    mediaClosed,
                    assetId,
                    actorId);
        } catch (RuntimeException localFailure) {
            failure = uploadFailed();
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException closeFailure) {
                    failure = uploadFailed();
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (view == null) {
            throw uploadFailed();
        }
        return view;
    }

    private MediaAssetView persistLocalAtomically(
            LocalMediaIngestSession session,
            StorageLocation writerLocation,
            String stagingKey,
            String originalKey,
            InspectedMedia media,
            AtomicBoolean mediaClosed,
            UUID assetId,
            UUID actorId) {
        AtomicInteger completion = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
        AtomicReference<PublicationConfidence> publication =
                new AtomicReference<>(PublicationConfidence.ABSENT);
        MediaAssetView view;
        try {
            view = transactions.execute(status -> {
                registerCompletion(completion);
                try {
                    session.prepareOuterTransaction();
                    InputStream stagingInput = media.openStream();
                    publication.set(PublicationConfidence.UNKNOWN);
                    StoredObject staged = session.publish(stagingInput, media.byteSize());
                    if (!matchesPublishedObject(
                            staged,
                            writerLocation,
                            stagingKey,
                            media.byteSize(),
                            media.mimeType())) {
                        throw uploadFailed();
                    }
                    publication.set(PublicationConfidence.OWNED);
                    media.close();
                    mediaClosed.set(true);
                    return persistAssetJobAndAudit(
                            staged, originalKey, media, assetId, actorId);
                } catch (IOException | RuntimeException dependencyFailure) {
                    throw uploadFailed();
                }
            });
        } catch (RuntimeException transactionFailure) {
            cleanupKnownRollback(session, completion.get(), publication.get());
            throw uploadFailed();
        }

        if (completion.get() != TransactionSynchronization.STATUS_COMMITTED) {
            cleanupKnownRollback(session, completion.get(), publication.get());
            throw uploadFailed();
        }
        if (view == null) {
            throw uploadFailed();
        }
        return view;
    }

    private MediaAssetView ingestRemote(
            Writer writer,
            String stagingKey,
            String originalKey,
            InspectedMedia media,
            AtomicBoolean mediaClosed,
            UUID assetId,
            UUID actorId) {
        InputStream stagingInput;
        try {
            stagingInput = media.openStream();
        } catch (IOException | RuntimeException temporaryReadFailure) {
            throw uploadFailed();
        }

        StoredObject staged;
        try {
            staged = writer.storage().put(
                    stagingKey, stagingInput, media.byteSize(), media.mimeType());
        } catch (RuntimeException unknownPublicationOutcome) {
            throw uploadFailed();
        }
        if (!matchesPublishedObject(
                staged,
                writer.location(),
                stagingKey,
                media.byteSize(),
                media.mimeType())) {
            throw uploadFailed();
        }

        AtomicBoolean stagingCleanupAttempted = new AtomicBoolean();
        try {
            media.close();
            mediaClosed.set(true);
        } catch (IOException | RuntimeException temporaryDeleteFailure) {
            deleteStagingOnce(writer.storage(), stagingKey, stagingCleanupAttempted);
            throw uploadFailed();
        }

        return persistRemoteAtomically(
                writer.storage(),
                stagingKey,
                stagingCleanupAttempted,
                staged,
                originalKey,
                media,
                assetId,
                actorId);
    }

    private MediaAssetView persistRemoteAtomically(
            StorageService storage,
            String stagingKey,
            AtomicBoolean stagingCleanupAttempted,
            StoredObject staged,
            String originalKey,
            InspectedMedia media,
            UUID assetId,
            UUID actorId) {
        AtomicBoolean callbackEntered = new AtomicBoolean();
        AtomicReference<CompletionDecision> completion =
                new AtomicReference<>(CompletionDecision.RETAIN);
        MediaAssetView view;
        try {
            view = transactions.execute(status -> {
                callbackEntered.set(true);
                try {
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCompletion(int status) {
                                    completion.set(status == STATUS_ROLLED_BACK
                                            ? CompletionDecision.DELETE
                                            : CompletionDecision.RETAIN);
                                }
                            });
                    return persistAssetJobAndAudit(
                            staged, originalKey, media, assetId, actorId);
                } catch (RuntimeException dependencyFailure) {
                    throw uploadFailed();
                }
            });
        } catch (RuntimeException transactionFailure) {
            if (!callbackEntered.get()
                    || completion.get() == CompletionDecision.DELETE) {
                deleteStagingOnce(storage, stagingKey, stagingCleanupAttempted);
            }
            throw uploadFailed();
        }

        if (completion.get() == CompletionDecision.DELETE) {
            deleteStagingOnce(storage, stagingKey, stagingCleanupAttempted);
            throw uploadFailed();
        }
        if (view == null) {
            throw uploadFailed();
        }
        return view;
    }

    private MediaAssetView persistAssetJobAndAudit(
            StoredObject staged,
            String originalKey,
            InspectedMedia media,
            UUID assetId,
            UUID actorId) {
        MediaAssetRecord record = assets.insertProcessing(
                new MediaAssetRecord.Insert(
                        assetId,
                        staged.provider(),
                        staged.bucket(),
                        staged.region(),
                        originalKey,
                        media.originalFilename(),
                        media.mimeType(),
                        media.byteSize(),
                        media.width(),
                        media.height(),
                        media.sha256()));
        jobs.enqueue(
                "FINALIZE_MEDIA_UPLOAD",
                "media-finalize:" + assetId,
                Map.of("assetId", assetId.toString()));
        audit.record(new AuditCommand(
                actorId,
                "MEDIA_UPLOAD",
                "MEDIA_ASSET",
                assetId.toString(),
                AuditOutcome.SUCCESS,
                null,
                Map.of()));
        return assets.toView(record);
    }

    private static void registerCompletion(AtomicInteger completion) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw uploadFailed();
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        completion.set(status);
                    }
                });
    }

    private static void cleanupKnownRollback(
            LocalMediaIngestSession session,
            int completion,
            PublicationConfidence publication) {
        if (publication == PublicationConfidence.UNKNOWN) {
            return;
        }
        if (publication == PublicationConfidence.OWNED
                && completion != TransactionSynchronization.STATUS_ROLLED_BACK) {
            return;
        }
        try {
            session.cleanupKnownRollback();
        } catch (RuntimeException ignored) {
            // Unknown cleanup or release outcome deliberately retains the reservation.
        }
    }

    private static boolean matchesPublishedObject(
            StoredObject staged,
            StorageLocation writerLocation,
            String stagingKey,
            long byteSize,
            String mimeType) {
        return staged != null
                && staged.provider() == writerLocation.provider()
                && Objects.equals(staged.bucket(), writerLocation.bucket())
                && Objects.equals(staged.region(), writerLocation.region())
                && stagingKey.equals(staged.objectKey())
                && staged.contentLength() == byteSize
                && mimeType.equals(staged.contentType());
    }

    private static void deleteStagingOnce(
            StorageService storage,
            String stagingKey,
            AtomicBoolean attempted) {
        if (!attempted.compareAndSet(false, true)) {
            return;
        }
        try {
            storage.delete(stagingKey);
        } catch (RuntimeException ignored) {
            // Cleanup is deliberately cause-free and never masks the primary failure.
        }
    }

    private static void closeInboundBestEffort(UploadMediaCommand command) {
        if (command.input() == null) {
            return;
        }
        try {
            command.input().close();
        } catch (IOException | RuntimeException ignored) {
            // The fixed validation/transaction boundary failure remains primary.
        }
    }

    private static IllegalArgumentException invalidTransactionTimeout() {
        return new IllegalArgumentException(
                "media ingest transaction timeout is invalid");
    }

    private static DomainException requestInvalid() {
        return new DomainException(
                "MEDIA_REQUEST_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, Map.of());
    }

    private static DomainException uploadFailed() {
        return new DomainException(
                "MEDIA_UPLOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR, Map.of());
    }

    private record Writer(StorageService storage, StorageLocation location) {}

    private enum PublicationConfidence {
        ABSENT,
        UNKNOWN,
        OWNED
    }

    private enum CompletionDecision {
        RETAIN,
        DELETE
    }
}
