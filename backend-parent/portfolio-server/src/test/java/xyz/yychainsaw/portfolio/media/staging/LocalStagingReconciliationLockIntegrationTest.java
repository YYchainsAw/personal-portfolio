package xyz.yychainsaw.portfolio.media.staging;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import xyz.yychainsaw.portfolio.media.storage.LocalStagingAuditExpectation;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class LocalStagingReconciliationLockIntegrationTest
        extends PostgresIntegrationTestBase {
    private static final String SHA256 = "a".repeat(64);

    @Autowired LocalStagingReconciliationService reconciliation;
    @Autowired LocalStagingReservationService reservations;
    @Autowired LocalStagingReservationRepository repository;

    @MockitoSpyBean LocalStorageService storage;

    @BeforeEach
    @AfterEach
    void resetState() {
        migratorJdbc().sql("delete from portfolio.local_staging_reservation").update();
        migratorJdbc().sql("""
                        delete from portfolio.background_job
                        where idempotency_key like 'local-staging-cleanup:%'
                        """).update();
        reset(storage);
    }

    @Test
    void reserveCannotCrossTheDatabaseSnapshotAndFilesystemAuditWindow()
            throws Exception {
        AuditGate gate = gateAudit(Map.of());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        try {
            Future<?> audit = executor.submit(reconciliation::auditDaily);
            assertThat(gate.entered().await(10, SECONDS)).isTrue();

            UUID assetId = UUID.randomUUID();
            Future<LocalStagingReservationReceipt> reserve = executor.submit(() -> {
                mutationStarted.countDown();
                return reservations.reserve(assetId, SHA256, "image/jpeg");
            });
            assertThat(mutationStarted.await(10, SECONDS)).isTrue();

            assertThatThrownBy(() -> reserve.get(300, MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(repository.findByAssetId(assetId)).isEmpty();

            gate.release().countDown();
            audit.get(10, SECONDS);
            assertThat(reserve.get(10, SECONDS).assetId()).isEqualTo(assetId);
        } finally {
            gate.release().countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void releaseCannotCrossTheDatabaseSnapshotAndFilesystemAuditWindow()
            throws Exception {
        LocalStagingReservationReceipt receipt = reservations.reserve(
                UUID.randomUUID(), SHA256, "image/png");
        LocalStagingAuditExpectation expectation = new LocalStagingAuditExpectation(
                receipt.assetId(), SHA256, "image/png");
        AuditGate gate = gateAudit(Map.of(receipt.assetId(), expectation));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        try {
            Future<?> audit = executor.submit(reconciliation::auditDaily);
            assertThat(gate.entered().await(10, SECONDS)).isTrue();

            Future<Boolean> release = executor.submit(() -> {
                mutationStarted.countDown();
                return reservations.releaseExact(receipt.reservation());
            });
            assertThat(mutationStarted.await(10, SECONDS)).isTrue();

            assertThatThrownBy(() -> release.get(300, MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(repository.findByAssetId(receipt.assetId())).contains(
                    receipt.reservation());

            gate.release().countDown();
            audit.get(10, SECONDS);
            assertThat(release.get(10, SECONDS)).isTrue();
        } finally {
            gate.release().countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    private AuditGate gateAudit(
            Map<UUID, LocalStagingAuditExpectation> expectedSnapshot) {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    assertThat(invocation.<Map<UUID, LocalStagingAuditExpectation>>getArgument(0))
                            .containsExactlyInAnyOrderEntriesOf(expectedSnapshot);
                    entered.countDown();
                    if (!release.await(10, SECONDS)) {
                        throw new IllegalStateException("test audit gate timed out");
                    }
                    return null;
                })
                .when(storage)
                .auditReservedStaging(anyMap());
        return new AuditGate(entered, release);
    }

    private record AuditGate(CountDownLatch entered, CountDownLatch release) {}
}
