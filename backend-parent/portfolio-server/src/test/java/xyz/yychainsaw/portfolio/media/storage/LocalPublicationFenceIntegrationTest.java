package xyz.yychainsaw.portfolio.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@Isolated
class LocalPublicationFenceIntegrationTest extends PostgresIntegrationTestBase {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CLEANUP_JOB_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SHA256 = "a".repeat(64);
    private static final String VOLUME_ID = defaultVolumeId();
    private static final String OTHER_VOLUME_ID = "d".repeat(64);
    private static final StorageLocation LOCAL =
            new StorageLocation(StorageProvider.LOCAL, null, null);

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(migratorDataSource())
                .defaultSchema("portfolio")
                .schemas("portfolio")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .load()
                .migrate();
        JdbcClient runtime = JdbcClient.create(runtimeDataSource());
        runtime.sql("""
                        insert into portfolio.local_staging_policy (
                            singleton_key,
                            active_capacity,
                            scan_entry_ceiling,
                            worst_case_entries_per_reservation,
                            reserved_headroom
                        ) values (1, 3, 64, 6, 16)
                        on conflict (singleton_key) do nothing
                        """)
                .update();
        assertThat(runtime.sql(
                        "select portfolio.claim_local_staging_volume(:volumeId)")
                .param("volumeId", VOLUME_ID)
                .query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void sameAssetIsSerializedAcrossIndependentFenceInstancesAndReleasedForReuse() {
        DataSource dataSource = runtimeDataSource();
        LocalPublicationFence first = fence(dataSource, 1, Duration.ofMillis(150));
        LocalPublicationFence second = fence(dataSource, 1, Duration.ofMillis(150));
        LocalStagingPublication publication = publication(ASSET_ID, SHA256, CLEANUP_JOB_ID);
        try {
            persistReservation(publication);
            try (LocalPublicationAuthorization held = first.acquire(publication)) {
                assertFixedFailure(
                        () -> second.acquire(publication),
                        "LOCAL_PUBLICATION_FENCE_TIMEOUT");
            }

            try (LocalPublicationAuthorization reacquired = second.acquire(publication)) {
                reacquired.require(publication, VOLUME_ID);
            }
        } finally {
            deleteReservationAndJobs(publication.assetId());
        }
    }

    @Test
    void aStableHashCollisionOnlySerializesUnrelatedAssets() {
        DataSource dataSource = runtimeDataSource();
        LocalPublicationFence first = fenceWithHash(
                dataSource, Duration.ofMillis(150), ignored -> 7);
        LocalPublicationFence second = fenceWithHash(
                dataSource, Duration.ofMillis(150), ignored -> 7);
        LocalStagingPublication a = publication(ASSET_ID, SHA256, CLEANUP_JOB_ID);
        LocalStagingPublication b = publication(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "b".repeat(64),
                UUID.fromString("44444444-4444-4444-8444-444444444444"));

        try {
            persistReservation(a);
            persistReservation(b);
            try (LocalPublicationAuthorization held = first.acquire(a)) {
                assertFixedFailure(
                        () -> second.acquire(b),
                        "LOCAL_PUBLICATION_FENCE_TIMEOUT");
            }

            try (LocalPublicationAuthorization acquired = second.acquire(b)) {
                acquired.require(b, VOLUME_ID);
            }
        } finally {
            deleteReservationAndJobs(a.assetId());
            deleteReservationAndJobs(b.assetId());
        }
    }

    @Test
    void delayedPublisherReauthenticatesOnlyAfterItObtainsTheSessionLock()
            throws Exception {
        DataSource dataSource = runtimeDataSource();
        CountDownLatch secondAttemptStarted = new CountDownLatch(1);
        LocalPublicationFence first = fence(dataSource, 1, Duration.ofSeconds(2));
        LocalPublicationFence delayed = fence(dataSource, 1, Duration.ofSeconds(2));
        LocalStagingPublication publication = publication(ASSET_ID, SHA256, CLEANUP_JOB_ID);
        try {
            persistReservation(publication);
            LocalPublicationAuthorization held = first.acquire(publication);
            AtomicReference<StorageException> failure = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                secondAttemptStarted.countDown();
                try (LocalPublicationAuthorization ignored = delayed.acquire(publication)) {
                    failure.set(null);
                } catch (StorageException expected) {
                    failure.set(expected);
                }
            });
            waiter.start();
            assertThat(secondAttemptStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            deleteReservation(publication.assetId());
            held.close();
            waiter.join(10_000);

            assertThat(waiter.isAlive()).isFalse();
            assertThat(failure.get()).isNotNull();
            assertThat(failure.get().code()).isEqualTo(
                    "LOCAL_STAGING_AUTHORIZATION_INVALID");
            assertThat(failure.get()).hasNoCause();
        } finally {
            deleteReservationAndJobs(publication.assetId());
        }
    }

