package xyz.yychainsaw.portfolio.publishing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaQueryAccessGuard;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.publishing.api.ArchiveProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PublicationResult;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PublishSiteCommand;
import xyz.yychainsaw.portfolio.publishing.api.ReorderCatalogCommand;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.MediaReferenceRow;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.PublicationRow;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.EncodedSnapshot;
import xyz.yychainsaw.portfolio.publishing.snapshot.ProjectCatalogSnapshotMapper;
import xyz.yychainsaw.portfolio.publishing.snapshot.ProjectSnapshotMapper;
import xyz.yychainsaw.portfolio.publishing.snapshot.SiteSnapshotMapper;
import xyz.yychainsaw.portfolio.publishing.snapshot.SnapshotCodec;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

/**
 * Creates immutable publication revisions and advances their pointers in one
 * transaction. Media is always share-locked before any publication or workspace
 * row is update-locked.
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public class PublicationService {
    public static final UUID PROJECT_CATALOG_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final Comparator<UUID> UUID_ORDER =
            Comparator.comparing(UUID::toString);
    private static final Comparator<MediaVariantKey> MEDIA_VARIANT_ORDER =
            Comparator.comparing((MediaVariantKey key) -> key.assetId().toString())
                    .thenComparing(MediaVariantKey::variantName);

    private final CurrentAdminProvider currentAdmin;
    private final SiteWorkspaceRepository sites;
    private final ProjectWorkspaceRepository projects;
    private final PublishingRepository publishing;
    private final PublicationValidator validator;
    private final SiteSnapshotMapper siteSnapshots;
    private final ProjectSnapshotMapper projectSnapshots;
    private final ProjectCatalogSnapshotMapper catalogSnapshots;
    private final SnapshotCodec codec;
    private final MediaQueryService media;
    private final MediaQueryAccessGuard mediaAccess;
    private final AuditService audit;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Supplier<UUID> revisionIds;

    @Autowired
    public PublicationService(
            CurrentAdminProvider currentAdmin,
            SiteWorkspaceRepository sites,
            ProjectWorkspaceRepository projects,
            PublishingRepository publishing,
            PublicationValidator validator,
            SiteSnapshotMapper siteSnapshots,
            ProjectSnapshotMapper projectSnapshots,
            ProjectCatalogSnapshotMapper catalogSnapshots,
            SnapshotCodec codec,
            MediaQueryService media,
            MediaQueryAccessGuard mediaAccess,
            AuditService audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(
                currentAdmin,
                sites,
                projects,
                publishing,
                validator,
                siteSnapshots,
                projectSnapshots,
                catalogSnapshots,
                codec,
                media,
                mediaAccess,
                audit,
                new TransactionTemplate(Objects.requireNonNull(
                        transactionManager, "transaction manager is required")),
                clock,
                UUID::randomUUID);
    }

    PublicationService(
            CurrentAdminProvider currentAdmin,
            SiteWorkspaceRepository sites,
            ProjectWorkspaceRepository projects,
            PublishingRepository publishing,
            PublicationValidator validator,
            SiteSnapshotMapper siteSnapshots,
            ProjectSnapshotMapper projectSnapshots,
            ProjectCatalogSnapshotMapper catalogSnapshots,
            SnapshotCodec codec,
            MediaQueryService media,
            MediaQueryAccessGuard mediaAccess,
            AuditService audit,
            TransactionOperations transactions,
            Clock clock,
            Supplier<UUID> revisionIds) {
        this.currentAdmin = Objects.requireNonNull(
                currentAdmin, "current administrator provider is required");
        this.sites = Objects.requireNonNull(sites, "site repository is required");
        this.projects = Objects.requireNonNull(projects, "project repository is required");
        this.publishing = Objects.requireNonNull(
                publishing, "publishing repository is required");
        this.validator = Objects.requireNonNull(
                validator, "publication validator is required");
        this.siteSnapshots = Objects.requireNonNull(
                siteSnapshots, "site snapshot mapper is required");
        this.projectSnapshots = Objects.requireNonNull(
                projectSnapshots, "project snapshot mapper is required");
        this.catalogSnapshots = Objects.requireNonNull(
                catalogSnapshots, "catalog snapshot mapper is required");
        this.codec = Objects.requireNonNull(codec, "snapshot codec is required");
        this.media = Objects.requireNonNull(media, "media query service is required");
        this.mediaAccess = Objects.requireNonNull(
                mediaAccess, "media query access guard is required");
        this.audit = Objects.requireNonNull(audit, "audit service is required");
        this.transactions = Objects.requireNonNull(
                transactions, "transaction operations are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.revisionIds = Objects.requireNonNull(
                revisionIds, "revision id supplier is required");
    }

    public PublicationResult publishSite(PublishSiteCommand command) {
        Objects.requireNonNull(command, "command");
        UUID actor = requireActor();
        PublicationResult result = transactions.execute(status -> {
            SiteWorkspaceDto observed = sites.require();
            validator.validateSiteWorkspace(observed);
            MediaDiscovery discoveredMedia = discoverSiteMedia(observed);
            LockedMediaPlan lockedMedia = lockInitialMedia(discoveredMedia);

            SiteSnapshotV1 snapshot;
            List<MediaReferenceRow> references;
            try (MediaQueryAccessGuard.Scope ignored = openMediaScope(lockedMedia)) {
                snapshot = siteSnapshots.toSnapshot(observed);
                references = references(validator.validateSite(observed, snapshot));
            }
            requireExactReferencesLocked(
                    lockedMedia, publishedVariantKeys(snapshot.media()), references);

            PublicationRow pointer = requirePointer(
                    publishing.lock(AggregateType.SITE, SiteWorkspaceDto.SITE_ID),
                    AggregateType.SITE,
                    SiteWorkspaceDto.SITE_ID);
            requirePublicationVersion(pointer, command.expectedPublicationVersion());
            SiteWorkspaceDto locked = sites.requireForUpdate();
            if (locked.version() != command.expectedWorkspaceVersion()
                    || !locked.equals(observed)) {
                throw contentConflict();
            }

            Instant publishedAt = clock.instant();
            UUID revisionId = nextRevisionId();
            long revisionVersion = nextVersion(pointer.version());
            EncodedSnapshot encoded = codec.encode(snapshot);
            publishing.insertRevision(revision(
                    revisionId,
                    AggregateType.SITE,
                    SiteWorkspaceDto.SITE_ID,
                    revisionVersion,
                    encoded,
                    actor,
                    publishedAt));
            publishing.insertMediaReferences(revisionId, references);
            if (!publishing.casPublish(
                    AggregateType.SITE,
                    SiteWorkspaceDto.SITE_ID,
                    pointer.version(),
                    revisionId,
                    null,
                    publishedAt)) {
                throw publicationConflict();
            }
            recordAudit(
                    actor,
                    "SITE_PUBLISHED",
                    "SITE",
                    SiteWorkspaceDto.SITE_ID,
                    Map.of("revisionId", revisionId.toString()));
            return new PublicationResult(
                    revisionId, revisionVersion, null, null, encoded.sha256());
        });
        return Objects.requireNonNull(result, "site publication transaction returned no result");
    }

    public PublicationResult publishProject(PublishProjectCommand command) {
        Objects.requireNonNull(command, "command");
        UUID projectId = Objects.requireNonNull(command.projectId(), "projectId");
        UUID actor = requireActor();
        PublicationResult result = transactions.execute(status -> {
            ProjectWorkspaceDto observed = projects.require(projectId);
            validator.validateProjectWorkspace(observed);

            List<PublicationRow> observedRows = publishedRows();
            Map<UUID, ProjectSnapshotV1> existingCatalogProjects =
                    readPublishedProjectsExcept(observedRows, projectId);
            MediaDiscovery discoveredMedia = discoverProjectMedia(observed);
            existingCatalogProjects.values().forEach(snapshot ->
                    addProjectCover(discoveredMedia, snapshot));
            LockedMediaPlan lockedMedia = lockInitialMedia(discoveredMedia);

            ProjectSnapshotV1 projectSnapshot;
            List<MediaReferenceRow> projectReferences;
            ProjectCatalogSnapshotV1 catalogSnapshot;
            List<MediaReferenceRow> catalogReferences;
            try (MediaQueryAccessGuard.Scope ignored = openMediaScope(lockedMedia)) {
                projectSnapshot = projectSnapshots.toSnapshot(observed);
                projectReferences = references(
                        validator.validateProject(observed, projectSnapshot));

                List<ProjectSnapshotV1> catalogProjects = catalogWithPublishedProject(
                        observedRows,
                        existingCatalogProjects,
                        projectSnapshot,
                        observed.sortOrder());
                catalogSnapshot = catalogSnapshots.fromCurrentProjects(catalogProjects);
                validator.validateCatalogStructure(catalogSnapshot);
                catalogReferences = references(validator.validateCatalog(catalogSnapshot));
            }
            requireExactReferencesLocked(
                    lockedMedia,
                    publishedVariantKeys(projectSnapshot.media()),
                    projectReferences);
            requireExactReferencesLocked(
                    lockedMedia, catalogVariantKeys(catalogSnapshot), catalogReferences);

            PublicationRow catalogPointer = requirePointer(
                    publishing.lock(AggregateType.PROJECT_CATALOG, PROJECT_CATALOG_ID),
                    AggregateType.PROJECT_CATALOG,
                    PROJECT_CATALOG_ID);
            requireCatalogVersion(catalogPointer, command.expectedCatalogVersion());

            ProjectWorkspaceDto lockedWorkspace = projects.requireForUpdate(projectId);
            if (lockedWorkspace.version() != command.expectedWorkspaceVersion()
                    || !lockedWorkspace.equals(observed)) {
                throw contentConflict();
            }

            publishing.ensureProjectPublication(projectId);
            PublicationRow projectPointer = requirePointer(
                    publishing.lock(AggregateType.PROJECT, projectId),
                    AggregateType.PROJECT,
                    projectId);
            requirePublicationVersion(
                    projectPointer, command.expectedProjectPublicationVersion());

            List<PublicationRow> authoritativeRows = publishedRows();
            if (!authoritativeRows.equals(observedRows)) {
                throw catalogConflict();
            }
            requireLockedProjectMatchesCatalog(projectPointer, authoritativeRows);

            String newSlug = Objects.requireNonNull(
                    lockedWorkspace.slug(), "published project slug is required");
            String oldSlug = projectPointer.currentSlug();
            boolean slugChanged = !Objects.equals(oldSlug, newSlug);
            if (publishing.currentSlugOrRedirectExists(newSlug, projectId)) {
                throw slugConflict();
            }

            Instant publishedAt = clock.instant();
            UUID projectRevisionId = nextRevisionId();
            UUID catalogRevisionId = nextRevisionId();
            long projectVersion = nextVersion(projectPointer.version());
            long catalogVersion = nextVersion(catalogPointer.version());
            EncodedSnapshot encodedProject = codec.encode(projectSnapshot);
            EncodedSnapshot encodedCatalog = codec.encode(catalogSnapshot);

            publishing.insertRevision(revision(
                    projectRevisionId,
                    AggregateType.PROJECT,
                    projectId,
                    projectVersion,
                    encodedProject,
                    actor,
                    publishedAt));
            publishing.insertMediaReferences(projectRevisionId, projectReferences);
            publishing.insertRevision(revision(
                    catalogRevisionId,
                    AggregateType.PROJECT_CATALOG,
                    PROJECT_CATALOG_ID,
                    catalogVersion,
                    encodedCatalog,
                    actor,
                    publishedAt));
            publishing.insertMediaReferences(catalogRevisionId, catalogReferences);
            if (slugChanged && oldSlug != null && !oldSlug.isBlank()) {
                publishing.insertRedirect(oldSlug, newSlug, projectId, publishedAt);
            }
            if (!publishing.casPublish(
                    AggregateType.PROJECT_CATALOG,
                    PROJECT_CATALOG_ID,
                    catalogPointer.version(),
                    catalogRevisionId,
                    null,
                    publishedAt)) {
                throw catalogConflict();
            }
            if (!publishing.casPublish(
                    AggregateType.PROJECT,
                    projectId,
                    projectPointer.version(),
                    projectRevisionId,
                    newSlug,
                    publishedAt)) {
                throw publicationConflict();
            }
            projects.markPublished(projectId, lockedWorkspace.version(), publishedAt);
            recordAudit(
                    actor,
                    "PROJECT_PUBLISHED",
                    "PROJECT",
                    projectId,
                    Map.of("revisionId", projectRevisionId.toString()));
            return new PublicationResult(
                    projectRevisionId,
                    projectVersion,
                    catalogRevisionId,
                    catalogVersion,
                    encodedProject.sha256());
        });
        return Objects.requireNonNull(
                result, "project publication transaction returned no result");
    }

    public PublicationResult archiveProject(ArchiveProjectCommand command) {
        Objects.requireNonNull(command, "command");
        UUID projectId = Objects.requireNonNull(command.projectId(), "projectId");
        UUID actor = requireActor();
        PublicationResult result = transactions.execute(status -> {
            List<PublicationRow> observedRows = publishedRows();
            PublicationRow observedProject = observedRows.stream()
                    .filter(row -> row.aggregateId().equals(projectId))
                    .findFirst()
                    .orElseThrow(PublicationService::publicationConflict);
            RevisionRow retainedRevision = requireProjectRevision(
                    observedProject.currentRevisionId(), projectId);
            List<ProjectSnapshotV1> remainingProjects = new ArrayList<>();
            for (PublicationRow row : observedRows) {
                if (!row.aggregateId().equals(projectId)) {
                    remainingProjects.add(readProjectRevision(row));
                }
            }
            ProjectCatalogSnapshotV1 catalogSnapshot =
                    catalogSnapshots.fromCurrentProjects(remainingProjects);
            validator.validateCatalogStructure(catalogSnapshot);
            LockedMediaPlan lockedMedia = lockInitialMedia(
                    discoverCatalogMedia(catalogSnapshot));
            List<MediaReferenceRow> catalogReferences;
            try (MediaQueryAccessGuard.Scope ignored = openMediaScope(lockedMedia)) {
                catalogReferences = references(validator.validateCatalog(catalogSnapshot));
            }
            requireExactReferencesLocked(
                    lockedMedia, catalogVariantKeys(catalogSnapshot), catalogReferences);

            PublicationRow catalogPointer = requirePointer(
                    publishing.lock(AggregateType.PROJECT_CATALOG, PROJECT_CATALOG_ID),
                    AggregateType.PROJECT_CATALOG,
                    PROJECT_CATALOG_ID);
            requireCatalogVersion(catalogPointer, command.expectedCatalogVersion());
            publishing.ensureProjectPublication(projectId);
            PublicationRow projectPointer = requirePointer(
                    publishing.lock(AggregateType.PROJECT, projectId),
                    AggregateType.PROJECT,
                    projectId);
            requirePublicationVersion(
                    projectPointer, command.expectedProjectPublicationVersion());
            if (!"PUBLISHED".equals(projectPointer.status())
                    || !projectPointer.equals(observedProject)) {
                throw publicationConflict();
            }
            List<PublicationRow> authoritativeRows = publishedRows();
            if (!authoritativeRows.equals(observedRows)) {
                throw catalogConflict();
            }

            Instant publishedAt = clock.instant();
            UUID catalogRevisionId = nextRevisionId();
            long catalogVersion = nextVersion(catalogPointer.version());
            long projectVersion = nextVersion(projectPointer.version());
            EncodedSnapshot encodedCatalog = codec.encode(catalogSnapshot);
            publishing.insertRevision(revision(
                    catalogRevisionId,
                    AggregateType.PROJECT_CATALOG,
                    PROJECT_CATALOG_ID,
                    catalogVersion,
                    encodedCatalog,
                    actor,
                    publishedAt));
            publishing.insertMediaReferences(catalogRevisionId, catalogReferences);
            if (!publishing.casPublish(
                    AggregateType.PROJECT_CATALOG,
                    PROJECT_CATALOG_ID,
                    catalogPointer.version(),
                    catalogRevisionId,
                    null,
                    publishedAt)) {
                throw catalogConflict();
            }
            if (!publishing.casArchive(
                    AggregateType.PROJECT,
                    projectId,
                    projectPointer.version(),
                    publishedAt)) {
                throw publicationConflict();
            }
            recordAudit(
                    actor,
                    "PROJECT_ARCHIVED",
                    "PROJECT_CATALOG",
                    PROJECT_CATALOG_ID,
                    Map.of(
                            "projectId", projectId.toString(),
                            "revisionId", catalogRevisionId.toString()));
            return new PublicationResult(
                    retainedRevision.id(),
                    projectVersion,
                    catalogRevisionId,
                    catalogVersion,
                    retainedRevision.checksum());
        });
        return Objects.requireNonNull(result, "project archive transaction returned no result");
    }

    public PublicationResult reorderCatalog(ReorderCatalogCommand command) {
        Objects.requireNonNull(command, "command");
        List<UUID> requestedOrder = requireOrder(command.projectIdsInOrder());
        UUID actor = requireActor();
        PublicationResult result = transactions.execute(status -> {
            List<PublicationRow> observedRows = publishedRows();
            requireExactProjectSet(requestedOrder, observedRows);
            Map<UUID, ProjectSnapshotV1> snapshotsByProject = new LinkedHashMap<>();
            for (PublicationRow row : observedRows) {
                snapshotsByProject.put(row.aggregateId(), readProjectRevision(row));
            }
            List<ProjectSnapshotV1> orderedSnapshots = requestedOrder.stream()
                    .map(snapshotsByProject::get)
                    .toList();
            ProjectCatalogSnapshotV1 catalogSnapshot =
                    catalogSnapshots.fromCurrentProjects(orderedSnapshots);
            validator.validateCatalogStructure(catalogSnapshot);
            LockedMediaPlan lockedMedia = lockInitialMedia(
                    discoverCatalogMedia(catalogSnapshot));
            List<MediaReferenceRow> catalogReferences;
            try (MediaQueryAccessGuard.Scope ignored = openMediaScope(lockedMedia)) {
                catalogReferences = references(validator.validateCatalog(catalogSnapshot));
            }
            requireExactReferencesLocked(
                    lockedMedia, catalogVariantKeys(catalogSnapshot), catalogReferences);

            PublicationRow catalogPointer = requirePointer(
                    publishing.lock(AggregateType.PROJECT_CATALOG, PROJECT_CATALOG_ID),
                    AggregateType.PROJECT_CATALOG,
                    PROJECT_CATALOG_ID);
            requireCatalogVersion(catalogPointer, command.expectedCatalogVersion());
            List<PublicationRow> authoritativeRows = publishedRows();
            if (!authoritativeRows.equals(observedRows)) {
                throw catalogConflict();
            }
            requireExactProjectSet(requestedOrder, authoritativeRows);

            Instant publishedAt = clock.instant();
            projects.updateCatalogOrder(requestedOrder, publishedAt);
            UUID catalogRevisionId = nextRevisionId();
            long catalogVersion = nextVersion(catalogPointer.version());
            EncodedSnapshot encoded = codec.encode(catalogSnapshot);
            publishing.insertRevision(revision(
                    catalogRevisionId,
                    AggregateType.PROJECT_CATALOG,
                    PROJECT_CATALOG_ID,
                    catalogVersion,
                    encoded,
                    actor,
                    publishedAt));
            publishing.insertMediaReferences(catalogRevisionId, catalogReferences);
            if (!publishing.casPublish(
                    AggregateType.PROJECT_CATALOG,
                    PROJECT_CATALOG_ID,
                    catalogPointer.version(),
                    catalogRevisionId,
                    null,
                    publishedAt)) {
                throw catalogConflict();
            }
            recordAudit(
                    actor,
                    "PROJECT_CATALOG_REORDERED",
                    "PROJECT_CATALOG",
                    PROJECT_CATALOG_ID,
                    Map.of("revisionId", catalogRevisionId.toString()));
            return new PublicationResult(
                    catalogRevisionId,
                    catalogVersion,
                    null,
                    null,
                    encoded.sha256());
        });
        return Objects.requireNonNull(result, "catalog reorder transaction returned no result");
    }

    public List<RevisionRow> history(AggregateType type, UUID aggregateId) {
        return publishing.history(
                Objects.requireNonNull(type, "aggregateType"),
                Objects.requireNonNull(aggregateId, "aggregateId"));
    }

    private List<ProjectSnapshotV1> catalogWithPublishedProject(
            List<PublicationRow> currentRows,
            Map<UUID, ProjectSnapshotV1> existingProjects,
            ProjectSnapshotV1 target,
            int requestedSortOrder) {
        List<ProjectSnapshotV1> snapshots = new ArrayList<>(currentRows.size() + 1);
        boolean replaced = false;
        for (PublicationRow row : currentRows) {
            if (row.aggregateId().equals(target.projectId())) {
                snapshots.add(target);
                replaced = true;
            } else {
                ProjectSnapshotV1 existing = existingProjects.get(row.aggregateId());
                if (existing == null) {
                    throw new IllegalStateException(
                            "published project snapshot was not preloaded");
                }
                snapshots.add(existing);
            }
        }
        if (!replaced) {
            int insertion = Math.max(0, Math.min(requestedSortOrder, snapshots.size()));
            snapshots.add(insertion, target);
        }
        return List.copyOf(snapshots);
    }

    private Map<UUID, ProjectSnapshotV1> readPublishedProjectsExcept(
            List<PublicationRow> currentRows, UUID excludedProjectId) {
        Map<UUID, ProjectSnapshotV1> snapshots = new LinkedHashMap<>();
        for (PublicationRow row : currentRows) {
            if (!row.aggregateId().equals(excludedProjectId)) {
                snapshots.put(row.aggregateId(), readProjectRevision(row));
            }
        }
        return Map.copyOf(snapshots);
    }

    private ProjectSnapshotV1 readProjectRevision(PublicationRow row) {
        Objects.requireNonNull(row, "publication row");
        return decodeProjectRevision(requireProjectRevision(
                row.currentRevisionId(), row.aggregateId()));
    }

    private RevisionRow requireProjectRevision(UUID revisionId, UUID projectId) {
        if (revisionId == null) {
            throw publicationConflict();
        }
        RevisionRow revision = publishing.requireRevision(revisionId);
        if (revision.type() != AggregateType.PROJECT
                || !revision.aggregateId().equals(projectId)) {
            throw new IllegalStateException("project publication points to another aggregate");
        }
        return revision;
    }

    private ProjectSnapshotV1 decodeProjectRevision(RevisionRow revision) {
        if (revision.schemaVersion() != 1) {
            throw new DomainException(
                    "SNAPSHOT_SCHEMA_UNSUPPORTED",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("snapshotSchemaVersion", Integer.toString(revision.schemaVersion())));
        }
        return codec.decode(revision.json(), ProjectSnapshotV1.class);
    }

    private LockedMediaPlan lockInitialMedia(MediaDiscovery discovered) {
        TreeMap<UUID, TreeSet<String>> variants = discovered.copy();
        for (UUID assetId : variants.keySet()) {
            MediaAssetDescriptor descriptor = requireInitialAsset(assetId);
            for (MediaVariantDescriptor variant : descriptor.variants()) {
                if (variant == null
                        || !assetId.equals(variant.assetId())
                        || variant.variantName() == null
                        || variant.variantName().isBlank()) {
                    throw mediaNotReady(assetId);
                }
                variants.get(assetId).add(variant.variantName());
            }
        }

        Set<MediaVariantKey> lockedVariants = new HashSet<>();
        for (Map.Entry<UUID, TreeSet<String>> entry : variants.entrySet()) {
            for (String variantName : entry.getValue()) {
                requireInitialVariant(entry.getKey(), variantName);
                lockedVariants.add(new MediaVariantKey(entry.getKey(), variantName));
            }
        }
        return new LockedMediaPlan(variants.keySet(), lockedVariants);
    }

    private MediaAssetDescriptor requireInitialAsset(UUID assetId) {
        MediaAssetDescriptor descriptor;
        try {
            descriptor = media.requireReadyAsset(assetId);
        } catch (DomainException failure) {
            throw normalizeMediaFailure(assetId, failure);
        }
        if (descriptor == null
                || !assetId.equals(descriptor.assetId())
                || !"READY".equals(descriptor.status())) {
            throw mediaNotReady(assetId);
        }
        return descriptor;
    }

    private void requireInitialVariant(UUID assetId, String variantName) {
        MediaVariantDescriptor descriptor;
        try {
            descriptor = media.requireReadyVariant(assetId, variantName);
        } catch (DomainException failure) {
            throw normalizeMediaFailure(assetId, failure);
        }
        if (descriptor == null
                || !assetId.equals(descriptor.assetId())
                || !variantName.equals(descriptor.variantName())
                || !"READY".equals(descriptor.status())) {
            throw mediaNotReady(assetId);
        }
    }

    private static DomainException normalizeMediaFailure(
            UUID assetId, DomainException failure) {
        if ("MEDIA_NOT_FOUND".equals(failure.code())
                || "MEDIA_NOT_READY".equals(failure.code())) {
            return mediaNotReady(assetId);
        }
        return failure;
    }

    private MediaQueryAccessGuard.Scope openMediaScope(LockedMediaPlan plan) {
        Set<MediaQueryAccessGuard.VariantKey> variants = new HashSet<>();
        for (MediaVariantKey key : plan.variants()) {
            variants.add(new MediaQueryAccessGuard.VariantKey(
                    key.assetId(), key.variantName()));
        }
        return mediaAccess.openScope(plan.assetIds(), variants);
    }

    private static void requireExactReferencesLocked(
            LockedMediaPlan plan,
            Set<MediaVariantKey> snapshotKeys,
            List<MediaReferenceRow> references) {
        for (MediaVariantKey snapshotKey : snapshotKeys) {
            if (!plan.assetIds().contains(snapshotKey.assetId())
                    || !plan.variants().contains(snapshotKey)) {
                throw mediaNotReady(snapshotKey.assetId());
            }
        }

        Set<MediaVariantKey> referenceKeys = new HashSet<>();
        for (MediaReferenceRow reference : references) {
            if (reference == null || reference.assetId() == null) {
                throw new IllegalStateException(
                        "publication validator returned an invalid media reference");
            }
            String variantName = reference.variantName();
            if (variantName == null || variantName.isBlank()) {
                throw new IllegalStateException(
                        "publication validator returned a blank media variant");
            }
            MediaVariantKey key = new MediaVariantKey(
                    reference.assetId(), variantName);
            if (!plan.assetIds().contains(reference.assetId())
                    || !plan.variants().contains(key)) {
                throw mediaNotReady(reference.assetId());
            }
            referenceKeys.add(key);
        }

        if (!snapshotKeys.equals(referenceKeys)) {
            TreeSet<MediaVariantKey> difference = new TreeSet<>(MEDIA_VARIANT_ORDER);
            for (MediaVariantKey key : snapshotKeys) {
                if (!referenceKeys.contains(key)) {
                    difference.add(key);
                }
            }
            for (MediaVariantKey key : referenceKeys) {
                if (!snapshotKeys.contains(key)) {
                    difference.add(key);
                }
            }
            throw mediaNotReady(difference.first().assetId());
        }
    }

    private static Set<MediaVariantKey> publishedVariantKeys(
            List<PublishedMediaV1> publishedMedia) {
        Set<MediaVariantKey> keys = new HashSet<>();
        for (PublishedMediaV1 published : publishedMedia) {
            addPublishedVariantKeys(keys, published);
        }
        return Set.copyOf(keys);
    }

    private static Set<MediaVariantKey> catalogVariantKeys(
            ProjectCatalogSnapshotV1 snapshot) {
        Set<MediaVariantKey> keys = new HashSet<>();
        for (ProjectCatalogSnapshotV1.Card card : snapshot.projects()) {
            if (card != null && card.cover() != null) {
                addPublishedVariantKeys(keys, card.cover());
            }
        }
        return Set.copyOf(keys);
    }

    private static void addPublishedVariantKeys(
            Set<MediaVariantKey> keys, PublishedMediaV1 published) {
        if (published == null || published.assetId() == null) {
            throw new IllegalStateException(
                    "publication snapshot contains invalid media");
        }
        for (PublishedMediaV1.Variant variant : published.variants()) {
            if (variant == null || variant.name() == null || variant.name().isBlank()) {
                throw mediaNotReady(published.assetId());
            }
            keys.add(new MediaVariantKey(published.assetId(), variant.name()));
        }
    }

    private static MediaDiscovery discoverSiteMedia(SiteWorkspaceDto workspace) {
        MediaDiscovery discovered = new MediaDiscovery();
        if (workspace.hero() != null) {
            discovered.addAsset(workspace.hero().mediaAssetId());
        }
        for (SiteWorkspaceDto.ResumeDocument resume : workspace.resumes()) {
            if (resume != null && resume.current()) {
                discovered.addAsset(resume.mediaAssetId());
            }
        }
        return discovered;
    }

    private static MediaDiscovery discoverProjectMedia(ProjectWorkspaceDto workspace) {
        MediaDiscovery discovered = new MediaDiscovery();
        for (ProjectWorkspaceDto.ProjectMedia item : workspace.media()) {
            if (item != null) {
                discovered.addAsset(item.assetId());
            }
        }
        for (ContentBlockDto block : workspace.blocks()) {
            if (block != null) {
                addBlockAssets(discovered, block.payload());
            }
        }
        return discovered;
    }

    private static MediaDiscovery discoverCatalogMedia(
            ProjectCatalogSnapshotV1 snapshot) {
        MediaDiscovery discovered = new MediaDiscovery();
        for (ProjectCatalogSnapshotV1.Card card : snapshot.projects()) {
            if (card != null) {
                discovered.addPublishedMedia(card.cover());
            }
        }
        return discovered;
    }

    private static void addProjectCover(
            MediaDiscovery discovered, ProjectSnapshotV1 snapshot) {
        UUID coverAssetId = null;
        for (ProjectSnapshotV1.ProjectMediaV1 projectMedia : snapshot.projectMedia()) {
            if (projectMedia != null
                    && "COVER".equals(projectMedia.usage())
                    && projectMedia.assetId() != null) {
                coverAssetId = projectMedia.assetId();
                break;
            }
        }
        if (coverAssetId == null) {
            return;
        }
        discovered.addAsset(coverAssetId);
        for (PublishedMediaV1 published : snapshot.media()) {
            if (published != null && coverAssetId.equals(published.assetId())) {
                discovered.addPublishedMedia(published);
                return;
            }
        }
    }

    private static void addBlockAssets(
            MediaDiscovery discovered, ContentBlockDto.Payload payload) {
        if (payload instanceof ContentBlockDto.ImagePayload image) {
            discovered.addAsset(image.mediaAssetId());
        } else if (payload instanceof ContentBlockDto.GalleryPayload gallery) {
            gallery.mediaAssetIds().forEach(discovered::addAsset);
        } else if (payload instanceof ContentBlockDto.VideoPayload video) {
            discovered.addAsset(video.coverAssetId());
        } else if (payload instanceof ContentBlockDto.DownloadPayload download) {
            discovered.addAsset(download.mediaAssetId());
        }
    }

    private static List<MediaReferenceRow> references(List<MediaReferenceRow> references) {
        return List.copyOf(Objects.requireNonNull(
                references, "publication media references are required"));
    }

    private static List<PublicationRow> publishedRows(List<PublicationRow> rows) {
        List<PublicationRow> immutable = List.copyOf(
                Objects.requireNonNull(rows, "published projects are required"));
        Set<UUID> ids = new HashSet<>();
        for (PublicationRow row : immutable) {
            requirePointer(row, AggregateType.PROJECT, row.aggregateId());
            if (!"PUBLISHED".equals(row.status())
                    || row.currentRevisionId() == null
                    || !ids.add(row.aggregateId())) {
                throw new IllegalStateException("published project query is inconsistent");
            }
        }
        return immutable;
    }

    private List<PublicationRow> publishedRows() {
        return publishedRows(publishing.findPublishedProjects());
    }

    private static List<UUID> requireOrder(List<UUID> requested) {
        List<UUID> order = List.copyOf(Objects.requireNonNull(
                requested, "projectIdsInOrder"));
        Set<UUID> unique = new HashSet<>();
        for (UUID projectId : order) {
            if (projectId == null || !unique.add(projectId)) {
                throw catalogOrderInvalid();
            }
        }
        return order;
    }

    private static void requireExactProjectSet(
            List<UUID> requestedOrder, List<PublicationRow> currentRows) {
        Set<UUID> currentIds = new HashSet<>();
        currentRows.forEach(row -> currentIds.add(row.aggregateId()));
        if (requestedOrder.size() != currentRows.size()
                || !new HashSet<>(requestedOrder).equals(currentIds)) {
            throw catalogOrderInvalid();
        }
    }

    private static void requireLockedProjectMatchesCatalog(
            PublicationRow lockedProject, List<PublicationRow> currentRows) {
        PublicationRow listed = currentRows.stream()
                .filter(row -> row.aggregateId().equals(lockedProject.aggregateId()))
                .findFirst()
                .orElse(null);
        if ("PUBLISHED".equals(lockedProject.status())) {
            if (!lockedProject.equals(listed)) {
                throw catalogConflict();
            }
        } else if (listed != null) {
            throw catalogConflict();
        }
    }

    private static PublicationRow requirePointer(
            PublicationRow row, AggregateType type, UUID aggregateId) {
        Objects.requireNonNull(row, "publication pointer");
        if (row.type() != type || !row.aggregateId().equals(aggregateId)) {
            throw new IllegalStateException("publication repository returned another aggregate");
        }
        return row;
    }

    private static void requireCatalogVersion(PublicationRow row, long expectedVersion) {
        if (row.version() != expectedVersion) {
            throw catalogConflict();
        }
    }

    private static void requirePublicationVersion(PublicationRow row, long expectedVersion) {
        if (row.version() != expectedVersion) {
            throw publicationConflict();
        }
    }

    private static long nextVersion(long current) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("publication version overflow", overflow);
        }
    }

    private UUID nextRevisionId() {
        return Objects.requireNonNull(
                revisionIds.get(), "generated revision id is required");
    }

    private UUID requireActor() {
        return Objects.requireNonNull(
                currentAdmin.requireAdminId(),
                "current administrator id is required");
    }

    private void recordAudit(
            UUID actor,
            String action,
            String targetType,
            UUID targetId,
            Map<String, String> metadata) {
        audit.record(new AuditCommand(
                actor,
                action,
                targetType,
                targetId.toString(),
                AuditOutcome.SUCCESS,
                null,
                metadata));
    }

    private static RevisionRow revision(
            UUID id,
            AggregateType type,
            UUID aggregateId,
            long version,
            EncodedSnapshot encoded,
            UUID actor,
            Instant publishedAt) {
        return new RevisionRow(
                id,
                type,
                aggregateId,
                version,
                encoded.schemaVersion(),
                encoded.json(),
                encoded.sha256(),
                actor,
                publishedAt);
    }

    private static DomainException mediaNotReady(UUID assetId) {
        return new DomainException(
                "MEDIA_NOT_READY",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of(
                        "media[" + assetId + "]",
                        "a READY media asset and variant are required"));
    }

    private static DomainException catalogConflict() {
        return new DomainException(
                "CATALOG_VERSION_CONFLICT",
                HttpStatus.CONFLICT,
                Map.of("catalogVersion", "catalog was changed by another request"));
    }

    private static DomainException publicationConflict() {
        return new DomainException(
                "PUBLICATION_VERSION_CONFLICT",
                HttpStatus.CONFLICT,
                Map.of("publicationVersion", "publication was changed by another request"));
    }

    private static DomainException contentConflict() {
        return new DomainException(
                "CONTENT_VERSION_CONFLICT",
                HttpStatus.CONFLICT,
                Map.of("version", "workspace was changed by another request"));
    }

    private static DomainException slugConflict() {
        return new DomainException(
                "PROJECT_SLUG_CONFLICT",
                HttpStatus.CONFLICT,
                Map.of("slug", "slug is current or retained as a redirect"));
    }

    private static DomainException catalogOrderInvalid() {
        return new DomainException(
                "CATALOG_ORDER_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("projectIdsInOrder", "must contain every published project exactly once"));
    }

    private record MediaVariantKey(UUID assetId, String variantName) {}

    private record LockedMediaPlan(
            Set<UUID> assetIds, Set<MediaVariantKey> variants) {
        private LockedMediaPlan {
            assetIds = Set.copyOf(assetIds);
            variants = Set.copyOf(variants);
        }
    }

    private static final class MediaDiscovery {
        private final TreeMap<UUID, TreeSet<String>> variants =
                new TreeMap<>(UUID_ORDER);

        private void addAsset(UUID assetId) {
            if (assetId != null) {
                variants.computeIfAbsent(assetId, ignored -> new TreeSet<>());
            }
        }

        private void addVariant(UUID assetId, String variantName) {
            if (assetId == null) {
                throw new IllegalStateException("published media has no asset id");
            }
            if (variantName == null || variantName.isBlank()) {
                throw mediaNotReady(assetId);
            }
            addAsset(assetId);
            variants.get(assetId).add(variantName);
        }

        private void addPublishedMedia(PublishedMediaV1 published) {
            if (published == null) {
                return;
            }
            addAsset(published.assetId());
            for (PublishedMediaV1.Variant variant : published.variants()) {
                if (variant == null) {
                    throw mediaNotReady(published.assetId());
                }
                addVariant(published.assetId(), variant.name());
            }
        }

        private TreeMap<UUID, TreeSet<String>> copy() {
            TreeMap<UUID, TreeSet<String>> copied = new TreeMap<>(UUID_ORDER);
            variants.forEach((assetId, names) ->
                    copied.put(assetId, new TreeSet<>(names)));
            return copied;
        }
    }
}
