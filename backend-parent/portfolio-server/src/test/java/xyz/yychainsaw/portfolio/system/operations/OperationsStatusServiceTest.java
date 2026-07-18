package xyz.yychainsaw.portfolio.system.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import xyz.yychainsaw.portfolio.system.operations.MaintenanceRunMapper.MaintenanceRunSnapshot;

@ExtendWith(MockitoExtension.class)
class OperationsStatusServiceTest {
    private static final Instant START = Instant.parse("2026-07-18T01:00:00Z");
    private static final Instant FINISH = Instant.parse("2026-07-18T01:05:00Z");
    private static final Instant NOW = Instant.parse("2026-07-18T02:00:00Z");
    private static final String CHECKSUM = "a".repeat(64);

    @Mock MaintenanceRunMapper runs;

    private OperationsStatusService service;

    @BeforeEach
    void setUp() {
        service = new OperationsStatusService(
                runs, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void mapsEveryAllowlistedRunToItsStableCardAndFixedFailureCategory() {
        given(runs.findLatestAllowlistedRuns()).willReturn(List.of(
                succeeded("DATABASE_BACKUP"),
                failed("MEDIA_BACKUP"),
                running("ANALYTICS_AGGREGATE"),
                failed("CONTACT_RETENTION"),
                failed("MEDIA_CLEANUP_SCAN"),
                succeeded("DEPLOYMENT"),
                failed("RESTORE_DRILL")));

        OperationsStatus result = service.read();

        assertThat(result.serverTime()).isEqualTo(NOW);
        assertThat(result.databaseBackup().type()).isEqualTo("DATABASE_BACKUP");
        assertThat(result.databaseBackup().status()).isEqualTo("SUCCEEDED");
        assertThat(result.databaseBackup().artifactChecksum()).isEqualTo(CHECKSUM);
        assertThat(result.mediaBackup().errorCategory())
                .isEqualTo("MEDIA_BACKUP_FAILED");
        assertThat(result.analyticsAggregation().status()).isEqualTo("RUNNING");
        assertThat(result.analyticsAggregation().finishedAt()).isNull();
        assertThat(result.analyticsAggregation().errorCategory()).isNull();
        assertThat(result.contactRetention().errorCategory())
                .isEqualTo("CONTACT_RETENTION_FAILED");
        assertThat(result.mediaCleanup().type()).isEqualTo("MEDIA_CLEANUP_SCAN");
        assertThat(result.mediaCleanup().errorCategory())
                .isEqualTo("MEDIA_CLEANUP_FAILED");
        assertThat(result.deployment().status()).isEqualTo("SUCCEEDED");
        assertThat(result.restoreDrill().errorCategory())
                .isEqualTo("RESTORE_DRILL_FAILED");
        verify(runs).findLatestAllowlistedRuns();
    }

    @Test
    void absentTypesStayExplicitlyNullInTheStatusModel() {
        given(runs.findLatestAllowlistedRuns()).willReturn(List.of(
                succeeded("ANALYTICS_AGGREGATE")));

        OperationsStatus result = service.read();

        assertThat(result.databaseBackup()).isNull();
        assertThat(result.mediaBackup()).isNull();
        assertThat(result.analyticsAggregation()).isNotNull();
        assertThat(result.contactRetention()).isNull();
        assertThat(result.mediaCleanup()).isNull();
        assertThat(result.deployment()).isNull();
        assertThat(result.restoreDrill()).isNull();
    }

    @Test
    void rejectsUnexpectedOrDuplicateRowsInsteadOfReturningUnboundedValues() {
        given(runs.findLatestAllowlistedRuns()).willReturn(List.of(
                succeeded("DATABASE_BACKUP"),
                succeeded("DATABASE_BACKUP")));

        assertThatThrownBy(service::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("operations query returned duplicate types");

        given(runs.findLatestAllowlistedRuns()).willReturn(List.of(
                succeeded("UNBOUNDED_OPERATOR_VALUE")));

        assertThatThrownBy(service::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("operations query returned an unsupported type");
    }

    @Test
    void theQueryBoundaryIsReadOnlyAndUsesAStableSnapshot() throws Exception {
        Method read = OperationsStatusService.class.getMethod("read");
        Transactional transaction = read.getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void springCanCreateTheRequiredClassBasedTransactionalProxy() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(
                        TransactionalProxyConfiguration.class)) {
            OperationsStatusService proxied =
                    context.getBean(OperationsStatusService.class);

            assertThat(AopUtils.isCglibProxy(proxied)).isTrue();
        }
    }

    @Test
    void maintenanceViewRejectsWireValuesOutsideTheFrozenContract() {
        assertThatThrownBy(() -> new MaintenanceView(
                        "DATABASE_BACKUP", "DEAD", START, FINISH,
                        null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maintenance status is invalid");
        assertThatThrownBy(() -> new MaintenanceView(
                        "DATABASE_BACKUP", "SUCCEEDED", START, FINISH,
                        "not-a-checksum", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maintenance checksum is invalid");
        assertThatThrownBy(() -> new MaintenanceView(
                        "DATABASE_BACKUP", "FAILED", START, FINISH,
                        null, "raw exception /secret/path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maintenance error category is invalid");
    }

    private static MaintenanceRunSnapshot succeeded(String type) {
        return new MaintenanceRunSnapshot(type, "SUCCEEDED", START, FINISH, CHECKSUM);
    }

    private static MaintenanceRunSnapshot failed(String type) {
        return new MaintenanceRunSnapshot(type, "FAILED", START, FINISH, null);
    }

    private static MaintenanceRunSnapshot running(String type) {
        return new MaintenanceRunSnapshot(type, "RUNNING", START, null, null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionalProxyConfiguration {
        @Bean
        PlatformTransactionManager transactionManager() {
            return org.mockito.Mockito.mock(PlatformTransactionManager.class);
        }

        @Bean
        MaintenanceRunMapper maintenanceRunMapper() {
            return org.mockito.Mockito.mock(MaintenanceRunMapper.class);
        }

        @Bean
        Clock operationsClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        OperationsStatusService operationsStatusService(
                MaintenanceRunMapper mapper, Clock operationsClock) {
            return new OperationsStatusService(mapper, operationsClock);
        }
    }
}
