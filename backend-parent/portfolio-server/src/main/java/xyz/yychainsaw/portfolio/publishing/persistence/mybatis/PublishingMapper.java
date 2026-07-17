package xyz.yychainsaw.portfolio.publishing.persistence.mybatis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.MediaReferenceRow;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.PublicationRow;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.RevisionRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;

@Mapper
public interface PublishingMapper {
    int ensureProjectPublication(@Param("projectId") UUID projectId);

    PublicationRow lock(
            @Param("type") AggregateType type,
            @Param("aggregateId") UUID aggregateId);

    PublicationRow find(
            @Param("type") AggregateType type,
            @Param("aggregateId") UUID aggregateId);

    List<PublicationRow> findPublishedProjects();

    PublicationRow findPublishedProjectBySlug(@Param("slug") String slug);

    String redirectTarget(@Param("oldSlug") String oldSlug);

    int insertRevision(@Param("revision") RevisionRow revision);

    int insertMediaReferences(
            @Param("revisionId") UUID revisionId,
            @Param("references") List<MediaReferenceRow> references);

    int casPublish(
            @Param("type") AggregateType type,
            @Param("aggregateId") UUID aggregateId,
            @Param("expectedVersion") long expectedVersion,
            @Param("revisionId") UUID revisionId,
            @Param("slug") String slug,
            @Param("publishedAt") Instant publishedAt);

    int casArchive(
            @Param("type") AggregateType type,
            @Param("aggregateId") UUID aggregateId,
            @Param("expectedVersion") long expectedVersion,
            @Param("archivedAt") Instant archivedAt);

    boolean currentSlugOrRedirectExists(
            @Param("slug") String slug,
            @Param("excludingProjectId") UUID excludingProjectId);

    int insertRedirect(
            @Param("oldSlug") String oldSlug,
            @Param("newSlug") String newSlug,
            @Param("projectId") UUID projectId,
            @Param("createdAt") Instant createdAt);

    RevisionRow requireRevision(@Param("revisionId") UUID revisionId);

    List<RevisionRow> history(
            @Param("type") AggregateType type,
            @Param("aggregateId") UUID aggregateId);
}
