package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

@SpringBootTest
class LocalStagingCleanupDeadLetterIntegrationTest extends PostgresIntegrationTestBase {
    private static final String SHA256 = "f".repeat(64);

    @Autowired LocalStagingReservationService reservations;
    @Autowired LocalStagingReservationRepository repository;
    @Autowired LocalStagingSuccessorService successors;
    @Autowired BackgroundJobService jobs;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void clearReservationsAndJobs() {
        migratorJdbc().sql("delete from portfolio.local_staging_reservation").update();
        migratorJdbc().sql("delete from portfolio.background_job").update();
    }

    @Test
    void directTenthFailureAndSuccessorCommitAtomically() {
        LocalStagingReservation current = reservations
                .reserve(UUID.randomUUID(), SHA256, "image/jpeg")
                .reservation();
        markRunning(current.cleanupJobId(), "direct-dead-worker", false);

        assertThat(jobs.fail(
                        current.cleanupJobId(),
                        "direct-dead-worker",
                        10,
                        "JOB_HANDLER_FAILED"))
                .isTrue();

        LocalStagingReservation advanced =
                repository.findByAssetId(current.assetId()).orElseThrow();
        assertThat(advanced.generation()).isOne();
        assertThat(advanced.cleanupJobId()).isNotEqualTo(current.cleanupJobId());
        assertThat(status(current.cleanupJobId())).isEqualTo("DEAD");
        assertThat(status(advanced.cleanupJobId())).isEqualTo("PENDING");
    }

    @Test
    void directDeadRollsBackWhenSuccessorInsertFails() {
        LocalStagingReservation current = reservations
                .reserve(UUID.randomUUID(), SHA256, "image/png")
                .reservation();
        markRunning(current.cleanupJobId(), "direct-rollback-worker", false);
        insertConflictingSuccessor(current);

        assertThatIllegalStateException()
                .isThrownBy(() -> jobs.fail(
                        current.cleanupJobId(),
                        "direct-rollback-worker",
                        10,
                        "JOB_HANDLER_FAILED"))
                .withMessage("job dead-letter hook failed")
                .withNoCause();

        assertThat(status(current.cleanupJobId())).isEqualTo("RUNNING");
        assertThat(leaseOwner(current.cleanupJobId())).isEqualTo("direct-rollback-worker");
        assertThat(repository.findByAssetId(current.assetId()).orElseThrow())
                .isEqualTo(current);
    }

    @Test
    void expiredTenthLeaseAndSuccessorCommitAtomicallyWithPriorExecutionContext() {
        LocalStagingReservation current = reservations
                .reserve(UUID.randomUUID(), SHA256, "application/pdf")
                .reservation();
        markRunning(current.cleanupJobId(), "expired-dead-worker", true);

        assertThat(jobs.leaseNext("polling-worker", Duration.ofMinutes(5))).isEmpty();

        LocalStagingReservation advanced =
                repository.findByAssetId(current.assetId()).orElseThrow();
        assertThat(advanced.generation()).isOne();
        assertThat(advanced.cleanupJobId()).isNotEqualTo(current.cleanupJobId());
        assertThat(status(current.cleanupJobId())).isEqualTo("DEAD");
        assertThat(status(advanced.cleanupJobId())).isEqualTo("PENDING");
    }

    @Test
    void expiredLeaseDeadRollsBackWhenSuccessorInsertFails() {
        LocalStagingReservation current = reservations
                .reserve(UUID.randomUUID(), SHA256, "image/jpeg")
                .reservation();
        markRunning(current.cleanupJobId(), "expired-rollback-worker", true);
        insertConflictingSuccessor(current);

        assertThatIllegalStateException()
                .isThrownBy(() -> jobs.leaseNext("polling-worker", Duration.ofMinutes(5)))
                .withMessage("job dead-letter hook failed")
                .withNoCause();

        assertThat(status(current.cleanupJobId())).isEqualTo("RUNNING");
        assertThat(leaseOwner(current.cleanupJobId())).isEqualTo("expired-rollback-worker");
        assertThat(repository.findByAssetId(current.assetId()).orElseThrow())
                .isEqualTo(current);
    }

