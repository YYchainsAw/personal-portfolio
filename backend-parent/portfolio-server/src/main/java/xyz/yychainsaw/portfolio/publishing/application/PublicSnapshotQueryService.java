package xyz.yychainsaw.portfolio.publishing.application;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.publicapi.PublishedEnvelope;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectCardDto;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publicapi.PublicSiteDto;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.PublicationRow;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.SnapshotMapperRegistry;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.SiteSnapshotV1;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class PublicSnapshotQueryService {
    private static final UUID PROJECT_CATALOG_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final PublishingRepository publishing;
    private final SnapshotMapperRegistry snapshots;
    private final PublicProjectionMapper projections;

    public PublicSnapshotQueryService(
            PublishingRepository publishing,
            SnapshotMapperRegistry snapshots,
            PublicProjectionMapper projections) {
        this.publishing = Objects.requireNonNull(
                publishing, "publishing repository is required");
        this.snapshots = Objects.requireNonNull(
                snapshots, "snapshot mapper registry is required");
        this.projections = Objects.requireNonNull(
                projections, "public projection mapper is required");
    }

    public PublishedEnvelope<PublicSiteDto> site(LocaleCode locale) {
        LocaleCode requiredLocale = Objects.requireNonNull(locale, "locale is required");
        PublicationRow pointer = requireCurrent(
                AggregateType.SITE, SiteWorkspaceDto.SITE_ID);
        RevisionRow revision = requireOwnedRevision(pointer);
        SiteSnapshotV1 snapshot = snapshots.readSite(
                revision.schemaVersion(), revision.json());
        if (snapshot == null || !SiteWorkspaceDto.SITE_ID.equals(snapshot.siteId())) {
            throw ownershipFailure();
        }
        return new PublishedEnvelope<>(
                revision.version(),
                revision.checksum(),
                projections.site(snapshot, requiredLocale));
    }

    public PublishedEnvelope<List<PublicProjectCardDto>> catalog(LocaleCode locale) {
        LocaleCode requiredLocale = Objects.requireNonNull(locale, "locale is required");
        PublicationRow pointer = requireCurrent(
                AggregateType.PROJECT_CATALOG, PROJECT_CATALOG_ID);
        RevisionRow revision = requireOwnedRevision(pointer);
        var snapshot = snapshots.readCatalog(revision.schemaVersion(), revision.json());
        if (snapshot == null) {
            throw ownershipFailure();
        }
        return new PublishedEnvelope<>(
                revision.version(),
                revision.checksum(),
                projections.catalog(snapshot, requiredLocale));
    }

    public PublishedEnvelope<PublicProjectDto> project(
            String slug, LocaleCode locale) {
        if (slug == null || slug.isBlank()) {
            throw projectNotFound();
        }
        LocaleCode requiredLocale = Objects.requireNonNull(locale, "locale is required");
        PublicationRow pointer = publishing.findPublishedProjectBySlug(slug)
                .filter(row -> validProjectPointer(row, slug))
                .orElseThrow(PublicSnapshotQueryService::projectNotFound);
        RevisionRow revision = requireOwnedRevision(pointer);
        ProjectSnapshotV1 snapshot = snapshots.readProject(
                revision.schemaVersion(), revision.json());
        if (snapshot == null || !pointer.aggregateId().equals(snapshot.projectId())) {
            throw ownershipFailure();
        }
        return new PublishedEnvelope<>(
                revision.version(),
                revision.checksum(),
                projections.project(snapshot, requiredLocale));
    }

    private PublicationRow requireCurrent(AggregateType type, UUID aggregateId) {
        return publishing.find(type, aggregateId)
                .filter(row -> validPointer(row, type, aggregateId))
                .orElseThrow(() -> notFound(type));
    }

    private RevisionRow requireOwnedRevision(PublicationRow pointer) {
        UUID revisionId = pointer.currentRevisionId();
        if (revisionId == null) {
            throw ownershipFailure();
        }
        RevisionRow revision;
        try {
            revision = publishing.requireRevision(revisionId);
        } catch (NoSuchElementException missing) {
            throw ownershipFailure();
        }
        if (revision == null
                || !revisionId.equals(revision.id())
                || revision.type() != pointer.type()
                || !pointer.aggregateId().equals(revision.aggregateId())) {
            throw ownershipFailure();
        }
        return revision;
    }

    private static boolean validPointer(
            PublicationRow row, AggregateType type, UUID aggregateId) {
        return row != null
                && row.type() == type
                && aggregateId.equals(row.aggregateId())
                && "PUBLISHED".equals(row.status())
                && row.currentRevisionId() != null;
    }

    private static boolean validProjectPointer(PublicationRow row, String slug) {
        return row != null
                && row.type() == AggregateType.PROJECT
                && row.aggregateId() != null
                && "PUBLISHED".equals(row.status())
                && row.currentRevisionId() != null
                && slug.equals(row.currentSlug());
    }

    private static DomainException projectNotFound() {
        return new DomainException(
                "PROJECT_NOT_FOUND", HttpStatus.NOT_FOUND, Map.of());
    }

    private static DomainException notFound(AggregateType type) {
        String code = type == AggregateType.SITE
                ? "SITE_NOT_FOUND"
                : "PROJECT_CATALOG_NOT_FOUND";
        return new DomainException(code, HttpStatus.NOT_FOUND, Map.of());
    }

    private static IllegalStateException ownershipFailure() {
        return new IllegalStateException("publication revision ownership is inconsistent");
    }
}
