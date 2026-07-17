package xyz.yychainsaw.portfolio.system.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class BackgroundJobRetentionServiceTest {

    @Test
    void stopsAfterTheFirstShortBatchAndReturnsOnlyAggregateCounts() {
        BackgroundJobRetentionRepository repository =
                mock(BackgroundJobRetentionRepository.class);
        when(repository.deleteExpiredTerminalBatch(500))
                .thenReturn(500, 231);
        BackgroundJobRetentionService service =
                new BackgroundJobRetentionService(repository, 500, 10);

        BackgroundJobRetentionResult result = service.deleteExpiredTerminalJobs();

        assertThat(result).isEqualTo(new BackgroundJobRetentionResult(731, 2));
        verify(repository, times(2)).deleteExpiredTerminalBatch(500);
    }

    @Test
    void neverExceedsTenBatchesOrFiveThousandRowsPerRun() {
        BackgroundJobRetentionRepository repository =
                mock(BackgroundJobRetentionRepository.class);
        when(repository.deleteExpiredTerminalBatch(500)).thenReturn(500);
        BackgroundJobRetentionService service =
                new BackgroundJobRetentionService(repository, 500, 10);

        BackgroundJobRetentionResult result = service.deleteExpiredTerminalJobs();

        assertThat(result).isEqualTo(new BackgroundJobRetentionResult(5_000, 10));
        verify(repository, times(10)).deleteExpiredTerminalBatch(500);
    }

    @Test
    void rejectsConfigurationThatCanExceedTheOperationalCeiling() {
        BackgroundJobRetentionRepository repository =
                mock(BackgroundJobRetentionRepository.class);

        for (int invalidBatchSize : new int[] {0, 501}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BackgroundJobRetentionService(
                            repository, invalidBatchSize, 10))
                    .withMessage("background job retention configuration is invalid")
                    .withNoCause();
        }
        for (int invalidBatchCount : new int[] {0, 11}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new BackgroundJobRetentionService(
                            repository, 500, invalidBatchCount))
                    .withMessage("background job retention configuration is invalid")
                    .withNoCause();
        }
    }

    @Test
    void repositoryFailureHasOneFixedCauseFreeSurface() {
        BackgroundJobRetentionRepository repository =
                mock(BackgroundJobRetentionRepository.class);
        when(repository.deleteExpiredTerminalBatch(500))
                .thenThrow(new IllegalStateException(
                        "payload={secret} key=private-key id=private-id"));
        BackgroundJobRetentionService service =
                new BackgroundJobRetentionService(repository, 500, 10);

        assertThatThrownBy(service::deleteExpiredTerminalJobs)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("BACKGROUND_JOB_RETENTION_FAILED")
                .hasNoCause();
    }

    @Test
    void forgedRepositoryCountsAreRejectedCauseFree() {
        BackgroundJobRetentionRepository repository =
                mock(BackgroundJobRetentionRepository.class);
        when(repository.deleteExpiredTerminalBatch(500)).thenReturn(501);
        BackgroundJobRetentionService service =
                new BackgroundJobRetentionService(repository, 500, 10);

        assertThatThrownBy(service::deleteExpiredTerminalJobs)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("BACKGROUND_JOB_RETENTION_FAILED")
                .hasNoCause();
    }
}