    @Test
    void pooledConnectionIsExplicitlyUnlockedBeforeItCanBeReturned() throws Exception {
        HikariDataSource pool = pooledRuntimeDataSource();
        LocalStagingPublication publication = publication(
                ASSET_ID, SHA256, CLEANUP_JOB_ID);
        try {
            persistReservation(publication);
            LocalPublicationFence fence = fence(pool, 1, Duration.ofSeconds(1));

            try (LocalPublicationAuthorization ignored = fence.acquire(publication)) {
                // The authorization owns the pooled session until close.
            }

            try (Connection connection = pool.getConnection();
                    var statement = connection.prepareStatement(
                            "select pg_try_advisory_lock(?, ?)") ) {
                statement.setInt(1, LocalPublicationFence.LOCK_NAMESPACE);
                statement.setInt(2, LocalPublicationFence.stableAssetLockKey(ASSET_ID));
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                }
                try (var unlock = connection.prepareStatement(
                        "select pg_advisory_unlock(?, ?)") ) {
                    unlock.setInt(1, LocalPublicationFence.LOCK_NAMESPACE);
                    unlock.setInt(2, LocalPublicationFence.stableAssetLockKey(ASSET_ID));
                    try (var result = unlock.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getBoolean(1)).isTrue();
                    }
                }
            }
        } finally {
            pool.close();
            deleteReservationAndJobs(publication.assetId());
        }
    }

    @Test
    void terminatingTheFenceBackendInvalidatesAStillCurrentAuthorization() {
        DataSource dataSource = runtimeDataSource();
        LocalPublicationFence fence = fence(dataSource, 1, Duration.ofSeconds(2));
        LocalStagingPublication publication = publication(
                ASSET_ID, SHA256, CLEANUP_JOB_ID);
        try {
            persistReservation(publication);
            LocalPublicationAuthorization authorization = fence.acquire(publication);

            int backendPid = migratorJdbc().sql("""
                        select pid
                        from pg_catalog.pg_locks
                        where locktype='advisory'
                          and database=(
                              select oid from pg_catalog.pg_database
                              where datname=pg_catalog.current_database()
                          )
                          and classid::bigint=(cast(:namespace as bigint) & 4294967295)
                          and objid::bigint=(cast(:lockKey as bigint) & 4294967295)
                          and objsubid=2
                          and mode='ExclusiveLock'
                          and granted
                        """)
                .param("namespace", LocalPublicationFence.LOCK_NAMESPACE)
                .param("lockKey", LocalPublicationFence.stableAssetLockKey(ASSET_ID))
                .query(Integer.class)
                .single();
            assertThat(ownerJdbc().sql("select pg_catalog.pg_terminate_backend(:pid)")
                    .param("pid", backendPid)
                    .query(Boolean.class)
                    .single()).isTrue();

            assertFixedFailure(
                    authorization::reauthenticate,
                    "LOCAL_STAGING_AUTHORIZATION_INVALID");
            assertFixedFailure(
                    authorization::close,
                    "LOCAL_PUBLICATION_FENCE_RELEASE_FAILED");
        } finally {
            deleteReservationAndJobs(publication.assetId());
        }
    }

    @Test
    void losingTheExactAdvisoryLockInvalidatesTheOtherwiseLiveAuthorization()
            throws Exception {
        DataSource dataSource = runtimeDataSource();
        LocalPublicationFence fence = fence(dataSource, 1, Duration.ofSeconds(2));
        LocalStagingPublication publication = publication(
                ASSET_ID, SHA256, CLEANUP_JOB_ID);
        try {
            persistReservation(publication);
            LocalPublicationAuthorization authorization = fence.acquire(publication);

            Connection connection = fenceConnection(authorization);
            try (var unlock = connection.prepareStatement(
                            "select pg_advisory_unlock(?, ?)")) {
                unlock.setInt(1, LocalPublicationFence.LOCK_NAMESPACE);
                unlock.setInt(2, LocalPublicationFence.stableAssetLockKey(ASSET_ID));
                try (var result = unlock.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                    assertThat(result.next()).isFalse();
                }
            }

            assertFixedFailure(
                    authorization::reauthenticate,
                    "LOCAL_STAGING_AUTHORIZATION_INVALID");
            assertFixedFailure(
                    authorization::close,
                    "LOCAL_PUBLICATION_FENCE_RELEASE_FAILED");
        } finally {
            deleteReservationAndJobs(publication.assetId());
        }
    }

    @Test
    void removingTheExactReservationInvalidatesTheOtherwiseLiveAuthorization() {
        UUID assetId = UUID.randomUUID();
        UUID cleanupJobId = UUID.randomUUID();
        LocalStagingPublication publication = publication(assetId, SHA256, cleanupJobId);
        try {
            persistReservation(publication);
            LocalPublicationFence fence = fence(
                    runtimeDataSource(),
                    1,
                    Duration.ofSeconds(2));
            try (LocalPublicationAuthorization authorization = fence.acquire(publication)) {
                assertThat(migratorJdbc().sql("""
                                delete from portfolio.local_staging_reservation
                                where asset_id = :assetId
                                """)
                        .param("assetId", assetId)
                        .update()).isOne();

                assertFixedFailure(
                        authorization::reauthenticate,
                        "LOCAL_STAGING_AUTHORIZATION_INVALID");
            }
        } finally {
            deleteReservationAndJobs(assetId);
        }
    }

    @Test
    void advancingTheReservationInvalidatesTheStaleAuthorization() {
        UUID assetId = UUID.randomUUID();
        LocalStagingPublication publication = publication(
                assetId, SHA256, UUID.randomUUID());
        try {
            persistReservation(publication);
            LocalPublicationFence fence = fence(
                    runtimeDataSource(), 1, Duration.ofSeconds(2));
            try (LocalPublicationAuthorization authorization = fence.acquire(publication)) {
                UUID successorJobId = UUID.randomUUID();
                persistCleanupJob(assetId, successorJobId);
                assertThat(migratorJdbc().sql("""
                                update portfolio.local_staging_reservation
                                set generation = generation + 1,
                                    cleanup_job_id = :successorJobId
                                where asset_id = :assetId
                                """)
                        .param("successorJobId", successorJobId)
                        .param("assetId", assetId)
                        .update()).isOne();

                assertFixedFailure(
                        authorization::reauthenticate,
                        "LOCAL_STAGING_AUTHORIZATION_INVALID");
            }
        } finally {
            deleteReservationAndJobs(assetId);
        }
    }

    @Test
    void initialAuthenticationRejectsAMissingExactReservation() {
        UUID assetId = UUID.randomUUID();
        LocalStagingPublication publication = publication(
                assetId, SHA256, UUID.randomUUID());
        LocalPublicationFence fence = fence(
                runtimeDataSource(), 1, Duration.ofSeconds(2));

        assertFixedFailure(
                () -> fence.acquire(publication),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");
    }

    @Test
    void initialAuthenticationRejectsAPolicyBoundToAnotherVolume() {
        UUID assetId = UUID.randomUUID();
        LocalStagingPublication publication = publication(
                assetId, SHA256, UUID.randomUUID());
        try {
            persistReservation(publication);
            LocalPublicationFence wrongVolume = fence(
                    runtimeDataSource(),
                    OTHER_VOLUME_ID,
                    1,
                    Duration.ofSeconds(2));

            assertFixedFailure(
                    () -> wrongVolume.acquire(publication),
                    "LOCAL_STAGING_AUTHORIZATION_INVALID");
        } finally {
            deleteReservationAndJobs(assetId);
        }
    }

    @Test
    void anUnexpectedNestedLockCannotBeSilentlyReturnedToThePool() throws Exception {
        UUID assetId = UUID.randomUUID();
        UUID cleanupJobId = UUID.randomUUID();
        LocalStagingPublication publication = publication(assetId, SHA256, cleanupJobId);
        HikariDataSource pool = pooledRuntimeDataSource();
        try {
            persistReservation(publication);
            LocalPublicationFence fence = fence(pool, 1, Duration.ofSeconds(2));
            LocalPublicationAuthorization authorization = fence.acquire(publication);
            Connection connection = fenceConnection(authorization);
            try (var nestedLock = connection.prepareStatement(
                            "select pg_advisory_lock(?, ?)")) {
                nestedLock.setInt(1, LocalPublicationFence.LOCK_NAMESPACE);
                nestedLock.setInt(2, LocalPublicationFence.stableAssetLockKey(assetId));
                try (var result = nestedLock.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.next()).isFalse();
                }
            }

            assertFixedFailure(
                    authorization::close,
                    "LOCAL_PUBLICATION_FENCE_RELEASE_FAILED");
        } finally {
            pool.close();
            deleteReservationAndJobs(assetId);
        }
    }

    @Test
    void permitAcquisitionIsFairBoundedAndInterruptionIsPreserved() throws Exception {
        DataSource dataSource = runtimeDataSource();
        LocalPublicationFence fence = fence(dataSource, 1, Duration.ofSeconds(5));
        LocalStagingPublication first = publication(ASSET_ID, SHA256, CLEANUP_JOB_ID);
        LocalStagingPublication second = publication(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "b".repeat(64),
                UUID.fromString("44444444-4444-4444-8444-444444444444"));
        try {
            persistReservation(first);
            LocalPublicationAuthorization held = fence.acquire(first);
            AtomicReference<StorageException> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread waiter = new Thread(() -> {
                try {
                    fence.acquire(second);
                } catch (StorageException expected) {
                    failure.set(expected);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            waiter.start();
            Thread.sleep(100);
            waiter.interrupt();
            waiter.join(10_000);
            held.close();

            assertThat(waiter.isAlive()).isFalse();
            assertThat(failure.get()).isNotNull();
            assertThat(failure.get().code()).isEqualTo(
                    "LOCAL_PUBLICATION_FENCE_INTERRUPTED");
            assertThat(interrupted).isTrue();

            Field permitsField = LocalPublicationFence.class.getDeclaredField("permits");
            permitsField.setAccessible(true);
            assertThat(((Semaphore) permitsField.get(fence)).isFair()).isTrue();
        } finally {
            deleteReservationAndJobs(first.assetId());
        }
    }

    @Test
    void malformedIdentityFailsBeforeAConnectionOrReservationRead() {
        DataSource dataSource = mock(DataSource.class);
        LocalPublicationFence fence = fence(dataSource, 1, Duration.ofMillis(100));
        LocalStagingPublication wrongLocation = new LocalStagingPublication(
                ASSET_ID,
                "staging/" + ASSET_ID + '/' + SHA256 + ".jpg",
                SHA256,
                "image/jpeg",
                new StorageLocation(
                        StorageProvider.TENCENT_COS, "bucket", "ap-hongkong"),
                0,
                CLEANUP_JOB_ID);

        assertFixedFailure(
                () -> fence.acquire(wrongLocation),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");

        try {
            verify(dataSource, org.mockito.Mockito.never()).getConnection();
        } catch (java.sql.SQLException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Test
    void currentSuccessorGenerationCanAcquireAndReauthenticateTheSameFence() {
        DataSource dataSource = runtimeDataSource();
        LocalPublicationFence fence = fence(dataSource, 1, Duration.ofSeconds(1));
        LocalStagingPublication successor = publication(
                ASSET_ID, SHA256, CLEANUP_JOB_ID, 1);

        try {
            persistReservation(successor);
            try (LocalPublicationAuthorization authorization = fence.acquire(successor)) {
                authorization.reauthenticate();
            }
        } finally {
            deleteReservationAndJobs(successor.assetId());
        }
    }

    private static LocalPublicationFence fence(
            DataSource dataSource,
            int concurrency,
            Duration acquireTimeout) {
        return fence(dataSource, VOLUME_ID, concurrency, acquireTimeout);
    }

    private static LocalPublicationFence fence(
            DataSource dataSource,
            String volumeId,
            int concurrency,
            Duration acquireTimeout) {
        return new LocalPublicationFence(
                dataSource,
                volumeId,
                concurrency,
                acquireTimeout,
                Duration.ofSeconds(5),
                System::nanoTime,
                LocalPublicationFence::stableAssetLockKey);
    }

    private static LocalPublicationFence fenceWithHash(
            DataSource dataSource,
            Duration acquireTimeout,
            java.util.function.ToIntFunction<UUID> hash) {
        return new LocalPublicationFence(
                dataSource,
                VOLUME_ID,
                1,
                acquireTimeout,
                Duration.ofSeconds(5),
                System::nanoTime,
                hash);
    }

    private static LocalStagingPublication publication(
            UUID assetId, String sha256, UUID cleanupJobId) {
        return publication(assetId, sha256, cleanupJobId, 0);
    }

    private static LocalStagingPublication publication(
            UUID assetId, String sha256, UUID cleanupJobId, long generation) {
        return new LocalStagingPublication(
                assetId,
                "staging/" + assetId + '/' + sha256 + ".jpg",
                sha256,
                "image/jpeg",
                LOCAL,
                generation,
                cleanupJobId);
    }

    private static void persistReservation(LocalStagingPublication publication) {
        assertThat(publication.generation()).isBetween(0L, 16L);
        for (long generation = 0; generation <= publication.generation(); generation++) {
            UUID cleanupJobId = generation == publication.generation()
                    ? publication.cleanupJobId()
                    : UUID.randomUUID();
            persistCleanupJob(publication.assetId(), cleanupJobId);
            if (generation == 0) {
                insertReservation(publication, cleanupJobId);
            } else {
                assertThat(migratorJdbc().sql("""
                                update portfolio.local_staging_reservation
                                set generation = :generation,
                                    cleanup_job_id = :jobId
                                where asset_id = :assetId
                                """)
                        .param("generation", generation)
                        .param("jobId", cleanupJobId)
                        .param("assetId", publication.assetId())
                        .update()).isOne();
            }
        }
    }

    private static void persistCleanupJob(UUID assetId, UUID cleanupJobId) {
        assertThat(migratorJdbc().sql("""
                        insert into portfolio.background_job (
                            id, job_type, idempotency_key, payload, status,
                            attempts, next_run_at
                        ) values (
                            :jobId, 'CLEAN_LOCAL_STAGING_OBJECT', :idempotencyKey,
                            '{}'::jsonb, 'PENDING', 0,
                            clock_timestamp() + interval '1 day'
                        )
                        """)
                .param("jobId", cleanupJobId)
                .param(
                        "idempotencyKey",
                        "fence-test:" + assetId + ':' + cleanupJobId)
                .update()).isOne();
    }

    private static void insertReservation(
            LocalStagingPublication publication, UUID cleanupJobId) {
        assertThat(migratorJdbc().sql("""
                        insert into portfolio.local_staging_reservation (
                            asset_id, sha256, mime_type, generation,
                            cleanup_job_id, cleanup_after
                        ) values (
                            :assetId, :sha256, :mimeType, 0,
                            :jobId, clock_timestamp() + interval '25 hours'
                        )
                        """)
                .param("assetId", publication.assetId())
                .param("sha256", publication.sha256())
                .param("mimeType", publication.mimeType())
                .param("jobId", cleanupJobId)
                .update()).isOne();
    }

    private static int deleteReservation(UUID assetId) {
        return migratorJdbc().sql("""
                        delete from portfolio.local_staging_reservation
                        where asset_id = :assetId
                        """)
                .param("assetId", assetId)
                .update();
    }

    private static void deleteReservationAndJobs(UUID assetId) {
        deleteReservation(assetId);
        migratorJdbc().sql("""
                        delete from portfolio.background_job
                        where idempotency_key like :idempotencyPrefix
                        """)
                .param("idempotencyPrefix", "fence-test:" + assetId + ":%")
                .update();
    }

    private static DriverManagerDataSource runtimeDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(runtimeJdbcUrl());
        dataSource.setUsername("test_runtime");
        dataSource.setPassword("runtime_test_password");
        return dataSource;
    }

    private static JdbcClient ownerJdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(runtimeJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return JdbcClient.create(dataSource);
    }

    private static Connection fenceConnection(
            LocalPublicationAuthorization authorization) throws Exception {
        Field leaseField = LocalPublicationAuthorization.class.getDeclaredField("lease");
        leaseField.setAccessible(true);
        Object lease = leaseField.get(authorization);
        Field connectionField = lease.getClass().getDeclaredField("connection");
        connectionField.setAccessible(true);
        return (Connection) connectionField.get(lease);
    }

    private static HikariDataSource pooledRuntimeDataSource() {
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(runtimeJdbcUrl());
        configuration.setUsername("test_runtime");
        configuration.setPassword("runtime_test_password");
        configuration.setMaximumPoolSize(2);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(2_000);
        configuration.setPoolName("local-publication-fence-test");
        return new HikariDataSource(configuration);
    }

    private static String runtimeJdbcUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=portfolio";
    }

    private static String defaultVolumeId() {
        String configuredRoot = System.getProperty("portfolio.storage.local.root");
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new ExceptionInInitializerError(
                    "integration local storage root is unavailable");
        }
        try (LocalStorageService storage = new LocalStorageService(
                new LocalStorageProperties(java.nio.file.Path.of(configuredRoot)))) {
            return storage.volumeId();
        } catch (java.io.IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void assertFixedFailure(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(StorageException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code))
                .hasMessage(code)
                .hasNoCause();
    }
}
