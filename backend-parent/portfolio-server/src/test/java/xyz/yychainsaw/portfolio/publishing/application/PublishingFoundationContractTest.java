package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.publishing.api.PublicationResult;
import xyz.yychainsaw.portfolio.publishing.api.ReorderCatalogCommand;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;

class PublishingFoundationContractTest {
    @Test
    void reorderCommandDefensivelyCopiesProjectOrder() {
        UUID projectId = UUID.randomUUID();
        var mutable = new java.util.ArrayList<>(List.of(projectId));

        var command = new ReorderCatalogCommand(4L, mutable);
        mutable.clear();

        assertThat(command.projectIdsInOrder()).containsExactly(projectId);
        assertThat(command.projectIdsInOrder()).isUnmodifiable();
    }

    @Test
    void publicationContractsKeepTypedAggregateAndNullableCatalogResult() {
        UUID revisionId = UUID.randomUUID();
        var row = new PublishingRepository.PublicationRow(
                AggregateType.PROJECT, UUID.randomUUID(), "PUBLISHED",
                revisionId, "example", 2L, java.time.Instant.EPOCH);
        var result = new PublicationResult(revisionId, row.version(), null, null, "a".repeat(64));

        assertThat(row.type()).isEqualTo(AggregateType.PROJECT);
        assertThat(result.catalogRevisionId()).isNull();
        assertThat(result.catalogVersion()).isNull();
    }
}
