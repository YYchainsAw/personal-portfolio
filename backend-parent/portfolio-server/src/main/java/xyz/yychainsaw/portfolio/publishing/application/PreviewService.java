package xyz.yychainsaw.portfolio.publishing.application;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.application.WorkspaceValidator;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.media.application.MediaQueryAccessGuard;
import xyz.yychainsaw.portfolio.publishing.application.PreviewTokenService.PreviewClaims;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.ProjectSnapshotMapper;
import xyz.yychainsaw.portfolio.publishing.snapshot.SiteSnapshotMapper;

/** Creates an ephemeral snapshot from the current editable workspace. */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public class PreviewService {
    private final SiteWorkspaceRepository sites;
    private final ProjectWorkspaceRepository projects;
    private final WorkspaceValidator validator;
    private final SiteSnapshotMapper siteSnapshots;
    private final ProjectSnapshotMapper projectSnapshots;
    private final MediaQueryAccessGuard mediaAccess;

    public PreviewService(
            SiteWorkspaceRepository sites,
            ProjectWorkspaceRepository projects,
            WorkspaceValidator validator,
            SiteSnapshotMapper siteSnapshots,
            ProjectSnapshotMapper projectSnapshots,
            MediaQueryAccessGuard mediaAccess) {
        this.sites = Objects.requireNonNull(sites, "site workspace repository is required");
        this.projects = Objects.requireNonNull(
                projects, "project workspace repository is required");
        this.validator = Objects.requireNonNull(validator, "workspace validator is required");
        this.siteSnapshots = Objects.requireNonNull(
                siteSnapshots, "site snapshot mapper is required");
        this.projectSnapshots = Objects.requireNonNull(
                projectSnapshots, "project snapshot mapper is required");
        this.mediaAccess = Objects.requireNonNull(
                mediaAccess, "media query access guard is required");
    }

    /**
     * This transaction must remain writable: READY media reads acquire PostgreSQL
     * share locks even though preview itself performs no writes.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Object preview(PreviewClaims claims) {
        PreviewClaims required = requireClaims(claims);
        if (required.aggregateType() == AggregateType.PROJECT_CATALOG) {
            throw catalogPreviewNotAllowed();
        }
        if (required.aggregateType() == AggregateType.SITE) {
            return previewSite(required);
        }
        if (required.aggregateType() == AggregateType.PROJECT) {
            return previewProject(required);
        }
        throw previewTokenInvalid();
    }

    private Object previewSite(PreviewClaims claims) {
        if (!SiteWorkspaceDto.SITE_ID.equals(claims.aggregateId())) {
            throw previewTokenInvalid();
        }
        SiteWorkspaceDto workspace = Objects.requireNonNull(
                sites.require(), "site workspace is required");
        if (!SiteWorkspaceDto.SITE_ID.equals(workspace.siteId())
                || !claims.aggregateId().equals(workspace.siteId())) {
            throw previewTokenInvalid();
        }
        requireCurrentVersion(workspace.version(), claims.workspaceVersion());
        validator.validateSite(workspace);
        try (MediaQueryAccessGuard.Scope ignored = mediaAccess.openScope(
                discoverSiteMedia(workspace), Set.of())) {
            return siteSnapshots.toSnapshot(workspace);
        }
    }

    private Object previewProject(PreviewClaims claims) {
        UUID projectId = claims.aggregateId();
        if (projectId == null) {
            throw previewTokenInvalid();
        }
        ProjectWorkspaceDto workspace = Objects.requireNonNull(
                projects.require(projectId), "project workspace is required");
        if (!projectId.equals(workspace.id())) {
            throw previewTokenInvalid();
        }
        requireCurrentVersion(workspace.version(), claims.workspaceVersion());
        validator.validateProject(workspace);
        try (MediaQueryAccessGuard.Scope ignored = mediaAccess.openScope(
                discoverProjectMedia(workspace), Set.of())) {
            return projectSnapshots.toSnapshot(workspace);
        }
    }

    private static PreviewClaims requireClaims(PreviewClaims claims) {
        if (claims == null
                || claims.aggregateType() == null
                || claims.workspaceVersion() < 0) {
            throw previewTokenInvalid();
        }
        return claims;
    }

    private static Set<UUID> discoverSiteMedia(SiteWorkspaceDto workspace) {
        Set<UUID> assetIds = new HashSet<>();
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

    private static Set<UUID> discoverProjectMedia(ProjectWorkspaceDto workspace) {
        Set<UUID> assetIds = new HashSet<>();
        for (ProjectWorkspaceDto.ProjectMedia media : workspace.media()) {
            if (media != null) {
                addAsset(assetIds, media.assetId());
            }
        }
        for (ContentBlockDto block : workspace.blocks()) {
            if (block != null) {
                addBlockMedia(assetIds, block.payload());
            }
        }
        return Set.copyOf(assetIds);
    }

    private static void addBlockMedia(
            Set<UUID> assetIds, ContentBlockDto.Payload payload) {
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

    private static void addAsset(Set<UUID> assetIds, UUID assetId) {
        if (assetId != null) {
            assetIds.add(assetId);
        }
    }

    private static void requireCurrentVersion(long actual, long expected) {
        if (actual != expected) {
            throw new DomainException(
                    "CONTENT_VERSION_CONFLICT",
                    HttpStatus.CONFLICT,
                    Map.of("version", "workspace was changed by another request"));
        }
    }

    private static DomainException catalogPreviewNotAllowed() {
        return new DomainException(
                "CATALOG_PREVIEW_NOT_ALLOWED",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of());
    }

    private static DomainException previewTokenInvalid() {
        return new DomainException(
                "PREVIEW_TOKEN_INVALID", HttpStatus.FORBIDDEN, Map.of());
    }
}
