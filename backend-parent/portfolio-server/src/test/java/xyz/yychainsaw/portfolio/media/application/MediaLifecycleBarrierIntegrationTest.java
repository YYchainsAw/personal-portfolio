package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@Isolated
class MediaLifecycleBarrierIntegrationTest extends PostgresIntegrationTestBase {

    @Test
    void sharedLifecycleLockResidueAbortsAndEvictsThePooledSession()
            throws Exception {
        assertTaintedPooledSessionIsEvicted(
                "select pg_catalog.pg_advisory_lock_shared(?, ?)",
                "ShareLock");
    }

    @Test
    void nestedExclusiveLifecycleLockResidueAbortsAndEvictsThePooledSession()
            throws Exception {
        assertTaintedPooledSessionIsEvicted(
                "select pg_catalog.pg_advisory_lock(?, ?)",
                "ExclusiveLock");
    }

    @Test
    void taintedPooledSessionStopsTheCleanupHandlerBeforeDatabaseOrProviderAccess()
            throws Exception {
        try (HikariDataSource pool = runtimePool()) {
            int contaminatedBackend;
            try (Connection connection = pool.getConnection()) {
                contaminatedBackend = backendPid(connection);
                acquireShared(connection);
            }
            assertThat(lifecycleLockCount(contaminatedBackend, "ShareLock")).isOne();

            PostgresMediaLifecycleBarrier barrier = new PostgresMediaLifecycleBarrier(
                    pool, Duration.ofSeconds(5), Duration.ofSeconds(2));
            MediaCleanupCoordinator coordinator = mock(MediaCleanupCoordinator.class);
            StorageRouter router = mock(StorageRouter.class);
            StorageService storage = mock(StorageService.class);
            Instant now = Instant.parse("2026-07-17T03:00:00Z");
            Instant cutoff = now.minus(Duration.ofDays(30));
            UUID assetId = UUID.fromString("a96c74ed-6066-4c5e-b4e8-e727ea2bce1f");
            String sha256 = "b".repeat(64);
            String originalKey = MediaObjectKeys.originalKey(
                    assetId, sha256, "application/pdf");
            MediaDeletionRequest request = new MediaDeletionRequest(assetId, 11, cutoff);
            MediaDeletionPlan plan = new MediaDeletionPlan(
                    new MediaAssetRecord(
                            assetId,
                            StorageProvider.LOCAL,
                            null,
                            null,
                            originalKey,
                            "tainted.pdf",
                            "application/pdf",
                            2_048,
                            null,
                            null,
                            sha256,
                            MediaStatus.PENDING_DELETE,
                            cutoff,
                            11,
                            Instant.parse("2026-01-01T00:00:00Z"),
                            now),
                    cutoff,
                    List.of(originalKey));
            when(coordinator.prepareDeletion(request, cutoff)).thenReturn(Optional.of(plan));
            when(coordinator.finishDeletion(plan)).thenReturn(true);
            when(router.require(StorageProvider.LOCAL)).thenReturn(storage);
            when(storage.provider()).thenReturn(StorageProvider.LOCAL);
            when(storage.location()).thenReturn(
                    new StorageLocation(StorageProvider.LOCAL, null, null));
            ArchivedMediaCleanupJobHandler handler = new ArchivedMediaCleanupJobHandler(
                    coordinator,
                    barrier,
                    router,
                    Clock.fixed(now, ZoneOffset.UTC),
                    new MediaReferenceResolver(List.of(id -> List.of())));
            JsonNode payload = new ObjectMapper().valueToTree(Map.of(
                    "assetId", assetId.toString(),
                    "version", 11,
                    "cutoffEpochSecond", cutoff.getEpochSecond()));

            assertThatThrownBy(() -> handler.handle(payload))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("MEDIA_LIFECYCLE_BARRIER_FAILED")
                    .hasNoCause();

            verify(coordinator, never()).prepareDeletion(request, cutoff);
            verify(router, never()).require(StorageProvider.LOCAL);
            verify(storage, never()).delete(anyString());
            assertThat(awaitNoLifecycleLocks(contaminatedBackend)).isTrue();
            try (Connection replacement = pool.getConnection()) {
                assertThat(backendPid(replacement)).isNotEqualTo(contaminatedBackend);
            }
        }
    }

