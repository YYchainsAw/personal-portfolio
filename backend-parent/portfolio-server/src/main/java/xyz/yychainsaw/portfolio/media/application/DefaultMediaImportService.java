package xyz.yychainsaw.portfolio.media.application;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.api.admin.media.StrictHttpsSourceUrl;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public class DefaultMediaImportService implements MediaImportService {
    private static final Set<String> REQUIRED_LOCALES = Set.of("zh-CN", "en");
    private static final Comparator<MediaVariantRecord> VARIANT_ORDER =
            Comparator.comparingInt((MediaVariantRecord value) ->
                            value.width() == null ? 0 : value.width())
                    .thenComparing(MediaVariantRecord::variantName);

    private final MediaFileInspector inspector;
    private final StorageRouter storageRouter;
    private final MediaAssetRepository assets;
    private final MediaVariantRepository variants;
    private final MediaTranslationRepository translations;
    private final BackgroundJobService jobs;
    private final LocalMediaIngestCoordinator localIngest;
    private final Supplier<UUID> uuidGenerator;
    private final TransactionTemplate transactionSuspension;

    @Autowired
    public DefaultMediaImportService(
            MediaFileInspector inspector,
            StorageRouter storageRouter,
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            MediaTranslationRepository translations,
            BackgroundJobService jobs,
            LocalMediaIngestCoordinator localIngest,
            PlatformTransactionManager transactionManager) {
        this(
                inspector,
                storageRouter,
                assets,
                variants,
                translations,
                jobs,
                localIngest,
                UUID::randomUUID,
                newTransactionSuspension(transactionManager));
    }

    DefaultMediaImportService(
            MediaFileInspector inspector,
            StorageRouter storageRouter,
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            MediaTranslationRepository translations,
            BackgroundJobService jobs,
            LocalMediaIngestCoordinator localIngest,
            Supplier<UUID> uuidGenerator) {
        this(
                inspector,
                storageRouter,
                assets,
                variants,
                translations,
                jobs,
                localIngest,
                uuidGenerator,
                (TransactionTemplate) null);
    }

    DefaultMediaImportService(
            MediaFileInspector inspector,
            StorageRouter storageRouter,
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            MediaTranslationRepository translations,
            BackgroundJobService jobs,
            LocalMediaIngestCoordinator localIngest,
            Supplier<UUID> uuidGenerator,
            PlatformTransactionManager transactionManager) {
        this(
                inspector,
                storageRouter,
                assets,
                variants,
                translations,
                jobs,
                localIngest,
                uuidGenerator,
                newTransactionSuspension(transactionManager));
    }

    private DefaultMediaImportService(
            MediaFileInspector inspector,
            StorageRouter storageRouter,
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            MediaTranslationRepository translations,
            BackgroundJobService jobs,
            LocalMediaIngestCoordinator localIngest,
            Supplier<UUID> uuidGenerator,
            TransactionTemplate transactionSuspension) {
        this.inspector = Objects.requireNonNull(inspector, "media inspector is required");
        this.storageRouter = Objects.requireNonNull(
                storageRouter, "storage router is required");
        this.assets = Objects.requireNonNull(assets, "media repository is required");
        this.variants = Objects.requireNonNull(
                variants, "media variant repository is required");
        this.translations = Objects.requireNonNull(
                translations, "media translation repository is required");
        this.jobs = Objects.requireNonNull(jobs, "background jobs are required");
        this.localIngest = Objects.requireNonNull(
                localIngest, "local media ingest is required");
        this.uuidGenerator = Objects.requireNonNull(
                uuidGenerator, "UUID generator is required");
        this.transactionSuspension = transactionSuspension;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ImportedMedia importLocal(ImportMediaCommand command) {
        ImportMetadata metadata = requireMetadata(command);
        ResolvedImport source = resolveSource(command);
        requireAmbientTransaction();

        InspectedMedia inspected = inspect(source);
        try (inspected) {
            ImportedMedia reused = findReusable(inspected, metadata);
            if (reused != null) {
                return reused;
            }
            return publishNew(inspected, metadata);
        } catch (IOException | RuntimeException failure) {
            throw importFailed();
        }
    }

    private InspectedMedia inspect(ResolvedImport source) {
        InputStream input;
        try {
            input = Files.newInputStream(
                    source.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
        return inspector.inspect(new UploadMediaCommand(
                source.filename(),
                source.mimeType(),
                source.byteSize(),
                input));
    }

    private ImportedMedia findReusable(
            InspectedMedia inspected, ImportMetadata metadata) {
        assets.lockImportSha256(inspected.sha256());
        List<MediaAssetRecord> candidates = assets.findImportCandidatesBySha256ForShare(
                inspected.sha256());
        for (MediaAssetRecord candidate : candidates) {
            if (candidate.status() != MediaStatus.READY
                    && candidate.status() != MediaStatus.PROCESSING) {
                continue;
            }
            if (!exactTranslations(candidate.id(), metadata)) {
                continue;
            }
            if (candidate.status() == MediaStatus.PROCESSING) {
                return new ImportedMedia(candidate.id(), candidate.sha256(), List.of());
            }
            List<MediaVariantRecord> ready = new ArrayList<>(
                    variants.findByAssetId(candidate.id()).stream()
                            .filter(value -> "READY".equals(value.status()))
                            .toList());
            ready.sort(VARIANT_ORDER);
            return new ImportedMedia(
                    candidate.id(),
                    candidate.sha256(),
                    ready.stream().map(MediaVariantRecord::variantName).toList());
        }
        return null;
    }

    private boolean exactTranslations(UUID assetId, ImportMetadata metadata) {
        List<MediaTranslationRecord> actual = translations.findByAssetId(assetId);
        List<MediaTranslationRecord> expected = translations(assetId, metadata);
        return actual.size() == expected.size()
                && new HashSet<>(actual).equals(new HashSet<>(expected));
    }

    private ImportedMedia publishNew(
            InspectedMedia inspected, ImportMetadata metadata) throws IOException {
        Writer writer = requireWriter();
        UUID assetId = requireAssetId();
        String stagingKey = MediaObjectKeys.stagingKey(
                assetId, inspected.sha256(), inspected.mimeType());
        String originalKey = MediaObjectKeys.originalKey(
                assetId, inspected.sha256(), inspected.mimeType());

        if (writer.location().provider() == StorageProvider.LOCAL) {
            return publishLocal(
                    writer,
                    assetId,
                    stagingKey,
                    originalKey,
                    inspected,
                    metadata);
        }
        StoredObject staged = writer.storage().put(
                stagingKey,
                inspected.openStream(),
                inspected.byteSize(),
                inspected.mimeType());
        requirePublished(staged, writer.location(), stagingKey, inspected);
        inspected.close();
        return persistNew(
                staged, assetId, originalKey, inspected, metadata);
    }

    private ImportedMedia publishLocal(
            Writer writer,
            UUID assetId,
            String stagingKey,
            String originalKey,
            InspectedMedia inspected,
            ImportMetadata metadata) throws IOException {
        LocalMediaIngestSession session = openLocalSession(
                writer.storage(),
                writer.location(),
                assetId,
                stagingKey,
                inspected.sha256(),
                inspected.mimeType());
        LocalImportCompletion completion = registerLocalCompletion(session);
        session.prepareOuterTransaction();
        completion.publishingStarted();
        StoredObject staged = session.publish(
                inspected.openStream(), inspected.byteSize());
        requirePublished(staged, writer.location(), stagingKey, inspected);
        completion.published();
        inspected.close();
        return persistNew(
                staged, assetId, originalKey, inspected, metadata);
    }

    private LocalMediaIngestSession openLocalSession(
            StorageService storage,
            StorageLocation location,
            UUID assetId,
            String stagingKey,
            String sha256,
            String mimeType) {
        if (transactionSuspension == null) {
            return requireLocalSession(localIngest.open(
                    storage, location, assetId, stagingKey, sha256, mimeType));
        }
        return requireLocalSession(transactionSuspension.execute(status ->
                openWithoutTransactionSynchronization(
                        storage, location, assetId, stagingKey, sha256, mimeType)));
    }

    private LocalMediaIngestSession openWithoutTransactionSynchronization(
            StorageService storage,
            StorageLocation location,
            UUID assetId,
            String stagingKey,
            String sha256,
            String mimeType) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw invalidSuspension();
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getSynchronizations().isEmpty()) {
            throw invalidSuspension();
        }
        TransactionSynchronizationManager.clearSynchronization();
        LocalMediaIngestSession session = null;
        RuntimeException failure = null;
        try {
            requireNoTransactionState();
            session = requireLocalSession(localIngest.open(
                    storage, location, assetId, stagingKey, sha256, mimeType));
            requireNoTransactionState();
        } catch (RuntimeException openFailure) {
            failure = openFailure;
        }

        if (failure != null) {
            if (session != null) {
                cleanupUnregisteredSession(session, failure);
            }
            restoreSyntheticSynchronization(failure);
            throw failure;
        }

        try {
            restoreSyntheticSynchronization(null);
            return session;
        } catch (RuntimeException restorationFailure) {
            cleanupUnregisteredSession(session, restorationFailure);
            throw restorationFailure;
        }
    }

    private static void requireNoTransactionState() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw invalidSuspension();
        }
    }

    private static void restoreSyntheticSynchronization(RuntimeException primary) {
        try {
            requireNoTransactionState();
            TransactionSynchronizationManager.initSynchronization();
        } catch (RuntimeException restorationFailure) {
            if (primary == null) {
                throw restorationFailure;
            }
            primary.addSuppressed(restorationFailure);
        }
    }

    private static void cleanupUnregisteredSession(
            LocalMediaIngestSession session, RuntimeException primary) {
        try {
            session.cleanupKnownRollback();
        } catch (RuntimeException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
        try {
            session.close();
        } catch (RuntimeException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    private static LocalMediaIngestSession requireLocalSession(
            LocalMediaIngestSession session) {
        return Objects.requireNonNull(session, "local media ingest session is required");
    }

    private static LocalImportCompletion registerLocalCompletion(
            LocalMediaIngestSession session) {
        LocalImportCompletion completion = new LocalImportCompletion(session);
        try {
            TransactionSynchronizationManager.registerSynchronization(completion);
            return completion;
        } catch (RuntimeException registrationFailure) {
            completion.cleanupBeforeRegistration();
            throw registrationFailure;
        }
    }

    private ImportedMedia persistNew(
            StoredObject staged,
            UUID assetId,
            String originalKey,
            InspectedMedia inspected,
            ImportMetadata metadata) {
        assets.insertProcessing(new MediaAssetRecord.Insert(
                assetId,
                staged.provider(),
                staged.bucket(),
                staged.region(),
                originalKey,
                inspected.originalFilename(),
                inspected.mimeType(),
                inspected.byteSize(),
                inspected.width(),
                inspected.height(),
                inspected.sha256()));
        translations.replaceAll(assetId, translations(assetId, metadata));
        jobs.enqueue(
                "FINALIZE_MEDIA_UPLOAD",
                "media-finalize:" + assetId,
                Map.of("assetId", assetId.toString()));
        return new ImportedMedia(assetId, inspected.sha256(), List.of());
    }

    private Writer requireWriter() {
        StorageService storage = Objects.requireNonNull(
                storageRouter.defaultWriter(), "storage writer is unavailable");
        StorageLocation location = Objects.requireNonNull(
                storage.location(), "storage location is unavailable");
        if (storage.provider() != location.provider()) {
            throw importFailed();
        }
        return new Writer(storage, location);
    }

    private UUID requireAssetId() {
        return Objects.requireNonNull(
                uuidGenerator.get(), "UUID generator returned no value");
    }

    private static void requirePublished(
            StoredObject staged,
            StorageLocation location,
            String stagingKey,
            InspectedMedia inspected) {
        if (staged == null
                || staged.provider() != location.provider()
                || !Objects.equals(staged.bucket(), location.bucket())
                || !Objects.equals(staged.region(), location.region())
                || !stagingKey.equals(staged.objectKey())
                || staged.contentLength() != inspected.byteSize()
                || !inspected.mimeType().equals(staged.contentType())) {
            throw importFailed();
        }
    }

    private static List<MediaTranslationRecord> translations(
            UUID assetId, ImportMetadata metadata) {
        return REQUIRED_LOCALES.stream()
                .sorted()
                .map(locale -> new MediaTranslationRecord(
                        assetId,
                        locale,
                        metadata.altByLocale().get(locale),
                        null,
                        metadata.credit(),
                        metadata.sourceUrl()))
                .toList();
    }

    private static ImportMetadata requireMetadata(ImportMediaCommand command) {
        if (command == null
                || !boundedText(command.usage(), 120)
                || !boundedText(command.objectPosition(), 120)
                || command.altByLocale() == null
                || !command.altByLocale().keySet().equals(REQUIRED_LOCALES)) {
            throw invalid();
        }
        for (String alt : command.altByLocale().values()) {
            if (alt == null || alt.length() > 500) {
                throw invalid();
            }
        }
        String credit = command.credit();
        if (credit != null && credit.length() > 300) {
            throw invalid();
        }
        String sourceUrl = requireSourceUrl(command.sourceUrl());
        return new ImportMetadata(
                Map.copyOf(command.altByLocale()), credit, sourceUrl);
    }

    private static String requireSourceUrl(URI sourceUrl) {
        if (sourceUrl == null) {
            return null;
        }
        String value = sourceUrl.toASCIIString();
        try {
            return StrictHttpsSourceUrl.requireValidNullable(value);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    private static ResolvedImport resolveSource(ImportMediaCommand command) {
        if (command == null
                || command.assetRoot() == null
                || command.publicPath() == null) {
            throw invalid();
        }
        String publicPath = command.publicPath();
        if (publicPath.isBlank()
                || !publicPath.equals(publicPath.trim())
                || publicPath.indexOf('\\') >= 0
                || hasControl(publicPath)) {
            throw invalid();
        }
        String relativeText = publicPath.startsWith("/")
                ? publicPath.substring(1)
                : publicPath;
        if (relativeText.isEmpty()) {
            throw invalid();
        }

        try {
            Path root = command.assetRoot().toRealPath();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid();
            }
            Path relative = Path.of(relativeText);
            if (relative.isAbsolute()) {
                throw invalid();
            }
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) {
                throw invalid();
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)
                    || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(real)) {
                throw invalid();
            }
            long byteSize = Files.size(real);
            if (byteSize <= 0) {
                throw invalid();
            }
            Path filename = real.getFileName();
            if (filename == null) {
                throw invalid();
            }
            return new ResolvedImport(
                    real,
                    filename.toString(),
                    mimeType(filename.toString()),
                    byteSize);
        } catch (DomainException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    private static String mimeType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        throw invalid();
    }

    private static boolean boundedText(String value, int maximum) {
        return value != null
                && !value.isBlank()
                && value.length() <= maximum
                && value.equals(value.trim())
                && !hasControl(value);
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static void requireAmbientTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("MEDIA_IMPORT_TRANSACTION_REQUIRED");
        }
    }

    private static TransactionTemplate newTransactionSuspension(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transaction manager is required"));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        template.setName("media-import-local-open");
        return template;
    }

    private static IllegalStateException invalidSuspension() {
        return new IllegalStateException("MEDIA_IMPORT_SUSPENSION_INVALID");
    }

    private static DomainException invalid() {
        return new DomainException(
                "MEDIA_IMPORT_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, Map.of());
    }

    private static DomainException importFailed() {
        return new DomainException(
                "MEDIA_IMPORT_FAILED", HttpStatus.INTERNAL_SERVER_ERROR, Map.of());
    }

    private record ImportMetadata(
            Map<String, String> altByLocale,
            String credit,
            String sourceUrl) {}

    private record ResolvedImport(
            Path path,
            String filename,
            String mimeType,
            long byteSize) {}

    private record Writer(StorageService storage, StorageLocation location) {}

    private enum PublicationConfidence {
        ABSENT,
        UNKNOWN,
        OWNED
    }

    private static final class LocalImportCompletion implements TransactionSynchronization {
        private final LocalMediaIngestSession session;
        private final AtomicReference<PublicationConfidence> publication =
                new AtomicReference<>(PublicationConfidence.ABSENT);

        private LocalImportCompletion(LocalMediaIngestSession session) {
            this.session = session;
        }

        private void publishingStarted() {
            publication.set(PublicationConfidence.UNKNOWN);
        }

        private void published() {
            publication.set(PublicationConfidence.OWNED);
        }

        private void cleanupBeforeRegistration() {
            cleanupKnownRollback();
            close();
        }

        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) {
                cleanupKnownRollback();
            }
            close();
        }

        private void cleanupKnownRollback() {
            if (publication.get() == PublicationConfidence.UNKNOWN) {
                return;
            }
            try {
                session.cleanupKnownRollback();
            } catch (RuntimeException ignored) {
                // Unknown cleanup outcome deliberately retains the durable reservation.
            }
        }

        private void close() {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // Transaction outcome is already fixed; durable cleanup can recover later.
            }
        }
    }
}
