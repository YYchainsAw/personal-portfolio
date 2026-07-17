package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;

class LocalStagingStartupAuditTest {
    @Test
    void servletStartupRunsTheAuditAtHighestPrecedenceBeforeReadiness() throws Exception {
        LocalStagingReconciliationService reconciliation =
                mock(LocalStagingReconciliationService.class);
        LocalStagingStartupAudit startup =
                new LocalStagingStartupAudit(reconciliation);

        startup.run(null);

        verify(reconciliation).auditStartup();
        assertThat(OrderUtils.getOrder(LocalStagingStartupAudit.class))
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void migrationDebtFailsStartupWithOneFixedCauseFreeCode() {
        LocalStagingReconciliationService reconciliation =
                mock(LocalStagingReconciliationService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "LOCAL_STAGING_MIGRATION_REQUIRED"))
                .when(reconciliation)
                .auditStartup();
        LocalStagingStartupAudit startup =
                new LocalStagingStartupAudit(reconciliation);

        assertThatThrownBy(() -> startup.run(null))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("LOCAL_STAGING_MIGRATION_REQUIRED")
                .hasNoCause();
    }

    @Test
    void maintenanceNonWebContextNeverInstantiatesOrRunsTheStartupAudit() {
        LocalStagingReconciliationService reconciliation =
                mock(LocalStagingReconciliationService.class);
        new ApplicationContextRunner()
                .withPropertyValues("spring.flyway.enabled=false")
                .withUserConfiguration(LocalStagingStartupAudit.class)
                .withBean(LocalStagingReconciliationService.class, () -> reconciliation)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(LocalStagingStartupAudit.class));

        verifyNoInteractions(reconciliation);
    }

    @Test
    void servletContextHasExactlyOneStartupAuditBoundary() {
        LocalStagingReconciliationService reconciliation =
                mock(LocalStagingReconciliationService.class);
        new WebApplicationContextRunner()
                .withUserConfiguration(LocalStagingStartupAudit.class)
                .withBean(LocalStagingReconciliationService.class, () -> reconciliation)
                .run(context -> assertThat(context)
                        .hasSingleBean(LocalStagingStartupAudit.class));
    }
}