    @Test
    void malformedAndImmutablePoisonJobsStayDeadWithoutAHotLoop() {
        LocalStagingReservation malformed = reservations
                .reserve(UUID.randomUUID(), SHA256, "image/jpeg")
                .reservation();
        markRunning(malformed.cleanupJobId(), "malformed-worker", false);
        replacePayload(malformed.cleanupJobId(), "{\"assetId\":\"not-a-uuid\"}");

        assertThat(jobs.fail(
                        malformed.cleanupJobId(),
                        "malformed-worker",
                        10,
                        "JOB_HANDLER_FAILED"))
                .isTrue();
        assertThat(status(malformed.cleanupJobId())).isEqualTo("DEAD");
        assertThat(repository.findByAssetId(malformed.assetId()).orElseThrow())
                .isEqualTo(malformed);

        clearReservationsAndJobs();
        LocalStagingReservation poison = reservations
                .reserve(UUID.randomUUID(), SHA256, "image/jpeg")
                .reservation();
        markRunning(poison.cleanupJobId(), "poison-worker", false);
        replacePayload(poison.cleanupJobId(), """
                {"assetId":"%s","generation":0,"mimeType":"image/jpeg","sha256":"%s"}
                """.formatted(poison.assetId(), "a".repeat(64)));

        assertThat(jobs.fail(
                        poison.cleanupJobId(),
                        "poison-worker",
                        10,
                        "JOB_HANDLER_FAILED"))
                .isTrue();
        assertThat(status(poison.cleanupJobId())).isEqualTo("DEAD");
        assertThat(repository.findByAssetId(poison.assetId()).orElseThrow())
                .isEqualTo(poison);
        assertThat(jobCountFor(poison.assetId())).isOne();
    }

    @Test
    void staleDeadGenerationDoesNotCreateAnotherSuccessor() {
        LocalStagingReservation original = reservations
                .reserve(UUID.randomUUID(), SHA256, "image/jpeg")
                .reservation();
        assertThat(successors.scheduleFromHandler(original)).isTrue();
        LocalStagingReservation current =
                repository.findByAssetId(original.assetId()).orElseThrow();
        markRunning(original.cleanupJobId(), "stale-worker", false);

        assertThat(jobs.fail(
                        original.cleanupJobId(),
                        "stale-worker",
                        10,
                        "JOB_HANDLER_FAILED"))
                .isTrue();

        assertThat(repository.findByAssetId(original.assetId()).orElseThrow())
                .isEqualTo(current);
        assertThat(jobCountFor(original.assetId())).isEqualTo(2L);
        assertThat(status(original.cleanupJobId())).isEqualTo("DEAD");
        assertThat(status(current.cleanupJobId())).isEqualTo("PENDING");
    }

    private void markRunning(UUID jobId, String owner, boolean expired) {
        migratorJdbc().sql("""
                        update portfolio.background_job
                        set status='RUNNING', attempts=10,
                            lease_owner=:owner,
                            lease_until=clock_timestamp()
                                + (:leaseSeconds * interval '1 second'),
                            next_run_at=clock_timestamp() - interval '2 hours'
                        where id=:id
                        """)
                .param("owner", owner)
                .param("leaseSeconds", expired ? -3_600 : 3_600)
                .param("id", jobId)
                .update();
    }

    private void insertConflictingSuccessor(LocalStagingReservation current) {
        String payload = """
                {"assetId":"%s","generation":1,"mimeType":"%s","sha256":"%s"}
                """.formatted(current.assetId(), current.mimeType(), current.sha256());
        migratorJdbc().sql("""
                        insert into portfolio.background_job(
                            id, job_type, idempotency_key, payload, status,
                            attempts, next_run_at, created_at, updated_at
                        ) values (
                            :id, 'CLEAN_LOCAL_STAGING_OBJECT', :key,
                            cast(:payload as jsonb), 'PENDING', 0,
                            clock_timestamp() + interval '1 hour',
                            clock_timestamp(), clock_timestamp()
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("key", "local-staging-cleanup:" + current.assetId()
                        + ':' + current.sha256() + ":g1")
                .param("payload", payload)
                .update();
    }

    private void replacePayload(UUID jobId, String payload) {
        migratorJdbc().sql("""
                        update portfolio.background_job
                        set payload=cast(:payload as jsonb)
                        where id=:id
                        """)
                .param("payload", payload)
                .param("id", jobId)
                .update();
    }

    private String status(UUID jobId) {
        return jdbc.sql("select status from portfolio.background_job where id=:id")
                .param("id", jobId)
                .query(String.class)
                .single();
    }

    private String leaseOwner(UUID jobId) {
        return jdbc.sql("select lease_owner from portfolio.background_job where id=:id")
                .param("id", jobId)
                .query(String.class)
                .single();
    }

    private long jobCountFor(UUID assetId) {
        return jdbc.sql("""
                        select count(*) from portfolio.background_job
                        where idempotency_key like :prefix
                        """)
                .param("prefix", "local-staging-cleanup:" + assetId + ":%")
                .query(Long.class)
                .single();
    }
}
