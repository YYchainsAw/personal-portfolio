package xyz.yychainsaw.portfolio.media.staging;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInsert;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

@SpringBootTest
@Isolated
class LocalStagingReservationServiceTest extends PostgresIntegrationTestBase {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Autowired LocalStagingReservationService reservations;
    @Autowired LocalStagingReservationRepository reservationRepository;
    @Autowired ScheduledJobInserter scheduledJobs;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired LocalStorageService storage;

    private final Set<UUID> trackedAssetIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> trackedJobIds = ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanupTrackedRows() {
        JdbcClient migrator = migratorJdbc();
        for (UUID assetId : trackedAssetIds) {
            migrator.sql("""
                            delete from portfolio.local_staging_reservation
                            where asset_id=:assetId
                            """)
                    .param("assetId", assetId)
                    .update();
        }
        for (UUID jobId : trackedJobIds) {
            migrator.sql("delete from portfolio.background_job where id=:jobId")
                    .param("jobId", jobId)
                    .update();
        }
        for (UUID assetId : trackedAssetIds) {
            migrator.sql("""
                            delete from portfolio.background_job
                            where idempotency_key like :keyPrefix
                            """)
                    .param("keyPrefix", "local-staging-cleanup:" + assetId + ":%")
                    .update();
        }
        trackedAssetIds.clear();
        trackedJobIds.clear();
    }

