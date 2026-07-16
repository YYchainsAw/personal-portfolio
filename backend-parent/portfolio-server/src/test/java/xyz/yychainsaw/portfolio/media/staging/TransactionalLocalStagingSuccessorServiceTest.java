package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

class TransactionalLocalStagingSuccessorServiceTest {
    @Test
    void generationOverflowFixedFailsBeforeInsertingAJob() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        ScheduledJobInserter jobs = mock(ScheduledJobInserter.class);
        TransactionalLocalStagingSuccessorService service =
                new TransactionalLocalStagingSuccessorService(repository, jobs);
        OffsetDateTime reservedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
        LocalStagingReservation exhausted = new LocalStagingReservation(
                UUID.fromString("5d257cbc-0992-43d1-9157-79c8b430c95f"),
                "e".repeat(64),
                "image/jpeg",
                Long.MAX_VALUE,
                UUID.fromString("75a8a888-c2de-46a9-a5d8-8b36de762a9d"),
                reservedAt,
                reservedAt.plusHours(24));
        when(repository.findByAssetIdForUpdate(exhausted.assetId()))
                .thenReturn(Optional.of(exhausted));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.scheduleFromHandler(exhausted))
                .withMessage("LOCAL_STAGING_SUCCESSOR_GENERATION_EXHAUSTED")
                .withNoCause();
        verifyNoInteractions(jobs);
    }
}
