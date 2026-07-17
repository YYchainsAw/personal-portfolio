package xyz.yychainsaw.portfolio.publishing.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;

public interface PublishingRepository {
    void ensureProjectPublication(UUID projectId);

    PublicationRow lock(AggregateType type, UUID aggregateId);

    Optional<PublicationRow> find(AggregateType type, UUID aggregateId);

    List<PublicationRow> findPublishedProjects();

    Optional<PublicationRow> findPublishedProjectBySlug(String slug);

    Optional<String> redirectTarget(String oldSlug);

    void insertRevision(RevisionRow revision);

    void insertMediaReferences(UUID revisionId, List<MediaReferenceRow> references);

    boolean casPublish(
            AggregateType type,
            UUID aggregateId,
            long expectedVersion,
            UUID revisionId,
            String slug,
            Instant publishedAt);

    boolean casArchive(
            AggregateType type,
            UUID aggregateId,
            long expectedVersion,
            Instant archivedAt);

    boolean currentSlugOrRedirectExists(String slug, UUID excludingProjectId);

    void insertRedirect(String oldSlug, String newSlug, UUID projectId);

    default void insertRedirect(
            String oldSlug,
            String newSlug,
            UUID projectId,
            Instant createdAt) {
        insertRedirect(oldSlug, newSlug, projectId);
    }

    RevisionRow requireRevision(UUID revisionId);

    List<RevisionRow> history(AggregateType type, UUID aggregateId);

    record PublicationRow(
            AggregateType type,
            UUID aggregateId,
            String status,
            UUID currentRevisionId,
            String currentSlug,
            long version,
            Instant publishedAt) { }

    record RevisionRow(
            UUID id,
            AggregateType type,
            UUID aggregateId,
            long version,
            int schemaVersion,
            String json,
            String checksum,
            UUID publishedBy,
            Instant publishedAt) { }

    record MediaReferenceRow(UUID assetId, String variantName, String usage) { }
}
