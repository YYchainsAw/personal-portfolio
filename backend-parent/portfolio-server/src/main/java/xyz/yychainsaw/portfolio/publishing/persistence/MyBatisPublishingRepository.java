package xyz.yychainsaw.portfolio.publishing.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import xyz.yychainsaw.portfolio.publishing.persistence.mybatis.PublishingMapper;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;

@Repository
public class MyBatisPublishingRepository implements PublishingRepository {
    private final PublishingMapper mapper;
    private final Clock clock;

    public MyBatisPublishingRepository(PublishingMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "publishing mapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public void ensureProjectPublication(UUID projectId) {
        mapper.ensureProjectPublication(Objects.requireNonNull(projectId, "projectId"));
    }

    @Override
    public PublicationRow lock(AggregateType type, UUID aggregateId) {
        PublicationRow row = mapper.lock(
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(aggregateId, "aggregateId"));
        if (row == null) {
            throw new NoSuchElementException("publication does not exist");
        }
        return row;
    }

    @Override
    public Optional<PublicationRow> find(AggregateType type, UUID aggregateId) {
        return Optional.ofNullable(mapper.find(
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(aggregateId, "aggregateId")));
    }

    @Override
    public List<PublicationRow> findPublishedProjects() {
        return List.copyOf(mapper.findPublishedProjects());
    }

    @Override
    public Optional<PublicationRow> findPublishedProjectBySlug(String slug) {
        return Optional.ofNullable(mapper.findPublishedProjectBySlug(
                Objects.requireNonNull(slug, "slug")));
    }

    @Override
    public Optional<String> redirectTarget(String oldSlug) {
        return Optional.ofNullable(mapper.redirectTarget(
                Objects.requireNonNull(oldSlug, "oldSlug")));
    }

    @Override
    public void insertRevision(RevisionRow revision) {
        if (mapper.insertRevision(Objects.requireNonNull(revision, "revision")) != 1) {
            throw new IllegalStateException("revision was not inserted");
        }
    }

    @Override
    public void insertMediaReferences(UUID revisionId, List<MediaReferenceRow> references) {
        Objects.requireNonNull(revisionId, "revisionId");
        List<MediaReferenceRow> immutable = List.copyOf(
                Objects.requireNonNull(references, "references"));
        if (!immutable.isEmpty()
                && mapper.insertMediaReferences(revisionId, immutable) != immutable.size()) {
            throw new IllegalStateException("not all revision media references were inserted");
        }
    }

    @Override
    public boolean casPublish(
            AggregateType type,
            UUID aggregateId,
            long expectedVersion,
            UUID revisionId,
            String slug,
            Instant publishedAt) {
        return mapper.casPublish(
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(aggregateId, "aggregateId"),
                expectedVersion,
                Objects.requireNonNull(revisionId, "revisionId"),
                slug,
                Objects.requireNonNull(publishedAt, "publishedAt")) == 1;
    }

    @Override
    public boolean casArchive(
            AggregateType type,
            UUID aggregateId,
            long expectedVersion,
            Instant archivedAt) {
        return mapper.casArchive(
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(aggregateId, "aggregateId"),
                expectedVersion,
                Objects.requireNonNull(archivedAt, "archivedAt")) == 1;
    }

    @Override
    public boolean currentSlugOrRedirectExists(String slug, UUID excludingProjectId) {
        return mapper.currentSlugOrRedirectExists(
                Objects.requireNonNull(slug, "slug"),
                Objects.requireNonNull(excludingProjectId, "excludingProjectId"));
    }

    @Override
    public void insertRedirect(String oldSlug, String newSlug, UUID projectId) {
        insertRedirect(oldSlug, newSlug, projectId, clock.instant());
    }

    @Override
    public void insertRedirect(
            String oldSlug,
            String newSlug,
            UUID projectId,
            Instant createdAt) {
        if (mapper.insertRedirect(
                Objects.requireNonNull(oldSlug, "oldSlug"),
                Objects.requireNonNull(newSlug, "newSlug"),
                Objects.requireNonNull(projectId, "projectId"),
                Objects.requireNonNull(createdAt, "createdAt")) != 1) {
            throw new IllegalStateException("slug redirect was not inserted");
        }
    }

    @Override
    public RevisionRow requireRevision(UUID revisionId) {
        RevisionRow revision = mapper.requireRevision(
                Objects.requireNonNull(revisionId, "revisionId"));
        if (revision == null) {
            throw new NoSuchElementException("revision does not exist");
        }
        return revision;
    }

    @Override
    public List<RevisionRow> history(AggregateType type, UUID aggregateId) {
        return List.copyOf(mapper.history(
                Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(aggregateId, "aggregateId")));
    }
}
