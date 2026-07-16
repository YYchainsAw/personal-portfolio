package xyz.yychainsaw.portfolio.media.storage;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class LocalPublicationFence {
    static final int LOCK_NAMESPACE = 0x59595046;
    private static final String TRY_LOCK_SQL = "select pg_try_advisory_lock(?, ?)";
    private static final String EXACT_UNLOCK_SQL = """
            with released as (
                select pg_catalog.pg_advisory_unlock(?, ?) as unlocked
            )
            select (
                pg_catalog.pg_backend_pid() = ?
                and released.unlocked
                and not exists (
                    select 1
                    from pg_catalog.pg_locks held
                    where held.locktype = 'advisory'
                      and held.database = (
                          select db.oid
                          from pg_catalog.pg_database db
                          where db.datname = pg_catalog.current_database()
                      )
                      and held.pid = pg_catalog.pg_backend_pid()
                      and held.classid::bigint =
                          (cast(? as bigint) & 4294967295)
                      and held.objid::bigint =
                          (cast(? as bigint) & 4294967295)
                      and held.objsubid = 2
                      and held.mode = 'ExclusiveLock'
                      and held.granted
                )
            )
            from released
            """;
    private static final String BACKEND_PID_SQL = "select pg_backend_pid()";
    private static final String AUTHENTICATION_SQL = """
            select (
                pg_catalog.pg_backend_pid() = ?
                and exists (
                    select 1
                    from pg_catalog.pg_locks held
                    where held.locktype = 'advisory'
                      and held.database = (
                          select db.oid
                          from pg_catalog.pg_database db
                          where db.datname = pg_catalog.current_database()
                      )
                      and held.pid = pg_catalog.pg_backend_pid()
                      and held.classid::bigint =
                          (cast(? as bigint) & 4294967295)
                      and held.objid::bigint =
                          (cast(? as bigint) & 4294967295)
                      and held.objsubid = 2
                      and held.mode = 'ExclusiveLock'
                      and held.granted
                )
                and exists (
                    select 1
                    from portfolio.local_staging_policy policy
                    where policy.singleton_key = 1
                      and policy.volume_id = ?
                )
                and exists (
                    select 1
                    from portfolio.local_staging_reservation reservation
                    where reservation.asset_id = ?
                      and reservation.sha256 = ?
                      and reservation.mime_type = ?
                      and reservation.generation = ?
                      and reservation.cleanup_job_id = ?
                )
            )
            """;
    private static final String INVALID = "LOCAL_STAGING_AUTHORIZATION_INVALID";
    private static final String TIMEOUT = "LOCAL_PUBLICATION_FENCE_TIMEOUT";
    private static final String INTERRUPTED = "LOCAL_PUBLICATION_FENCE_INTERRUPTED";
    private static final String TRANSACTION_ACTIVE =
            "LOCAL_PUBLICATION_FENCE_TRANSACTION_ACTIVE";
    private static final String FAILED = "LOCAL_PUBLICATION_FENCE_FAILED";
    private static final String RELEASE_FAILED =
            "LOCAL_PUBLICATION_FENCE_RELEASE_FAILED";
    private static final int MAX_CONCURRENCY = 16;
    private static final Duration MIN_ACQUIRE_TIMEOUT = Duration.ofMillis(1);
    private static final Duration MAX_ACQUIRE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MIN_OPERATION_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAX_OPERATION_TIMEOUT = Duration.ofMinutes(10);
    private static final long LOCK_RETRY_NANOS = Duration.ofMillis(10).toNanos();

    private final DataSource dataSource;
    private final String volumeId;
    private final Semaphore permits;
    private final long acquireTimeoutNanos;
    private final long operationTimeoutNanos;
    private final LongSupplier nanoTime;
    private final ToIntFunction<UUID> assetLockKey;

    @Autowired
    public LocalPublicationFence(
            DataSource dataSource,
            LocalStorageService localStorage,
            @Value("${portfolio.media.local-publication.max-concurrency:2}")
            String maximumConcurrency,
            @Value("${portfolio.media.local-publication.connection-headroom:2}")
            String connectionHeadroom,
            @Value("${portfolio.media.local-publication.acquire-timeout:PT10S}")
            String acquireTimeout,
            @Value("${portfolio.media.local-publication.operation-timeout:PT2M}")
            String operationTimeout) {
        this(
                dataSource,
                requireStorageVolumeId(localStorage),
                productionConfiguration(
                        maximumConcurrency,
                        connectionHeadroom,
                        acquireTimeout,
                        operationTimeout));
    }

    private LocalPublicationFence(
            DataSource dataSource,
            String volumeId,
            ProductionConfiguration configuration) {
        this(
                dataSource,
                volumeId,
                configuration.maximumConcurrency(),
                configuration.acquireTimeout(),
                configuration.operationTimeout(),
                System::nanoTime,
                LocalPublicationFence::stableAssetLockKey);
        validatePoolCapacity(dataSource, configuration);
    }

    LocalPublicationFence(
            DataSource dataSource,
            String volumeId,
            int maximumConcurrency,
            Duration acquireTimeout,
            Duration operationTimeout,
            LongSupplier nanoTime,
            ToIntFunction<UUID> assetLockKey) {
        this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
        if (volumeId == null || !volumeId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("local publication volume id is invalid");
        }
        this.volumeId = volumeId;
        if (maximumConcurrency < 1 || maximumConcurrency > MAX_CONCURRENCY) {
            throw new IllegalArgumentException("local publication concurrency is invalid");
        }
        requireDuration(
                acquireTimeout, MIN_ACQUIRE_TIMEOUT, MAX_ACQUIRE_TIMEOUT,
                "local publication acquire timeout is invalid");
        requireDuration(
                operationTimeout, MIN_OPERATION_TIMEOUT, MAX_OPERATION_TIMEOUT,
                "local publication operation timeout is invalid");
        this.permits = new Semaphore(maximumConcurrency, true);
        this.acquireTimeoutNanos = acquireTimeout.toNanos();
        this.operationTimeoutNanos = operationTimeout.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nano-time source is required");
        this.assetLockKey = Objects.requireNonNull(assetLockKey, "asset lock key is required");
    }

    public LocalPublicationAuthorization acquire(LocalStagingPublication publication) {
        requireInitialPublication(publication);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new StorageException(TRANSACTION_ACTIVE);
        }
        long acquireDeadline = deadlineAfter(nanoTime.getAsLong(), acquireTimeoutNanos);
        acquirePermit(acquireDeadline);

        Connection connection = null;
        boolean lockAcquired = false;
        int backendPid = -1;
        try {
            requireNotInterrupted();
            requireBeforeDeadline(acquireDeadline);
            connection = dataSource.getConnection();
            requireNotInterrupted();
            requireBeforeDeadline(acquireDeadline);
            connection.setAutoCommit(true);
            int lockKey = assetLockKey.applyAsInt(publication.assetId());
            while (!lockAcquired) {
                requireNotInterrupted();
                requireBeforeDeadline(acquireDeadline);
                lockAcquired = tryLock(connection, lockKey, acquireDeadline);
                if (!lockAcquired) {
                    awaitRetry(acquireDeadline);
                }
            }

            backendPid = backendPid(connection, acquireDeadline);
            authenticateExact(
                    connection,
                    publication,
                    backendPid,
                    lockKey,
                    acquireDeadline);
            requireNotInterrupted();
            requireBeforeDeadline(acquireDeadline);
            long operationDeadline = deadlineAfter(
                    nanoTime.getAsLong(), operationTimeoutNanos);
            Connection leasedConnection = connection;
            int capturedBackendPid = backendPid;
            JdbcFenceLease lease = new JdbcFenceLease(
                    leasedConnection,
                    capturedBackendPid,
                    lockKey,
                    permits,
                    () -> authenticateExact(
                            leasedConnection,
                            publication,
                            capturedBackendPid,
                            lockKey,
                            operationDeadline));
            connection = null;
            return new LocalPublicationAuthorization(
                    publication,
                    volumeId,
                    operationDeadline,
                    nanoTime,
                    lease);
        } catch (StorageException failure) {
            cleanupFailedAcquisition(
                    connection, lockAcquired, backendPid, publication, failure);
            throw failure;
        } catch (SQLException | RuntimeException failure) {
            StorageException fixed = new StorageException(
                    lockAcquired ? INVALID : FAILED);
            cleanupFailedAcquisition(
                    connection, lockAcquired, backendPid, publication, fixed);
            throw fixed;
        }
    }

    static int stableAssetLockKey(UUID assetId) {
        Objects.requireNonNull(assetId, "asset id is required");
        long most = assetId.getMostSignificantBits();
        long least = assetId.getLeastSignificantBits();
        return (int) (most ^ (most >>> 32) ^ least ^ (least >>> 32));
    }

    private void acquirePermit(long deadline) {
        long remaining = remainingNanos(deadline);
        if (remaining <= 0) {
            throw new StorageException(TIMEOUT);
        }
        try {
            if (!permits.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                throw new StorageException(TIMEOUT);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StorageException(INTERRUPTED);
        }
    }

    private boolean tryLock(Connection connection, int lockKey, long deadline)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, lockKey);
            statement.setQueryTimeout(queryTimeoutSeconds(deadline));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("advisory lock result missing");
                }
                boolean acquired = result.getBoolean(1);
                if (result.wasNull() || result.next()) {
                    throw new SQLException("advisory lock result invalid");
                }
                return acquired;
            }
        }
    }

    private int backendPid(Connection connection, long deadline) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(BACKEND_PID_SQL)) {
            statement.setQueryTimeout(queryTimeoutSeconds(deadline));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("publication fence backend pid missing");
                }
                int backendPid = result.getInt(1);
                if (backendPid <= 0 || result.wasNull() || result.next()) {
                    throw new SQLException("publication fence backend pid invalid");
                }
                return backendPid;
            }
        }
    }

    private void authenticateExact(
            Connection connection,
            LocalStagingPublication publication,
            int backendPid,
            int lockKey,
            long deadline) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(AUTHENTICATION_SQL)) {
            statement.setInt(1, backendPid);
            statement.setInt(2, LOCK_NAMESPACE);
            statement.setInt(3, lockKey);
            statement.setString(4, volumeId);
            statement.setObject(5, publication.assetId());
            statement.setString(6, publication.sha256());
            statement.setString(7, publication.mimeType());
            statement.setLong(8, publication.generation());
            statement.setObject(9, publication.cleanupJobId());
            statement.setQueryTimeout(queryTimeoutSeconds(deadline));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StorageException(INVALID);
                }
                boolean authenticated = result.getBoolean(1);
                if (!authenticated || result.wasNull() || result.next()) {
                    throw new StorageException(INVALID);
                }
            }
        }
    }

    private void cleanupFailedAcquisition(
            Connection connection,
            boolean lockAcquired,
            int backendPid,
            LocalStagingPublication publication,
            StorageException primary) {
        if (connection == null) {
            permits.release();
            return;
        }
        if (!lockAcquired) {
            closeUnownedConnection(connection);
            permits.release();
            return;
        }
        int lockKey = assetLockKey.applyAsInt(publication.assetId());
        try {
            new JdbcFenceLease(
                    connection, backendPid, lockKey, permits, () -> {}).close();
        } catch (StorageException releaseFailure) {
            primary.addSuppressed(releaseFailure);
        }
    }

    private static void closeUnownedConnection(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException | RuntimeException abortFailure) {
                // This session never acquired the advisory lock.
            }
        }
    }

    private void awaitRetry(long deadline) {
        long remaining = remainingNanos(deadline);
        if (remaining <= 0) {
            throw new StorageException(TIMEOUT);
        }
        LockSupport.parkNanos(Math.min(LOCK_RETRY_NANOS, remaining));
        requireNotInterrupted();
    }

    private void requireBeforeDeadline(long deadline) {
        if (remainingNanos(deadline) <= 0) {
            throw new StorageException(TIMEOUT);
        }
    }

    private long remainingNanos(long deadline) {
        long now = nanoTime.getAsLong();
        if (deadline == Long.MAX_VALUE) {
            return Long.MAX_VALUE - now;
        }
        return deadline - now;
    }

    private static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new StorageException(INTERRUPTED);
        }
    }

    private int queryTimeoutSeconds(long deadline) {
        long remaining = Math.max(1, remainingNanos(deadline));
        long seconds = (remaining + TimeUnit.SECONDS.toNanos(1) - 1)
                / TimeUnit.SECONDS.toNanos(1);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, seconds));
    }

    private static void requireInitialPublication(LocalStagingPublication publication) {
        if (publication == null) {
            throw new StorageException(INVALID);
        }
        publication.requireLocalIdentity();
    }

    private static int parseConcurrency(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) {
                throw new NumberFormatException();
            }
            return Integer.parseInt(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("local publication concurrency is invalid");
        }
    }

    private static String requireStorageVolumeId(LocalStorageService localStorage) {
        if (localStorage == null) {
            throw new IllegalArgumentException("local storage is required");
        }
        return localStorage.volumeId();
    }

    private static int parseConnectionHeadroom(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) {
                throw new NumberFormatException();
            }
            return Integer.parseInt(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "local publication connection headroom is invalid");
        }
    }

    private static Duration parseDuration(
            String value, Duration minimum, Duration maximum) {
        try {
            Duration parsed = Duration.parse(value);
            requireDuration(
                    parsed,
                    minimum,
                    maximum,
                    "local publication timeout is invalid");
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("local publication timeout is invalid");
        }
    }

    private static void requireDuration(
            Duration value,
            Duration minimum,
            Duration maximum,
            String message) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private static long deadlineAfter(long now, long timeoutNanos) {
        try {
            return Math.addExact(now, timeoutNanos);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static ProductionConfiguration productionConfiguration(
            String maximumConcurrency,
            String connectionHeadroom,
            String acquireTimeout,
            String operationTimeout) {
        return new ProductionConfiguration(
                parseConcurrency(maximumConcurrency),
                parseConnectionHeadroom(connectionHeadroom),
                parseDuration(
                        acquireTimeout,
                        MIN_ACQUIRE_TIMEOUT,
                        MAX_ACQUIRE_TIMEOUT),
                parseDuration(
                        operationTimeout,
                        MIN_OPERATION_TIMEOUT,
                        MAX_OPERATION_TIMEOUT));
    }

    private static void validatePoolCapacity(
            DataSource dataSource, ProductionConfiguration configuration) {
        HikariDataSource pool = hikariPool(dataSource);
        long requiredConnections = 2L * configuration.maximumConcurrency()
                + configuration.connectionHeadroom();
        if (pool.getMaximumPoolSize() < requiredConnections) {
            throw new IllegalArgumentException(
                    "local publication pool capacity is invalid");
        }
        if (pool.getConnectionTimeout() > configuration.acquireTimeout().toMillis()) {
            throw new IllegalArgumentException(
                    "local publication pool connection timeout is invalid");
        }
    }

    private static HikariDataSource hikariPool(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            return hikariDataSource;
        }
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                return dataSource.unwrap(HikariDataSource.class);
            }
        } catch (SQLException invalidDataSource) {
            throw new IllegalArgumentException(
                    "local publication data source is invalid");
        }
        throw new IllegalArgumentException("local publication data source is invalid");
    }

    private static final class JdbcFenceLease
            implements LocalPublicationAuthorization.FenceLease {
        private final Connection connection;
        private final int backendPid;
        private final int lockKey;
        private final Semaphore permits;
        private final FenceAuthentication authentication;
        private boolean active = true;

        private JdbcFenceLease(
                Connection connection,
                int backendPid,
                int lockKey,
                Semaphore permits,
                FenceAuthentication authentication) {
            this.connection = connection;
            this.backendPid = backendPid;
            this.lockKey = lockKey;
            this.permits = permits;
            this.authentication = authentication;
        }

        @Override
        public synchronized boolean isHeld() {
            return active;
        }

        @Override
        public synchronized void reauthenticate() {
            if (!active) {
                throw new StorageException(INVALID);
            }
            try {
                authentication.authenticate();
            } catch (SQLException | RuntimeException failure) {
                throw new StorageException(INVALID);
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                if (!active) {
                    return;
                }
                active = false;
            }

            boolean unlocked = unlockExact();
            if (unlocked) {
                boolean closed = closeAfterUnlock();
                permits.release();
                if (!closed) {
                    throw new StorageException(RELEASE_FAILED);
                }
                return;
            }

            boolean terminated = abortPhysicalConnection();
            if (terminated) {
                closeAfterAbort();
                permits.release();
            }
            throw new StorageException(RELEASE_FAILED);
        }

        private boolean unlockExact() {
            try (PreparedStatement statement = connection.prepareStatement(EXACT_UNLOCK_SQL)) {
                statement.setInt(1, LOCK_NAMESPACE);
                statement.setInt(2, lockKey);
                statement.setInt(3, backendPid);
                statement.setInt(4, LOCK_NAMESPACE);
                statement.setInt(5, lockKey);
                statement.setQueryTimeout(1);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            && result.getBoolean(1)
                            && !result.wasNull()
                            && !result.next();
                }
            } catch (SQLException | RuntimeException failure) {
                return false;
            }
        }

        private boolean closeAfterUnlock() {
            try {
                connection.close();
                return true;
            } catch (SQLException | RuntimeException closeFailure) {
                return abortPhysicalConnection();
            }
        }

        private boolean abortPhysicalConnection() {
            try {
                connection.abort(Runnable::run);
                return connection.isClosed();
            } catch (SQLException | RuntimeException abortFailure) {
                try {
                    return connection.isClosed();
                } catch (SQLException stateFailure) {
                    return false;
                }
            }
        }

        private void closeAfterAbort() {
            try {
                connection.close();
            } catch (SQLException | RuntimeException ignored) {
                // The physical session was already verified closed.
            }
        }
    }

    @FunctionalInterface
    private interface FenceAuthentication {
        void authenticate() throws SQLException;
    }

    private record ProductionConfiguration(
            int maximumConcurrency,
            int connectionHeadroom,
            Duration acquireTimeout,
            Duration operationTimeout) {}
}
