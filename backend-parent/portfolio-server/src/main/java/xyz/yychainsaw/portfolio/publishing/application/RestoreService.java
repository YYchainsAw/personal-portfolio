package xyz.yychainsaw.portfolio.publishing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
import xyz.yychainsaw.portfolio.content.application.WorkspaceValidator;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.publishing.application.PublishingMediaLockCoordinator.LockedMediaPlan;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.SnapshotMapperRegistry;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

/** Restores an immutable SITE or PROJECT revision into the editable workspace. */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class RestoreService {
    private static final Comparator<UUID> UUID_ORDER =
            Comparator.comparing(UUID::toString);

    private final CurrentAdminProvider currentAdmin;
    private final SiteWorkspaceRepository sites;
    private final ProjectWorkspaceRepository projects;
    private final PublishingRepository publishing;
    private final SnapshotMapperRegistry snapshots;
    private final WorkspaceValidator validator;
    private final PublishingMediaLockCoordinator mediaLocks;
    private final AuditService audit;
    private final TransactionOperations transactions;
    private final Clock clock;

    @Autowired
    public RestoreService(
            CurrentAdminProvider currentAdmin,
            SiteWorkspaceRepository sites,
            ProjectWorkspaceRepository projects,
            PublishingRepository publishing,
            SnapshotMapperRegistry snapshots,
            WorkspaceValidator validator,
            PublishingMediaLockCoordinator mediaLocks,
            AuditService audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(
                currentAdmin,
                sites,
                projects,
                publishing,
                snapshots,
                validator,
                mediaLocks,
                audit,
                new TransactionTemplate(Objects.requireNonNull(
                        transactionManager, "transaction manager is required")),
                clock);
    }

    RestoreService(
            CurrentAdminProvider currentAdmin,
            SiteWorkspaceRepository sites,
            ProjectWorkspaceRepository projects,
            PublishingRepository publishing,
            SnapshotMapperRegistry snapshots,
            WorkspaceValidator validator,
            PublishingMediaLockCoordinator mediaLocks,
            AuditService audit,
            TransactionOperations transactions,
            Clock clock) {
        this.currentAdmin = Objects.requireNonNull(
                currentAdmin, "current administrator provider is required");
        this.sites = Objects.requireNonNull(sites, "site repository is required");
        this.projects = Objects.requireNonNull(
                projects, "project repository is required");
        this.publishing = Objects.requireNonNull(
                publishing, "publishing repository is required");
        this.snapshots = Objects.requireNonNull(
                snapshots, "snapshot mapper registry is required");
        this.validator = Objects.requireNonNull(
                validator, "workspace validator is required");
        this.mediaLocks = Objects.requireNonNull(
                mediaLocks, "media lock coordinator is required");
        this.audit = Objects.requireNonNull(audit, "audit service is required");
        this.transactions = Objects.requireNonNull(
                transactions, "transaction operations are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public void restore(UUID revisionId, long expectedWorkspaceVersion) {
        UUID requiredRevisionId = Objects.requireNonNull(
                revisionId, "revision id is required");
        UUID actor = Objects.requireNonNull(
                currentAdmin.requireAdminId(),
                "current administrator id is required");
        transactions.executeWithoutResult(ignored -> restoreInTransaction(
                requiredRevisionId, expectedWorkspaceVersion, actor));
    }

    private void restoreInTransaction(
            UUID revisionId, long expectedWorkspaceVersion, UUID actor) {
        RevisionRow revision = requireRevision(revisionId);
        if (!revisionId.equals(revision.id())
                || revision.type() == null
                || revision.aggregateId() == null) {
            throw new IllegalStateException(
                    "publishing repository returned an inconsistent revision");
        }
        switch (revision.type()) {
            case SITE -> restoreSite(
                    revision, expectedWorkspaceVersion, actor);
            case PROJECT -> restoreProject(
                    revision, expectedWorkspaceVersion, actor);
            case PROJECT_CATALOG -> throw catalogRestoreNotAllowed(revisionId);
        }
    }

    private void restoreSite(
            RevisionRow revision, long expectedWorkspaceVersion, UUID actor) {
        SiteSnapshotV1 snapshot = snapshots.readSite(
                revision.schemaVersion(), revision.json());
        requireSiteRevisionOwnership(revision, snapshot);

        SiteWorkspaceDto observed = requireSiteOwnership(sites.require());
        requireExpectedVersion(observed.version(), expectedWorkspaceVersion);
        SiteWorkspaceDto candidate = requireSiteOwnership(snapshots.restoreSite(
                revision.schemaVersion(), revision.json(), observed));
        validator.validateSite(candidate);
        Set<UUID> candidateAssets = siteAssetIds(candidate);
        LockedMediaPlan plan = mediaLocks.lockRestoreMedia(
                candidateAssets, snapshot.media());

        SiteWorkspaceDto locked = requireSiteOwnership(sites.requireForUpdate());
        requireExpectedVersion(locked.version(), expectedWorkspaceVersion);
        SiteWorkspaceDto restored = requireSiteOwnership(snapshots.restoreSite(
                revision.schemaVersion(), revision.json(), locked));
        validator.validateSite(restored);
        requireFinalAssets(plan, siteAssetIds(restored));

        Instant updatedAt = clock.instant();
        sites.replace(restored, locked.version(), updatedAt);
        recordAudit(actor, revision);
    }

    private void restoreProject(
            RevisionRow revision, long expectedWorkspaceVersion, UUID actor) {
        ProjectSnapshotV1 snapshot = snapshots.readProject(
                revision.schemaVersion(), revision.json());
        requireProjectRevisionOwnership(revision, snapshot);

        ProjectWorkspaceDto observed = requireProjectOwnership(
                projects.require(revision.aggregateId()), revision.aggregateId());
        requireExpectedVersion(observed.version(), expectedWorkspaceVersion);
        ProjectWorkspaceDto candidate = preserveProjectIdentity(
                snapshots.restoreProject(
                        revision.schemaVersion(), revision.json(), observed.version()),
                observed);
        validator.validateProject(candidate);
        Set<UUID> candidateAssets = projectAssetIds(candidate);
        LockedMediaPlan plan = mediaLocks.lockRestoreMedia(
                candidateAssets, snapshot.media());

        ProjectWorkspaceDto locked = requireProjectOwnership(
                projects.requireForUpdate(revision.aggregateId()),
                revision.aggregateId());
        requireExpectedVersion(locked.version(), expectedWorkspaceVersion);
        if (!Objects.equals(observed.externalKey(), locked.externalKey())) {
            throw new IllegalStateException(
                    "locked project workspace identity changed");
        }
        ProjectWorkspaceDto restored = preserveProjectIdentity(
                snapshots.restoreProject(
                        revision.schemaVersion(), revision.json(), locked.version()),
                locked);
        validator.validateProject(restored);
        requireFinalAssets(plan, projectAssetIds(restored));

        Instant updatedAt = clock.instant();
        projects.replace(restored, locked.version(), updatedAt);
        recordAudit(actor, revision);
    }

    private RevisionRow requireRevision(UUID revisionId) {
        try {
            RevisionRow revision = publishing.requireRevision(revisionId);
            if (revision == null) {
                throw revisionNotFound();
            }
            return revision;
        } catch (NoSuchElementException failure) {
            throw revisionNotFound();
        }
    }

    private void recordAudit(UUID actor, RevisionRow revision) {
        audit.record(new AuditCommand(
                actor,
                "REVISION_RESTORED_TO_DRAFT",
                revision.type().name(),
                revision.aggregateId().toString(),
                AuditOutcome.SUCCESS,
                null,
                Map.of("revisionId", revision.id().toString())));
    }

    private static void requireSiteRevisionOwnership(
            RevisionRow revision, SiteSnapshotV1 snapshot) {
        if (!SiteWorkspaceDto.SITE_ID.equals(revision.aggregateId())
                || snapshot == null
                || !SiteWorkspaceDto.SITE_ID.equals(snapshot.siteId())) {
            throw new IllegalStateException(
                    "SITE revision ownership is inconsistent");
        }
    }

    private static void requireProjectRevisionOwnership(
            RevisionRow revision, ProjectSnapshotV1 snapshot) {
        if (snapshot == null
                || !revision.aggregateId().equals(snapshot.projectId())) {
            throw new IllegalStateException(
                    "PROJECT revision ownership is inconsistent");
        }
    }

    private static SiteWorkspaceDto requireSiteOwnership(SiteWorkspaceDto workspace) {
        if (workspace == null
                || !SiteWorkspaceDto.SITE_ID.equals(workspace.siteId())) {
            throw new IllegalStateException(
                    "site workspace ownership is inconsistent");
        }
        return workspace;
    }

    private static ProjectWorkspaceDto requireProjectOwnership(
            ProjectWorkspaceDto workspace, UUID projectId) {
        if (workspace == null || !projectId.equals(workspace.id())) {
            throw new IllegalStateException(
                    "project workspace ownership is inconsistent");
        }
        return workspace;
    }

    private static ProjectWorkspaceDto preserveProjectIdentity(
            ProjectWorkspaceDto restored, ProjectWorkspaceDto current) {
        Objects.requireNonNull(restored, "restored project workspace is required");
        return new ProjectWorkspaceDto(
                current.id(),
                current.externalKey(),
                restored.slug(),
                restored.number(),
                restored.sortOrder(),
                restored.featured(),
                restored.visible(),
                true,
                current.version(),
                restored.translations(),
                restored.tags(),
                restored.skills(),
                restored.media(),
                restored.blocks());
    }

    private static Set<UUID> siteAssetIds(SiteWorkspaceDto workspace) {
        TreeSet<UUID> assetIds = new TreeSet<>(UUID_ORDER);
        if (workspace.hero() != null) {
            addAsset(assetIds, workspace.hero().mediaAssetId());
        }
        for (SiteWorkspaceDto.ResumeDocument resume : workspace.resumes()) {
            if (resume != null && resume.current()) {
                addAsset(assetIds, resume.mediaAssetId());
            }
        }
        return Set.copyOf(assetIds);
    }

    private static Set<UUID> projectAssetIds(ProjectWorkspaceDto workspace) {
        TreeSet<UUID> assetIds = new TreeSet<>(UUID_ORDER);
        for (ProjectWorkspaceDto.ProjectMedia media : workspace.media()) {
            if (media != null) {
                addAsset(assetIds, media.assetId());
            }
        }
        for (ContentBlockDto block : workspace.blocks()) {
            if (block == null) {
                continue;
            }
            ContentBlockDto.Payload payload = block.payload();
            if (payload instanceof ContentBlockDto.ImagePayload image) {
                addAsset(assetIds, image.mediaAssetId());
            } else if (payload instanceof ContentBlockDto.GalleryPayload gallery) {
                gallery.mediaAssetIds().forEach(assetId -> addAsset(assetIds, assetId));
            } else if (payload instanceof ContentBlockDto.VideoPayload video) {
                addAsset(assetIds, video.coverAssetId());
            } else if (payload instanceof ContentBlockDto.DownloadPayload download) {
                addAsset(assetIds, download.mediaAssetId());
            }
        }
        return Set.copyOf(assetIds);
    }

    private static void addAsset(Set<UUID> assetIds, UUID assetId) {
        if (assetId != null) {
            assetIds.add(assetId);
        }
    }

    private static void requireFinalAssets(
            LockedMediaPlan plan, Set<UUID> finalAssetIds) {
        if (!plan.assetIds().equals(finalAssetIds)) {
            throw contentConflict();
        }
    }

    private static void requireExpectedVersion(long actual, long expected) {
        if (actual != expected) {
            throw contentConflict();
        }
    }

    private static DomainException revisionNotFound() {
        return new DomainException(
                "REVISION_NOT_FOUND",
                HttpStatus.NOT_FOUND,
                Map.of("revisionId", "revision does not exist"));
    }

    private static DomainException catalogRestoreNotAllowed(UUID revisionId) {
        return new DomainException(
                "CATALOG_RESTORE_NOT_ALLOWED",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of(
                        "revisionId",
                        "catalog is regenerated from current project publications"));
    }

    private static DomainException contentConflict() {
        return new DomainException(
                "CONTENT_VERSION_CONFLICT",
                HttpStatus.CONFLICT,
                Map.of("version", "workspace was changed by another request"));
    }
}
