package xyz.yychainsaw.portfolio.message.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

class MessageRetentionJobHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-18T08:30:00Z");
    private static final UUID RUN_ID =
            UUID.fromString("95000000-0000-4000-8000-000000000001");

    private final MessageRetentionRepository repository =
            mock(MessageRetentionRepository.class);
    private final MessageRetentionJobHandler handler = new MessageRetentionJobHandler(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> RUN_ID);

    @Test
    void exposesExactTypeAndDeletesInStableFiveHundredRowBatches() {
        Instant cutoff = Instant.parse("2025-07-18T08:30:00Z");
        when(repository.deleteExpiredBatch(
                        cutoff, MessageRetentionJobHandler.BATCH_SIZE))
                .thenReturn(500, 2);

        handler.handle(payload(LocalDate.parse("2026-07-18")));

        assertThat(handler.jobType()).isEqualTo("CONTACT_RETENTION");
        InOrder ordered = inOrder(repository);
        ordered.verify(repository).start(RUN_ID, NOW);
        ordered.verify(repository, times(2)).deleteExpiredBatch(cutoff, 500);
        ordered.verify(repository).succeed(RUN_ID, 502, NOW);
    }

    @Test
    void exactPayloadValidationPreventsAnAttackerFromExpandingTheCutoff() {
        JsonNode valid = payload(LocalDate.parse("2026-07-18"));
        JsonNode future = payload(LocalDate.parse("2026-07-19"));
        JsonNode tooOld = payload(LocalDate.parse("1900-01-01"));
        JsonNode extra = JSON.valueToTree(Map.of(
                "siteDate", "2026-07-18",
                "cutoffEpochSecond", 4_102_444_800L));
        JsonNode wrongType = JSON.valueToTree(Map.of("siteDate", 123));

        for (JsonNode invalid : java.util.List.of(
                JSON.nullNode(),
                JSON.createArrayNode(),
                JSON.createObjectNode(),
                future,
                tooOld,
                extra,
                wrongType)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> handler.handle(invalid))
                    .withMessage("CONTACT_RETENTION_PAYLOAD_INVALID")
                    .withNoCause();
        }

        handler.handle(valid);
        verify(repository).start(RUN_ID, NOW);
    }

    @Test
    void dependencyFailuresAreRedactedAndRecordedWithCountsOnly() {
        Instant cutoff = Instant.parse("2025-07-18T08:30:00Z");
        when(repository.deleteExpiredBatch(cutoff, 500)).thenReturn(500, 0);
        doThrow(new IllegalStateException(
                        "visitor@example.com SMTP private response"))
                .when(repository)
                .succeed(RUN_ID, 500, NOW);

        assertThatThrownBy(() -> handler.handle(payload(LocalDate.parse("2026-07-18"))))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("CONTACT_RETENTION_FAILED")
                .hasNoCause();

        verify(repository).fail(RUN_ID, 500, NOW, "CONTACT_RETENTION_FAILED");
    }

    @Test
    void startFailureDoesNotAttemptDeletionOrExposeTheDependencyMessage() {
        doThrow(new IllegalStateException("private database hostname"))
                .when(repository)
                .start(RUN_ID, NOW);

        assertThatThrownBy(() -> handler.handle(payload(LocalDate.parse("2026-07-18"))))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("CONTACT_RETENTION_FAILED")
                .hasNoCause();

        verify(repository, never()).deleteExpiredBatch(any(), any(Integer.class));
        verify(repository, never()).fail(any(), any(Long.class), any(), any());
    }

    @Test
    void schedulerUsesOneDeterministicHongKongDateKeyAndPayload() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        MessageRetentionScheduler scheduler = new MessageRetentionScheduler(
                jobs, Clock.fixed(Instant.parse("2026-07-17T16:00:01Z"), ZoneOffset.UTC));

        scheduler.enqueueCurrentDay();

        verify(jobs).enqueue(
                "CONTACT_RETENTION",
                "contact-retention:2026-07-18",
                Map.of("siteDate", "2026-07-18"));
    }

    private static JsonNode payload(LocalDate siteDate) {
        return JSON.valueToTree(Map.of("siteDate", siteDate.toString()));
    }
}
