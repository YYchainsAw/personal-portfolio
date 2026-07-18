package xyz.yychainsaw.portfolio.media.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.api.admin.media.MediaPageView;
import xyz.yychainsaw.portfolio.api.admin.media.MediaTranslationInput;
import xyz.yychainsaw.portfolio.api.admin.media.MediaTranslationView;
import xyz.yychainsaw.portfolio.api.admin.media.MediaVariantView;
import xyz.yychainsaw.portfolio.api.admin.media.StrictHttpsSourceUrl;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetPage;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class MediaManagementService {
    private static final Set<String> REQUIRED_LOCALES = Set.of("zh-CN", "en");
    private static final int MAXIMUM_PAGE_SIZE = 100;
    private static final Comparator<MediaVariantRecord> VARIANT_ORDER =
            Comparator.comparingInt((MediaVariantRecord record) ->
                            record.width() == null ? 0 : record.width())
                    .thenComparing(MediaVariantRecord::variantName);

    private final CurrentAdminProvider currentAdmin;
    private final MediaAssetRepository assets;
    private final MediaVariantRepository variants;
    private final MediaTranslationRepository translations;
    private final MediaReferenceResolver references;
    private final List<MediaChangeListener> listeners;
    private final AuditService audit;
    private final TransactionOperations readTransactions;
    private final TransactionOperations writeTransactions;

    @Autowired
    public MediaManagementService(
            CurrentAdminProvider currentAdmin,
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            MediaTranslationRepository translations,
            MediaReferenceResolver references,
            List<MediaChangeListener> listeners,
            AuditService audit,
            PlatformTransactionManager transactionManager) {
        this(
                currentAdmin,
                assets,
                variants,
                translations,
                references,
                listeners,
                audit,
                newReadTransactions(transactionManager),
                newWriteTransactions(transactionManager));
    }

    MediaManagementService(
            CurrentAdminProvider currentAdmin,
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            MediaTranslationRepository translations,
            MediaReferenceResolver references,
            List<MediaChangeListener> listeners,
            AuditService audit,
            TransactionOperations transactions) {
        this(
                currentAdmin,
                assets,
                variants,
                translations,
                references,
                listeners,
                audit,
                transactions,
                transactions);
    }

    MediaManagementService(
            CurrentAdminProvider currentAdmin,
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            MediaTranslationRepository translations,
            MediaReferenceResolver references,
            List<MediaChangeListener> listeners,
            AuditService audit,
            TransactionOperations readTransactions,
            TransactionOperations writeTransactions) {
        this.currentAdmin = Objects.requireNonNull(
                currentAdmin, "current administrator provider is required");
        this.assets = Objects.requireNonNull(assets, "media repository is required");
        this.variants = Objects.requireNonNull(
                variants, "media variant repository is required");
        this.translations = Objects.requireNonNull(
                translations, "media translation repository is required");
        this.references = Objects.requireNonNull(
                references, "media reference resolver is required");
        this.listeners = List.copyOf(Objects.requireNonNull(
                listeners, "media change listeners are required"));
        if (this.listeners.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("media change listener is invalid");
        }
        this.audit = Objects.requireNonNull(audit, "audit service is required");
        this.readTransactions = Objects.requireNonNull(
                readTransactions, "read transaction operations are required");
        this.writeTransactions = Objects.requireNonNull(
                writeTransactions, "write transaction operations are required");
    }

    public MediaPageView list(int page, int size, String requestedStatus) {
        Optional<MediaStatus> status = requirePageRequest(page, size, requestedStatus);
        MediaPageView result = readTransactions.execute(transaction -> listSnapshot(
                page, size, status));
        return Objects.requireNonNull(result, "media page transaction returned no result");
    }

    private MediaPageView listSnapshot(
            int page, int size, Optional<MediaStatus> status) {
        MediaAssetPage result = assets.findPage(page, size, status);
        List<MediaAssetView> items = result.items().stream()
                .map(this::toDetailedView)
                .toList();
        long pages = result.totalItems() / size
                + (result.totalItems() % size == 0 ? 0 : 1);
        if (pages > Integer.MAX_VALUE) {
            throw queryInvalid();
        }
        return new MediaPageView(
                items, page, size, result.totalItems(), (int) pages);
    }

    public MediaAssetView get(UUID assetId) {
        if (assetId == null) {
            throw notFound();
        }
        MediaAssetView result = readTransactions.execute(transaction -> {
            MediaAssetRecord record = assets.findById(assetId)
                    .filter(asset -> asset.status() != MediaStatus.PENDING_DELETE)
                    .orElseThrow(MediaManagementService::notFound);
            return toDetailedView(record);
        });
        return Objects.requireNonNull(result, "media detail transaction returned no result");
    }

    public MediaAssetView updateTranslations(
            UUID assetId,
            long expectedVersion,
            List<MediaTranslationInput> input) {
        if (expectedVersion < 0) {
            throw translationsInvalid();
        }
        List<MediaTranslationRecord> replacements = requireTranslations(assetId, input);
        UUID actorId = requireActor();
        MediaAssetView result = writeTransactions.execute(status -> {
            MediaAssetRecord before = assets.findByIdForUpdate(assetId)
                    .orElseThrow(MediaManagementService::notFound);
            if (before.status() == MediaStatus.PENDING_DELETE) {
                throw notFound();
            }
            if (before.version() != expectedVersion) {
                throw versionConflict();
            }
            translations.replaceAll(assetId, replacements);
            MediaAssetRecord updated = assets.incrementVersion(assetId, expectedVersion)
                    .orElseThrow(MediaManagementService::versionConflict);
            for (MediaChangeListener listener : listeners) {
                listener.onMediaChanged(assetId, MediaChangeType.TRANSLATION_UPDATED);
            }
            audit.record(new AuditCommand(
                    actorId,
                    "MEDIA_TRANSLATIONS_UPDATE",
                    "MEDIA_ASSET",
                    assetId.toString(),
                    AuditOutcome.SUCCESS,
                    null,
                    Map.of()));
            return toDetailedView(updated);
        });
        return Objects.requireNonNull(result, "media translation transaction returned no result");
    }

    public void archive(UUID assetId) {
        if (assetId == null) {
            throw notFound();
        }
        UUID actorId = requireActor();
        writeTransactions.execute(status -> {
            MediaAssetRecord current = assets.findByIdForUpdate(assetId)
                    .orElseThrow(MediaManagementService::notFound);
            if (current.status() == MediaStatus.PENDING_DELETE) {
                throw notFound();
            }
            if (current.status() == MediaStatus.ARCHIVED) {
                return null;
            }
            if (current.status() == MediaStatus.PROCESSING) {
                throw notReady();
            }
            if (!references.findReferences(assetId).isEmpty()) {
                throw new DomainException(
                        "MEDIA_STILL_REFERENCED", HttpStatus.CONFLICT, Map.of());
            }
            assets.archive(assetId, current.version())
                    .orElseThrow(MediaManagementService::conflict);
            audit.record(new AuditCommand(
                    actorId,
                    "MEDIA_ARCHIVE",
                    "MEDIA_ASSET",
                    assetId.toString(),
                    AuditOutcome.SUCCESS,
                    null,
                    Map.of()));
            return null;
        });
    }

    private Optional<MediaStatus> requirePageRequest(
            int page, int size, String requestedStatus) {
        if (page < 0 || size <= 0 || size > MAXIMUM_PAGE_SIZE) {
            throw queryInvalid();
        }
        if (requestedStatus == null) {
            return Optional.empty();
        }
        if (requestedStatus.isBlank() || !requestedStatus.equals(requestedStatus.trim())) {
            throw queryInvalid();
        }
        try {
            return Optional.of(MediaStatus.valueOf(requestedStatus));
        } catch (IllegalArgumentException invalid) {
            throw queryInvalid();
        }
    }

    private static List<MediaTranslationRecord> requireTranslations(
            UUID assetId, List<MediaTranslationInput> input) {
        if (assetId == null || input == null || input.size() != REQUIRED_LOCALES.size()) {
            throw translationsInvalid();
        }
        List<MediaTranslationRecord> records = new ArrayList<>(input.size());
        Set<String> locales = new HashSet<>();
        try {
            for (MediaTranslationInput value : input) {
                if (value == null
                        || !REQUIRED_LOCALES.contains(value.locale())
                        || !locales.add(value.locale())) {
                    throw translationsInvalid();
                }
                String sourceUrl = requireHttpsSource(value.sourceUrl());
                records.add(new MediaTranslationRecord(
                        assetId,
                        value.locale(),
                        Objects.requireNonNullElse(value.altText(), ""),
                        Objects.requireNonNullElse(value.caption(), ""),
                        Objects.requireNonNullElse(value.credit(), ""),
                        sourceUrl));
            }
        } catch (DomainException failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw translationsInvalid();
        }
        if (!locales.equals(REQUIRED_LOCALES)) {
            throw translationsInvalid();
        }
        return List.copyOf(records);
    }

    private static String requireHttpsSource(String value) {
        try {
            return StrictHttpsSourceUrl.requireValidNullable(value);
        } catch (IllegalArgumentException invalid) {
            throw translationsInvalid();
        }
    }

    private MediaAssetView toDetailedView(MediaAssetRecord record) {
        List<MediaTranslationView> translationViews = translations
                .findByAssetId(record.id())
                .stream()
                .map(value -> new MediaTranslationView(
                        value.locale(),
                        value.altText(),
                        value.caption(),
                        value.credit(),
                        value.sourceUrl()))
                .toList();
        List<MediaVariantRecord> variantRecords =
                new ArrayList<>(variants.findByAssetId(record.id()));
        variantRecords.sort(VARIANT_ORDER);
        List<MediaVariantView> variantViews = variantRecords.stream()
                .map(value -> new MediaVariantView(
                        value.variantName(),
                        value.width(),
                        value.height(),
                        value.status()))
                .toList();
        return new MediaAssetView(
                record.id(),
                record.originalFilename(),
                record.mimeType(),
                record.byteSize(),
                record.width(),
                record.height(),
                record.sha256(),
                record.status().name(),
                record.version(),
                record.createdAt(),
                record.updatedAt(),
                translationViews,
                variantViews);
    }

    private UUID requireActor() {
        return Objects.requireNonNull(
                currentAdmin.requireAdminId(), "current administrator id is required");
    }

    private static DomainException queryInvalid() {
        return new DomainException(
                "MEDIA_QUERY_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, Map.of());
    }

    private static DomainException translationsInvalid() {
        return new DomainException(
                "MEDIA_TRANSLATIONS_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of());
    }

    private static DomainException notFound() {
        return new DomainException("MEDIA_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of());
    }

    private static DomainException notReady() {
        return new DomainException(
                "MEDIA_NOT_READY", HttpStatus.UNPROCESSABLE_ENTITY, Map.of());
    }

    private static DomainException conflict() {
        return new DomainException("MEDIA_CONFLICT", HttpStatus.CONFLICT, Map.of());
    }

    private static DomainException versionConflict() {
        return new DomainException(
                "MEDIA_VERSION_CONFLICT", HttpStatus.CONFLICT, Map.of());
    }

    private static TransactionOperations newReadTransactions(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate transactions = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transaction manager is required"));
        transactions.setName("media-management-read");
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transactions.setReadOnly(true);
        return transactions;
    }

    private static TransactionOperations newWriteTransactions(
            PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transaction manager is required"));
    }
}