    @Test
    void sharedBackupLeaseBlocksDeletionUntilItReleasesThenLeavesNoSessionLock()
            throws Exception {
        DataSource dataSource = runtimeDataSource();
        PostgresMediaLifecycleBarrier barrier = new PostgresMediaLifecycleBarrier(
                dataSource, Duration.ofSeconds(5), Duration.ofSeconds(2));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<AutoCloseable> deletionLease = null;

        try (Connection backup = dataSource.getConnection()) {
            acquireShared(backup);
            deletionLease = executor.submit(barrier::acquireExclusiveDeletionLease);

            assertThat(awaitWaitingExclusiveLock()).isTrue();
            assertThat(deletionLease.isDone()).isFalse();

            assertThat(unlockShared(backup)).isTrue();
            try (AutoCloseable ignored = deletionLease.get(5, TimeUnit.SECONDS)) {
                assertThat(exclusiveLockCount(true)).isOne();
            }

            assertThat(awaitNoLifecycleLocks()).isTrue();
        } finally {
            if (deletionLease != null) {
                deletionLease.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sharedBackupLeaseBlocksTheCleanupHandlerBeforeAnyProviderDelete()
            throws Exception {
        DataSource dataSource = runtimeDataSource();
        PostgresMediaLifecycleBarrier barrier = new PostgresMediaLifecycleBarrier(
                dataSource, Duration.ofSeconds(5), Duration.ofSeconds(2));
        MediaCleanupCoordinator coordinator = mock(MediaCleanupCoordinator.class);
        StorageRouter router = mock(StorageRouter.class);
        StorageService storage = mock(StorageService.class);
        Instant now = Instant.parse("2026-07-17T03:00:00Z");
        Instant cutoff = now.minus(Duration.ofDays(30));
        UUID assetId = UUID.fromString("d2038830-aa80-4b41-81da-a779fb16c81c");
        String sha256 = "a".repeat(64);
        String originalKey = MediaObjectKeys.originalKey(
                assetId, sha256, "application/pdf");
        MediaDeletionPlan plan = new MediaDeletionPlan(
                new MediaAssetRecord(
                        assetId,
                        StorageProvider.LOCAL,
                        null,
                        null,
                        originalKey,
                        "resume.pdf",
                        "application/pdf",
                        1_024,
                        null,
                        null,
                        sha256,
                        MediaStatus.PENDING_DELETE,
                        cutoff,
                        7,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        now),
                cutoff,
                List.of(originalKey));
        MediaDeletionRequest request = new MediaDeletionRequest(assetId, 7, cutoff);
        when(coordinator.prepareDeletion(request, cutoff)).thenReturn(Optional.of(plan));
        when(coordinator.finishDeletion(plan)).thenReturn(true);
        when(router.require(StorageProvider.LOCAL)).thenReturn(storage);
        when(storage.provider()).thenReturn(StorageProvider.LOCAL);
        when(storage.location()).thenReturn(
                new StorageLocation(StorageProvider.LOCAL, null, null));
        ArchivedMediaCleanupJobHandler handler = new ArchivedMediaCleanupJobHandler(
                coordinator,
                barrier,
                router,
                Clock.fixed(now, ZoneOffset.UTC),
                new MediaReferenceResolver(List.of(id -> List.of())));
        JsonNode payload = new ObjectMapper().valueToTree(Map.of(
                "assetId", assetId.toString(),
                "version", 7,
                "cutoffEpochSecond", cutoff.getEpochSecond()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> cleanup = null;

        try (Connection backup = dataSource.getConnection()) {
            acquireShared(backup);
            cleanup = executor.submit(() -> {
                handler.handle(payload);
                return null;
            });

            assertThat(awaitWaitingExclusiveLock()).isTrue();
            assertThat(cleanup.isDone()).isFalse();
            verify(storage, never()).delete(anyString());

            assertThat(unlockShared(backup)).isTrue();
            cleanup.get(5, TimeUnit.SECONDS);

            verify(storage).delete(originalKey);
            verify(coordinator).finishDeletion(plan);
            assertThat(awaitNoLifecycleLocks()).isTrue();
        } finally {
            if (cleanup != null) {
                cleanup.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void acquireShared(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                        "select pg_catalog.pg_advisory_lock_shared(?, ?)");) {
            statement.setInt(1, MediaLifecycleBarrier.NAMESPACE_KEY);
            statement.setInt(2, MediaLifecycleBarrier.MEDIA_KEY);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static void assertTaintedPooledSessionIsEvicted(
            String contaminatingLockSql, String expectedMode) throws Exception {
        try (HikariDataSource pool = runtimePool()) {
            int contaminatedBackend;
            try (Connection connection = pool.getConnection()) {
                contaminatedBackend = backendPid(connection);
                acquire(connection, contaminatingLockSql);
            }
            assertThat(lifecycleLockCount(contaminatedBackend, expectedMode)).isOne();

            PostgresMediaLifecycleBarrier barrier = new PostgresMediaLifecycleBarrier(
                    pool, Duration.ofSeconds(5), Duration.ofSeconds(2));
            assertThatThrownBy(barrier::acquireExclusiveDeletionLease)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("MEDIA_LIFECYCLE_BARRIER_FAILED")
                    .hasNoCause();
            assertThat(awaitNoLifecycleLocks(contaminatedBackend)).isTrue();

            try (Connection replacement = pool.getConnection()) {
                assertThat(backendPid(replacement)).isNotEqualTo(contaminatedBackend);
            }
        }
    }

    private static void acquire(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, MediaLifecycleBarrier.NAMESPACE_KEY);
            statement.setInt(2, MediaLifecycleBarrier.MEDIA_KEY);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static int backendPid(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                        "select pg_catalog.pg_backend_pid()");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            int backendPid = result.getInt(1);
            assertThat(result.wasNull()).isFalse();
            assertThat(result.next()).isFalse();
            return backendPid;
        }
    }

    private static boolean unlockShared(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                        "select pg_catalog.pg_advisory_unlock_shared(?, ?)");) {
            statement.setInt(1, MediaLifecycleBarrier.NAMESPACE_KEY);
            statement.setInt(2, MediaLifecycleBarrier.MEDIA_KEY);
            try (ResultSet result = statement.executeQuery()) {
                boolean unlocked = result.next() && result.getBoolean(1);
                assertThat(result.next()).isFalse();
                return unlocked;
            }
        }
    }

    private static boolean awaitWaitingExclusiveLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (exclusiveLockCount(false) == 1) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static boolean awaitNoLifecycleLocks() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (lifecycleLockCount() == 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static boolean awaitNoLifecycleLocks(int backendPid)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (lifecycleLockCount(backendPid, null) == 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static int exclusiveLockCount(boolean granted) {
        return migratorJdbc().sql("""
                        select count(*)
                        from pg_catalog.pg_locks held
                        where held.locktype = 'advisory'
                          and held.database = (
                              select db.oid
                              from pg_catalog.pg_database db
                              where db.datname = pg_catalog.current_database()
                          )
                          and held.classid::bigint =
                              (cast(:namespace as bigint) & 4294967295)
                          and held.objid::bigint =
                              (cast(:mediaKey as bigint) & 4294967295)
                          and held.objsubid = 2
                          and held.mode = 'ExclusiveLock'
                          and held.granted = :granted
                        """)
                .param("namespace", MediaLifecycleBarrier.NAMESPACE_KEY)
                .param("mediaKey", MediaLifecycleBarrier.MEDIA_KEY)
                .param("granted", granted)
                .query(Integer.class)
                .single();
    }

    private static int lifecycleLockCount() {
        return migratorJdbc().sql("""
                        select count(*)
                        from pg_catalog.pg_locks held
                        where held.locktype = 'advisory'
                          and held.database = (
                              select db.oid
                              from pg_catalog.pg_database db
                              where db.datname = pg_catalog.current_database()
                          )
                          and held.classid::bigint =
                              (cast(:namespace as bigint) & 4294967295)
                          and held.objid::bigint =
                              (cast(:mediaKey as bigint) & 4294967295)
                          and held.objsubid = 2
                        """)
                .param("namespace", MediaLifecycleBarrier.NAMESPACE_KEY)
                .param("mediaKey", MediaLifecycleBarrier.MEDIA_KEY)
                .query(Integer.class)
                .single();
    }

    private static int lifecycleLockCount(int backendPid, String mode) {
        return migratorJdbc().sql("""
                        select count(*)
                        from pg_catalog.pg_locks held
                        where held.locktype = 'advisory'
                          and held.database = (
                              select db.oid
                              from pg_catalog.pg_database db
                              where db.datname = pg_catalog.current_database()
                          )
                          and held.pid = :backendPid
                          and held.classid::bigint =
                              (cast(:namespace as bigint) & 4294967295)
                          and held.objid::bigint =
                              (cast(:mediaKey as bigint) & 4294967295)
                          and held.objsubid = 2
                          and (cast(:mode as text) is null
                               or held.mode = cast(:mode as text))
                          and held.granted
                        """)
                .param("backendPid", backendPid)
                .param("namespace", MediaLifecycleBarrier.NAMESPACE_KEY)
                .param("mediaKey", MediaLifecycleBarrier.MEDIA_KEY)
                .param("mode", mode)
                .query(Integer.class)
                .single();
    }

    private static HikariDataSource runtimePool() {
        HikariConfig configuration = new HikariConfig();
        configuration.setDriverClassName("org.postgresql.Driver");
        configuration.setJdbcUrl(runtimeJdbcUrl());
        configuration.setUsername("test_runtime");
        configuration.setPassword("runtime_test_password");
        configuration.setPoolName("media-lifecycle-test-" + UUID.randomUUID());
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(2_000);
        configuration.setValidationTimeout(1_000);
        return new HikariDataSource(configuration);
    }

    private static DataSource runtimeDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(runtimeJdbcUrl());
        dataSource.setUsername("test_runtime");
        dataSource.setPassword("runtime_test_password");
        return dataSource;
    }

    private static String runtimeJdbcUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=portfolio";
    }
}
