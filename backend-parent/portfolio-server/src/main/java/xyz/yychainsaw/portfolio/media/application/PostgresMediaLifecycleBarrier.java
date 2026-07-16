package xyz.yychainsaw.portfolio.media.application;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class PostgresMediaLifecycleBarrier implements MediaLifecycleBarrier {
    private static final String BACKEND_PID_SQL =
            "select pg_catalog.pg_backend_pid()";
    private static final String CLEAN_SESSION_SQL = """
            select (
                pg_catalog.pg_backend_pid() = ?
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
                      and held.granted
                )
            )
            """;
    private static final String ACQUIRE_SQL =
            "select pg_catalog.pg_advisory_lock(?, ?)";
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
                      and held.granted
                )
            )
            from released
            """;
    private static final String FAILED = "MEDIA_LIFECYCLE_BARRIER_FAILED";
    private static final String TIMEOUT = "MEDIA_LIFECYCLE_BARRIER_TIMEOUT";
    private static final String INTERRUPTED =
            "MEDIA_LIFECYCLE_BARRIER_INTERRUPTED";
    private static final String RELEASE_FAILED =
            "MEDIA_LIFECYCLE_BARRIER_RELEASE_FAILED";
    private static final Duration MINIMUM_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(10);

    private final DataSource dataSource;
    private final HikariDataSource hikariDataSource;
    private final long acquireTimeoutNanos;
    private final int queryTimeoutSeconds;

    @Autowired
    public PostgresMediaLifecycleBarrier(
            DataSource dataSource,
            @Value("${portfolio.media.lifecycle-barrier.acquire-timeout:PT30S}")
            String acquireTimeout,
            @Value("${portfolio.media.lifecycle-barrier.query-timeout:PT5S}")
            String queryTimeout) {
        this(
                dataSource,
                parseTimeout(acquireTimeout, "media lifecycle acquire timeout is invalid"),
                parseTimeout(queryTimeout, "media lifecycle query timeout is invalid"));
    }

    PostgresMediaLifecycleBarrier(
            DataSource dataSource,
            Duration acquireTimeout,
            Duration queryTimeout) {
        this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
        requireTimeout(
                acquireTimeout,
                "media lifecycle acquire timeout is invalid");
        requireTimeout(
                queryTimeout,
                "media lifecycle query timeout is invalid");
        this.hikariDataSource = hikariPool(dataSource);
        validatePoolConnectionTimeout(hikariDataSource, acquireTimeout);
        this.acquireTimeoutNanos = acquireTimeout.toNanos();
        this.queryTimeoutSeconds = timeoutSeconds(queryTimeout);
    }

    @Override
    public AutoCloseable acquireExclusiveDeletionLease() {
        if (Thread.currentThread().isInterrupted()) {
            throw fixedFailure(INTERRUPTED);
        }

        long deadline = deadlineAfter(System.nanoTime(), acquireTimeoutNanos);
        Connection connection = null;
        int backendPid = -1;
        boolean cleanSessionProven = false;
        boolean lockQueryAttempted = false;
        try {
            connection = dataSource.getConnection();
            if (connection == null) {
                throw new SQLException("lifecycle barrier connection missing");
            }
            requireAcquisitionActive(deadline);
            connection.setAutoCommit(true);
            requireAcquisitionActive(deadline);
            backendPid = backendPid(connection, deadline);
            requireAcquisitionActive(deadline);
            requireCleanSession(connection, backendPid, deadline);
            cleanSessionProven = true;
            requireAcquisitionActive(deadline);

            try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_SQL)) {
                statement.setInt(1, NAMESPACE_KEY);
                statement.setInt(2, MEDIA_KEY);
                statement.setQueryTimeout(remainingTimeoutSeconds(deadline));
                lockQueryAttempted = true;
                try (ResultSet result = statement.executeQuery()) {
                    if (result == null || !result.next() || result.next()) {
                        throw new SQLException("lifecycle barrier lock result invalid");
                    }
                }
            }
            requireAcquisitionActive(deadline);

            Connection leasedConnection = connection;
            JdbcLease lease = new JdbcLease(
                    leasedConnection,
                    backendPid,
                    hikariDataSource,
                    queryTimeoutSeconds);
            connection = null;
            return lease;
        } catch (Throwable failure) {
            cleanupFailedAcquisition(
                    connection,
                    backendPid,
                    cleanSessionProven,
                    lockQueryAttempted,
                    hikariDataSource,
                    queryTimeoutSeconds);
            if (failure instanceof Error error) {
                throw error;
            }
            throw acquisitionFailure(failure);
        }
    }

    private int backendPid(Connection connection, long deadline) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(BACKEND_PID_SQL)) {
            statement.setQueryTimeout(Math.min(
                    queryTimeoutSeconds, remainingTimeoutSeconds(deadline)));
            try (ResultSet result = statement.executeQuery()) {
                if (result == null || !result.next()) {
                    throw new SQLException("lifecycle barrier backend pid missing");
                }
                int backendPid = result.getInt(1);
                if (backendPid <= 0 || result.wasNull() || result.next()) {
                    throw new SQLException("lifecycle barrier backend pid invalid");
                }
                return backendPid;
            }
        }
    }

    private void requireCleanSession(
            Connection connection, int backendPid, long deadline) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CLEAN_SESSION_SQL)) {
            statement.setInt(1, backendPid);
            statement.setInt(2, NAMESPACE_KEY);
            statement.setInt(3, MEDIA_KEY);
            statement.setQueryTimeout(Math.min(
                    queryTimeoutSeconds, remainingTimeoutSeconds(deadline)));
            try (ResultSet result = statement.executeQuery()) {
                if (result == null || !result.next()) {
                    throw new SQLException("lifecycle barrier residue result missing");
                }
                boolean cleanSession = result.getBoolean(1);
                if (!cleanSession || result.wasNull() || result.next()) {
                    throw new AcquisitionSignal(FAILED);
                }
            }
        }
    }

    private static void cleanupFailedAcquisition(
            Connection connection,
            int backendPid,
            boolean cleanSessionProven,
            boolean lockQueryAttempted,
            HikariDataSource hikariDataSource,
            int queryTimeoutSeconds) {
        if (connection == null) {
            return;
        }
        if (!cleanSessionProven) {
            abortThenClose(connection, hikariDataSource);
            return;
        }
        if (lockQueryAttempted) {
            boolean exactUnlock = unlockExact(
                    connection, backendPid, queryTimeoutSeconds);
            if (!exactUnlock) {
                abortThenClose(connection, hikariDataSource);
                return;
            }
        }
        closeKnownUnlocked(connection, hikariDataSource);
    }

    private static boolean unlockExact(
            Connection connection, int backendPid, int queryTimeoutSeconds) {
        if (backendPid <= 0) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(EXACT_UNLOCK_SQL)) {
            statement.setInt(1, NAMESPACE_KEY);
            statement.setInt(2, MEDIA_KEY);
            statement.setInt(3, backendPid);
            statement.setInt(4, NAMESPACE_KEY);
            statement.setInt(5, MEDIA_KEY);
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet result = statement.executeQuery()) {
                return result != null
                        && result.next()
                        && result.getBoolean(1)
                        && !result.wasNull()
                        && !result.next();
            }
        } catch (Throwable failure) {
            return false;
        }
    }

    private static void closeKnownUnlocked(
            Connection connection, HikariDataSource hikariDataSource) {
        try {
            connection.close();
        } catch (Throwable closeFailure) {
            abortThenClose(connection, hikariDataSource);
        }
    }

    private static void abortThenClose(
            Connection connection, HikariDataSource hikariDataSource) {
        try {
            connection.abort(Runnable::run);
        } catch (Throwable abortFailure) {
            // Explicit pool eviction below remains authoritative.
        }
        if (hikariDataSource != null) {
            try {
                hikariDataSource.evictConnection(connection);
            } catch (Throwable evictionFailure) {
                // Closing remains mandatory, but eviction is attempted before pool return.
            }
        }
        try {
            connection.close();
        } catch (Throwable closeFailure) {
            // The fixed failure hides driver and pool implementation details.
        }
    }

    private void requireAcquisitionActive(long deadline) {
        if (Thread.currentThread().isInterrupted()) {
            throw new AcquisitionSignal(INTERRUPTED);
        }
        if (remainingNanos(deadline) <= 0) {
            throw new AcquisitionSignal(TIMEOUT);
        }
    }

    private int remainingTimeoutSeconds(long deadline) {
        long remaining = remainingNanos(deadline);
        if (remaining <= 0) {
            throw new AcquisitionSignal(TIMEOUT);
        }
        long oneSecond = TimeUnit.SECONDS.toNanos(1);
        long seconds = (remaining + oneSecond - 1) / oneSecond;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, seconds));
    }

    private static long remainingNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    private static long deadlineAfter(long start, long timeoutNanos) {
        try {
            return Math.addExact(start, timeoutNanos);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static IllegalStateException acquisitionFailure(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return fixedFailure(INTERRUPTED);
        }
        if (failure instanceof AcquisitionSignal signal) {
            return fixedFailure(signal.code);
        }
        if (failure instanceof SQLTimeoutException
                || failure instanceof SQLException sqlFailure
                && "57014".equals(sqlFailure.getSQLState())) {
            return fixedFailure(TIMEOUT);
        }
        return fixedFailure(FAILED);
    }

    private static Duration parseTimeout(String value, String message) {
        try {
            Duration timeout = Duration.parse(value);
            requireTimeout(timeout, message);
            return timeout;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireTimeout(Duration timeout, String message) {
        if (timeout == null
                || timeout.compareTo(MINIMUM_TIMEOUT) < 0
                || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private static int timeoutSeconds(Duration timeout) {
        long nanoseconds = timeout.toNanos();
        long oneSecond = TimeUnit.SECONDS.toNanos(1);
        return Math.toIntExact((nanoseconds + oneSecond - 1) / oneSecond);
    }

    private static void validatePoolConnectionTimeout(
            HikariDataSource pool, Duration acquireTimeout) {
        if (pool != null
                && pool.getConnectionTimeout() > acquireTimeout.toMillis()) {
            throw new IllegalArgumentException(
                    "media lifecycle pool connection timeout is invalid");
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
        } catch (SQLException invalidWrapper) {
            throw new IllegalArgumentException(
                    "media lifecycle data source is invalid");
        }
        return null;
    }

    private static IllegalStateException fixedFailure(String code) {
        return new IllegalStateException(code);
    }

    private static final class JdbcLease implements AutoCloseable {
        private final Connection connection;
        private final int backendPid;
        private final HikariDataSource hikariDataSource;
        private final int queryTimeoutSeconds;
        private boolean active = true;

        private JdbcLease(
                Connection connection,
                int backendPid,
                HikariDataSource hikariDataSource,
                int queryTimeoutSeconds) {
            this.connection = connection;
            this.backendPid = backendPid;
            this.hikariDataSource = hikariDataSource;
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (!active) {
                    return;
                }
                active = false;
            }

            if (!unlockExact(connection, backendPid, queryTimeoutSeconds)) {
                abortThenClose(connection, hikariDataSource);
                throw fixedFailure(RELEASE_FAILED);
            }
            try {
                connection.close();
            } catch (Throwable closeFailure) {
                abortThenClose(connection, hikariDataSource);
                if (closeFailure instanceof Error error) {
                    throw error;
                }
                throw fixedFailure(RELEASE_FAILED);
            }
        }
    }

    private static final class AcquisitionSignal extends RuntimeException {
        private final String code;

        private AcquisitionSignal(String code) {
            super(null, null, false, false);
            this.code = code;
        }
    }
}