    @Test
    void startupPersistsTheExactImmutableReplicaPolicy() {
        StoredPolicy policy = jdbc.sql("""
                        select active_capacity, scan_entry_ceiling,
                               worst_case_entries_per_reservation, reserved_headroom,
                               volume_id
                        from portfolio.local_staging_policy
                        where singleton_key=1
                        """)
                .query((resultSet, rowNumber) -> new StoredPolicy(
                        new LocalStagingPolicy(
                                resultSet.getInt("active_capacity"),
                                resultSet.getInt("scan_entry_ceiling"),
                                resultSet.getInt("worst_case_entries_per_reservation"),
                                resultSet.getInt("reserved_headroom")),
                        resultSet.getString("volume_id")))
                .single();

        assertThat(policy.configuration())
                .isEqualTo(new LocalStagingPolicy(3, 64, 6, 16));
        assertThat(policy.volumeId()).isEqualTo(storage.volumeId());
        assertThatThrownBy(() -> reservations.initializePolicy(
                        new LocalStagingPolicy(2, 64, 6, 16)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LOCAL_STAGING_POLICY_MISMATCH")
                .hasNoCause();
    }

    @Test
    void concurrentReplicasRevalidateOnePolicyAndVolumeUnderTheAdvisoryLock()
            throws Exception {
        LocalStagingPolicy expected = new LocalStagingPolicy(3, 64, 6, 16);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int replica = 0; replica < 4; replica++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, SECONDS)) {
                        throw new IllegalStateException("test start timed out");
                    }
                    reservations.initializePolicy(expected);
                    return null;
                }));
            }
            assertThat(ready.await(10, SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(15, SECONDS);
            }

            assertThat(migratorJdbc().sql("""
                            select count(*)
                            from portfolio.local_staging_policy
                            where singleton_key=1
                              and active_capacity=3
                              and scan_entry_ceiling=64
                              and worst_case_entries_per_reservation=6
                              and reserved_headroom=16
                              and volume_id=:volumeId
                            """)
                    .param("volumeId", storage.volumeId())
                    .query(Long.class)
                    .single()).isOne();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
            reservations.initializePolicy(expected);
        }
    }

    @Test
    void reservationAndGenerationZeroJobCommitAtomicallyWithDatabaseTime() throws Exception {
        OffsetDateTime before = databaseNow();
        UUID assetId = trackedAssetId();

        LocalStagingReservationReceipt receipt =
                reservations.reserve(assetId, SHA_A, "image/jpeg");

        OffsetDateTime after = databaseNow();
        JobRow job = job(receipt.cleanupJobId());
        LocalStagingReservation stored = reservations.authenticateCurrent(
                assetId, SHA_A, "image/jpeg", 0L, receipt.cleanupJobId());

        assertThat(receipt.assetId()).isEqualTo(assetId);
        assertThat(receipt.generation()).isZero();
        assertThat(receipt.reservedAt()).isBetween(before, after);
        assertThat(receipt.cleanupAfter()).isEqualTo(receipt.reservedAt().plusHours(24));
        assertThat(stored).isEqualTo(receipt.reservation());
        assertThat(job.jobType()).isEqualTo("CLEAN_LOCAL_STAGING_OBJECT");
        assertThat(job.idempotencyKey())
                .isEqualTo("local-staging-cleanup:" + assetId + ":" + SHA_A + ":g0");
        assertThat(job.status()).isEqualTo("PENDING");
        assertThat(job.nextRunAt()).isEqualTo(receipt.cleanupAfter());
        assertThat(job.payload()).isEqualTo(objectMapper.readTree(
                "{\"assetId\":\"" + assetId + "\",\"generation\":0,"
                        + "\"mimeType\":\"image/jpeg\",\"sha256\":\""
                        + SHA_A + "\"}"));
        assertThat(job.payload().has("key")).isFalse();
        assertThat(job.payload().has("objectKey")).isFalse();
    }

    @Test
    void crossReplicaCapacityLockAllowsExactlyNAndRejectsNPlusOneWithoutAJob()
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        List<UUID> assetIds = List.of(
                trackedAssetId(), trackedAssetId(), trackedAssetId(), trackedAssetId());
        List<Future<ReservationOutcome>> futures = new ArrayList<>();
        try {
            for (UUID assetId : assetIds) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, SECONDS)) {
                        throw new IllegalStateException("test start timed out");
                    }
                    try {
                        return new ReservationOutcome(
                                reservations.reserve(assetId, SHA_A, "image/png"), null);
                    } catch (IllegalStateException failure) {
                        return new ReservationOutcome(null, failure.getMessage());
                    }
                }));
            }
            assertThat(ready.await(10, SECONDS)).isTrue();
            start.countDown();

            List<ReservationOutcome> outcomes = new ArrayList<>();
            for (Future<ReservationOutcome> future : futures) {
                outcomes.add(future.get(15, SECONDS));
            }
            assertThat(outcomes).filteredOn(outcome -> outcome.receipt() != null).hasSize(3);
            assertThat(outcomes).extracting(ReservationOutcome::failure)
                    .containsOnlyOnce("LOCAL_STAGING_CAPACITY_EXHAUSTED");
            assertThat(assetIds.stream()
                    .filter(assetId -> reservationRepository
                            .findByAssetId(assetId).isPresent())
                    .count()).isEqualTo(3L);
            assertThat(countTrackedCleanupJobs(assetIds)).isEqualTo(3L);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void duplicateAssetWithDifferentShaRollsBackTheNewJobAndNeverAuthorizes() {
        UUID assetId = trackedAssetId();
        reservations.reserve(assetId, SHA_A, "application/pdf");

        assertThatThrownBy(() -> reservations.reserve(assetId, SHA_B, "application/pdf"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LOCAL_STAGING_RESERVATION_FAILED")
                .hasNoCause();

        assertThat(jdbc.sql("""
                        select count(*) from portfolio.background_job
                        where idempotency_key=:key
                        """)
                .param("key", "local-staging-cleanup:" + assetId + ":" + SHA_B + ":g0")
                .query(Long.class)
                .single()).isZero();
        assertThat(reservationRepository.findByAssetId(assetId)).isPresent();
    }

    @Test
    void exactReauthenticationFailsClosedForMissingOrMismatchedIdentity() {
        UUID assetId = trackedAssetId();
        LocalStagingReservationReceipt receipt =
                reservations.reserve(assetId, SHA_A, "image/jpeg");

        List<Runnable> invalid = List.of(
                () -> reservations.authenticateCurrent(
                        UUID.randomUUID(), SHA_A, "image/jpeg", 0, receipt.cleanupJobId()),
                () -> reservations.authenticateCurrent(
                        assetId, SHA_B, "image/jpeg", 0, receipt.cleanupJobId()),
                () -> reservations.authenticateCurrent(
                        assetId, SHA_A, "image/png", 0, receipt.cleanupJobId()),
                () -> reservations.authenticateCurrent(
                        assetId, SHA_A, "image/jpeg", 1, receipt.cleanupJobId()),
                () -> reservations.authenticateCurrent(
                        assetId, SHA_A, "image/jpeg", 0, UUID.randomUUID()));

        for (Runnable action : invalid) {
            assertThatThrownBy(action::run)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("LOCAL_STAGING_RESERVATION_INVALID")
                    .hasNoCause();
        }
    }

    @Test
    void successorPrimitivesLockAndCasExactIdentityWithoutLeavingOrphanJobs() {
        UUID assetId = trackedAssetId();
        LocalStagingReservationReceipt receipt =
                reservations.reserve(assetId, SHA_A, "image/jpeg");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        UUID successorId = transaction.execute(status -> {
            LocalStagingReservation current = reservationRepository
                    .findByAssetIdForUpdate(assetId)
                    .orElseThrow();
            ScheduledJobInsert successor = scheduledJobs.insertAfter(
                    "CLEAN_LOCAL_STAGING_OBJECT",
                    "local-staging-cleanup:" + assetId + ":" + SHA_A + ":g1",
                    Map.of(
                            "assetId", assetId.toString(),
                            "generation", 1L,
                            "mimeType", "image/jpeg",
                            "sha256", SHA_A),
                    Duration.ofHours(24));
            assertThat(reservationRepository.advanceSuccessorExact(
                    current, successor.jobId())).isTrue();
            return successor.jobId();
        });

        LocalStagingReservation advanced = reservationRepository
                .findByAssetId(assetId)
                .orElseThrow();
        assertThat(advanced.generation()).isOne();
        assertThat(advanced.cleanupJobId()).isEqualTo(successorId);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    ScheduledJobInsert orphan = scheduledJobs.insertAfter(
                            "CLEAN_LOCAL_STAGING_OBJECT",
                            "local-staging-cleanup:" + assetId + ":" + SHA_A + ":g2",
                            Map.of(
                                    "assetId", assetId.toString(),
                                    "generation", 2L,
                                    "mimeType", "image/jpeg",
                                    "sha256", SHA_A),
                            Duration.ofHours(24));
                    if (!reservationRepository.advanceSuccessorExact(
                            receipt.reservation(), orphan.jobId())) {
                        throw new IllegalStateException("synthetic stale CAS");
                    }
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic stale CAS");
        assertThat(jdbc.sql("""
                        select count(*) from portfolio.background_job
                        where idempotency_key=:key
                        """)
                .param("key", "local-staging-cleanup:" + assetId + ":" + SHA_A + ":g2")
                .query(Long.class)
                .single()).isZero();
    }

    @Test
    void scheduledInsertOutsideAnAmbientTransactionFailsBeforeSql() {
        String key = "local-staging-cleanup:transaction-probe:"
                + UUID.randomUUID();

        assertThatThrownBy(() -> scheduledJobs.insertAfter(
                        "CLEAN_LOCAL_STAGING_OBJECT",
                        key,
                        Map.of(),
                        Duration.ofHours(24)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JOB_INSERT_REQUIRES_TRANSACTION")
                .hasNoCause();

        assertThat(jdbc.sql("""
                        select count(*)
                        from portfolio.background_job
                        where idempotency_key=:key
                        """)
                .param("key", key)
                .query(Long.class)
                .single()).isZero();
    }

    @Test
    void exactReleaseDeletesOnlyTheCurrentReservationAndRetainsJobHistory() {
        UUID assetId = trackedAssetId();
        LocalStagingReservationReceipt receipt =
                reservations.reserve(assetId, SHA_A, "application/pdf");

        assertThat(reservations.releaseExact(receipt.reservation())).isTrue();
        assertThat(reservations.releaseExact(receipt.reservation())).isFalse();
        assertThat(reservationRepository.findByAssetId(assetId)).isEmpty();
        assertThat(jdbc.sql("""
                        select count(*) from portfolio.background_job
                        where id=:id
                        """)
                .param("id", receipt.cleanupJobId())
                .query(Long.class)
                .single()).isOne();
    }

    @Test
    void deterministicReservationReadIsHardLimitedByTheCallerBound() {
        List<UUID> ids = List.of(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                UUID.fromString("00000000-0000-4000-8000-000000000002"),
                UUID.fromString("00000000-0000-4000-8000-000000000003"));
        trackedAssetIds.addAll(ids);
        for (UUID id : ids) {
            reservations.reserve(id, SHA_A, "image/jpeg");
        }

        assertThat(reservationRepository.findAllBounded(2))
                .extracting(LocalStagingReservation::assetId)
                .containsExactly(ids.get(0), ids.get(1));
        assertThatThrownBy(() -> reservationRepository.findAllBounded(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("local staging reservation limit is invalid")
                .hasNoCause();
    }

    @Test
    void stalledReservationExistsQueryUsesDatabaseTimeAndStrictOlderThanSevenDays() {
        insertReservationAtDatabaseAge(Duration.ofDays(7).minusHours(1));
        assertThat(reservationRepository.hasStalledReservation()).isFalse();

        cleanupTrackedRows();
        insertReservationAtDatabaseAge(Duration.ofDays(7).plusSeconds(1));
        assertThat(reservationRepository.hasStalledReservation()).isTrue();
    }

    @Test
    void reservationRejectsAStalledChainWithoutCreatingAJobOrReservation() {
        insertReservationAtDatabaseAge(Duration.ofDays(7).plusSeconds(1));
        UUID assetId = trackedAssetId();

        assertThatThrownBy(() -> reservations.reserve(
                        assetId, SHA_B, "image/png"))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("LOCAL_STAGING_CHAIN_STALLED")
                .hasNoCause();

        assertThat(reservationRepository.findByAssetId(assetId)).isEmpty();
        assertThat(jdbc.sql("""
                        select count(*)
                        from portfolio.background_job
                        where idempotency_key=:key
                        """)
                .param("key", "local-staging-cleanup:"
                        + assetId + ":" + SHA_B + ":g0")
                .query(Long.class)
                .single()).isZero();
    }

    private void insertReservationAtDatabaseAge(Duration age) {
        UUID assetId = trackedAssetId();
        UUID jobId = trackedJobId();
        migratorJdbc().sql("""
                        insert into portfolio.background_job(
                            id, job_type, idempotency_key, payload,
                            status, next_run_at
                        ) values (
                            :jobId, 'CLEAN_LOCAL_STAGING_OBJECT', :key,
                            '{}'::jsonb, 'PENDING', clock_timestamp()
                        )
                        """)
                .param("jobId", jobId)
                .param("key", "local-staging-cleanup:test:" + jobId)
                .update();
        migratorJdbc().sql("""
                        insert into portfolio.local_staging_reservation(
                            asset_id, sha256, mime_type, generation,
                            cleanup_job_id, reserved_at, cleanup_after
                        ) values (
                            :assetId, :sha256, 'image/jpeg', 0,
                            :jobId,
                            clock_timestamp() - make_interval(secs => :ageSeconds),
                            clock_timestamp() - make_interval(secs => :ageSeconds)
                                + interval '24 hours'
                        )
                        """)
                .param("assetId", assetId)
                .param("sha256", SHA_A)
                .param("jobId", jobId)
                .param("ageSeconds", age.toSeconds())
                .update();
    }

    private long countTrackedCleanupJobs(List<UUID> assetIds) {
        long count = 0;
        for (UUID assetId : assetIds) {
            count += jdbc.sql("""
                            select count(*)
                            from portfolio.background_job
                            where idempotency_key like :keyPrefix
                            """)
                    .param("keyPrefix", "local-staging-cleanup:" + assetId + ":%")
                    .query(Long.class)
                    .single();
        }
        return count;
    }

    private UUID trackedAssetId() {
        UUID assetId = UUID.randomUUID();
        trackedAssetIds.add(assetId);
        return assetId;
    }

    private UUID trackedJobId() {
        UUID jobId = UUID.randomUUID();
        trackedJobIds.add(jobId);
        return jobId;
    }

    private OffsetDateTime databaseNow() {
        return jdbc.sql("select clock_timestamp()")
                .query(OffsetDateTime.class)
                .single();
    }

    private JobRow job(UUID id) {
        return jdbc.sql("""
                        select job_type, idempotency_key, payload::text,
                               status, next_run_at
                        from portfolio.background_job
                        where id=:id
                        """)
                .param("id", id)
                .query((resultSet, rowNumber) -> new JobRow(
                        resultSet.getString("job_type"),
                        resultSet.getString("idempotency_key"),
                        readJson(resultSet.getString("payload")),
                        resultSet.getString("status"),
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

    private record JobRow(
            String jobType,
            String idempotencyKey,
            JsonNode payload,
            String status,
            OffsetDateTime nextRunAt) {}

    private record ReservationOutcome(
            LocalStagingReservationReceipt receipt, String failure) {}

    private record StoredPolicy(LocalStagingPolicy configuration, String volumeId) {}
}
