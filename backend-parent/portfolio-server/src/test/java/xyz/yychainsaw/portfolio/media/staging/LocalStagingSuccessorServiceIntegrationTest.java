package xyz.yychainsaw.portfolio.media.staging;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class LocalStagingSuccessorServiceIntegrationTest extends PostgresIntegrationTestBase {
    private static final String SHA256 = "d".repeat(64);

    @Autowired LocalStagingReservationService reservations;
    @Autowired LocalStagingReservationRepository repository;
    @Autowired LocalStagingSuccessorService successors;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void clearReservationsAndJobs() {
        migratorJdbc().sql("delete from portfolio.local_staging_reservation").update();
        migratorJdbc().sql("""
                        delete from portfolio.background_job
                        where idempotency_key like 'local-staging-cleanup:%'
                        """).update();
    }

    @Test
    void schedulesTheExactNextGenerationInOneShortTransaction() throws Exception {
        UUID assetId = UUID.randomUUID();
        LocalStagingReservation original =
                reservations.reserve(assetId, SHA256, "image/png").reservation();
        OffsetDateTime before = databaseNow();

        assertThat(successors.scheduleFromHandler(original)).isTrue();

        OffsetDateTime after = databaseNow();
        LocalStagingReservation advanced = repository.findByAssetId(assetId).orElseThrow();
        JobRow successor = job(advanced.cleanupJobId());
        assertThat(advanced.generation()).isOne();
        assertThat(advanced.sha256()).isEqualTo(original.sha256());
        assertThat(advanced.mimeType()).isEqualTo(original.mimeType());
        assertThat(advanced.reservedAt()).isEqualTo(original.reservedAt());
        assertThat(advanced.cleanupAfter()).isEqualTo(original.cleanupAfter());
        assertThat(successor.key())
                .isEqualTo("local-staging-cleanup:" + assetId + ':' + SHA256 + ":g1");
        assertThat(successor.payload()).isEqualTo(objectMapper.readTree(
                "{\"assetId\":\"" + assetId + "\",\"generation\":1,"
                        + "\"mimeType\":\"image/png\",\"sha256\":\""
                        + SHA256 + "\"}"));
        assertThat(successor.nextRunAt()).isBetween(
                before.plusMinutes(59), after.plusMinutes(61));
    }

    @Test
    void twoConcurrentExecutionsCreateAtMostOneLiveSuccessor() throws Exception {
        LocalStagingReservation original = reservations
                .reserve(UUID.randomUUID(), SHA256, "application/pdf")
                .reservation();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> scheduleTogether(original, ready, start));
            Future<Boolean> second = executor.submit(() -> scheduleTogether(original, ready, start));
            assertThat(ready.await(10, SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(15, SECONDS), second.get(15, SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            LocalStagingReservation advanced =
                    repository.findByAssetId(original.assetId()).orElseThrow();
            assertThat(advanced.generation()).isOne();
            assertThat(jdbc.sql("""
                            select count(*) from portfolio.background_job
                            where idempotency_key like :prefix
                            """)
                    .param("prefix", "local-staging-cleanup:" + original.assetId() + ":%")
                    .query(Long.class)
                    .single()).isEqualTo(2L);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void staleExecutionDoesNotInsertAnotherJob() {
        LocalStagingReservation original = reservations
                .reserve(UUID.randomUUID(), SHA256, "image/jpeg")
                .reservation();
        assertThat(successors.scheduleFromHandler(original)).isTrue();

        assertThat(successors.scheduleFromHandler(original)).isFalse();

        assertThat(jdbc.sql("""
                        select count(*) from portfolio.background_job
                        where idempotency_key like :prefix
                        """)
                .param("prefix", "local-staging-cleanup:" + original.assetId() + ":%")
                .query(Long.class)
                .single()).isEqualTo(2L);
    }

    @Test
    void deadLetterPathRequiresTheCallersAmbientTransitionTransaction() {
        LocalStagingReservation original = reservations
                .reserve(UUID.randomUUID(), SHA256, "image/jpeg")
                .reservation();

        assertThatThrownBy(() -> successors.scheduleFromDeadLetter(original))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(repository.findByAssetId(original.assetId()).orElseThrow())
                .isEqualTo(original);
    }

    private boolean scheduleTogether(
            LocalStagingReservation original,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, SECONDS)) {
            throw new IllegalStateException("successor start timed out");
        }
        return successors.scheduleFromHandler(original);
    }

    private OffsetDateTime databaseNow() {
        return jdbc.sql("select clock_timestamp()")
                .query(OffsetDateTime.class)
                .single();
    }

    private JobRow job(UUID id) {
        return jdbc.sql("""
                        select idempotency_key, payload::text, next_run_at
                        from portfolio.background_job
                        where id=:id
                        """)
                .param("id", id)
                .query((resultSet, rowNumber) -> new JobRow(
                        resultSet.getString("idempotency_key"),
                        readJson(resultSet.getString("payload")),
                        resultSet.getObject("next_run_at", OffsetDateTime.class)))
                .single();
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception failure) {
            throw new IllegalStateException("test job payload was invalid", failure);
        }
    }

    private record JobRow(String key, JsonNode payload, OffsetDateTime nextRunAt) {}
}
