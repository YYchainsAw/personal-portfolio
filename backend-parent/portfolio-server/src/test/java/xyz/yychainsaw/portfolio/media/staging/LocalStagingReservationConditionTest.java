package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

class LocalStagingReservationConditionTest {
    @Test
    void flywayDisabledMaintenanceCliDoesNotCreateServiceOrQueryVersionSix() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        new ApplicationContextRunner()
                .withPropertyValues("spring.flyway.enabled=false")
                .withUserConfiguration(LocalStagingReservationService.class)
                .withBean(LocalStagingReservationRepository.class, () -> repository)
                .withBean(LocalStorageService.class,
                        () -> mock(LocalStorageService.class))
                .run(context -> {
                    assertThat(context)
                            .doesNotHaveBean(LocalStagingReservationService.class);
                    verifyNoInteractions(repository);
                });
    }

    @Test
    void servletStartupFailsClosedWhenReplicaPolicyDoesNotMatch() {
        LocalStagingReservationRepository repository =
                mock(LocalStagingReservationRepository.class);
        when(repository.findPolicy()).thenReturn(Optional.of(
                new LocalStagingPolicy(2, 64, 6, 16)));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(TransactionStatus.class));

        new WebApplicationContextRunner()
                .withUserConfiguration(LocalStagingReservationService.class)
                .withBean(LocalStagingReservationRepository.class, () -> repository)
                .withBean(ScheduledJobInserter.class,
                        () -> mock(ScheduledJobInserter.class))
                .withBean(LocalStorageService.class,
                        () -> mock(LocalStorageService.class))
                .withBean(LocalStagingPolicyProperties.class,
                        () -> new LocalStagingPolicyProperties(3, 64, 16))
                .withBean(PlatformTransactionManager.class, () -> transactions)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessage("LOCAL_STAGING_POLICY_MISMATCH")
                            .hasNoCause();
                });
    }
}
