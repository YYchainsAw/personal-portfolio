package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

class LocalStagingCleanupConditionTest {
    @Test
    void nonWebMaintenanceContextDoesNotInstantiateCleanupRuntimeOrTouchVersionFour() {
        LocalStagingReservationRepository reservations =
                mock(LocalStagingReservationRepository.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        ScheduledJobInserter jobs = mock(ScheduledJobInserter.class);

        new ApplicationContextRunner()
                .withPropertyValues("spring.flyway.enabled=false")
                .withUserConfiguration(
                        CleanLocalStagingObjectJobHandler.class,
                        TransactionalLocalStagingSuccessorService.class,
                        LocalStagingCleanupConfiguration.class)
                .withBean(LocalStagingReservationRepository.class, () -> reservations)
                .withBean(MediaAssetRepository.class, () -> assets)
                .withBean(ScheduledJobInserter.class, () -> jobs)
                .run(context -> {
                    assertThat(context)
                            .doesNotHaveBean(CleanLocalStagingObjectJobHandler.class)
                            .doesNotHaveBean(LocalStagingSuccessorService.class)
                            .doesNotHaveBean(LocalStagingObjectCleanupPort.class);
                    verifyNoInteractions(reservations, assets, jobs);
                });
    }
}
