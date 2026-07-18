package xyz.yychainsaw.portfolio.publishing.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;

/** Stable administrator-facing state of one publication pointer. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicationStateDto(
        AggregateType aggregateType,
        UUID aggregateId,
        String status,
        long version,
        UUID currentRevisionId,
        Instant publishedAt,
        List<UUID> projectIdsInOrder) {
    private static final Set<String> STATUSES =
            Set.of("UNPUBLISHED", "PUBLISHED", "ARCHIVED");

    public PublicationStateDto {
        aggregateType = Objects.requireNonNull(
                aggregateType, "aggregateType is required");
        aggregateId = Objects.requireNonNull(aggregateId, "aggregateId is required");
        status = Objects.requireNonNull(status, "status is required");
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("unsupported publication status");
        }
        if (version < 0) {
            throw new IllegalArgumentException("publication version must be nonnegative");
        }
        projectIdsInOrder = List.copyOf(Objects.requireNonNull(
                projectIdsInOrder, "projectIdsInOrder is required"));
        if ("UNPUBLISHED".equals(status)) {
            if (version != 0 || currentRevisionId != null || publishedAt != null) {
                throw new IllegalArgumentException("unpublished state must be pristine");
            }
        } else if (version == 0 || currentRevisionId == null || publishedAt == null) {
            throw new IllegalArgumentException(
                    "published or archived state requires a revision and timestamp");
        }
        if (aggregateType != AggregateType.PROJECT_CATALOG
                && !projectIdsInOrder.isEmpty()) {
            throw new IllegalArgumentException(
                    "only a project catalog can contain project order");
        }
        if (new HashSet<>(projectIdsInOrder).size() != projectIdsInOrder.size()) {
            throw new IllegalArgumentException("project order must not contain duplicates");
        }
    }
}
