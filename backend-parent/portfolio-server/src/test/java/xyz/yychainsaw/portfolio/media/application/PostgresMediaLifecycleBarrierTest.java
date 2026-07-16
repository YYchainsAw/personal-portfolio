package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PostgresMediaLifecycleBarrierTest {

    @Test
    void barrierIsAProductionComponent() {
        assertThat(PostgresMediaLifecycleBarrier.class)
                .hasAnnotation(Component.class);
    }

    @Test
    void dedicatedSessionLockIgnoresAmbientTransactionAndClosesIdempotently()
            throws Exception {
        JdbcFixture jdbc = successfulFixture(true);
        PostgresMediaLifecycleBarrier barrier = barrier(jdbc.dataSource());

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            AutoCloseable lease = barrier.acquireExclusiveDeletionLease();

            verify(jdbc.connection()).setAutoCommit(true);
            verify(jdbc.backendPid()).setQueryTimeout(1);
            verify(jdbc.residueCheck()).setInt(1, 101);
            verify(jdbc.residueCheck()).setInt(
                    2, MediaLifecycleBarrier.NAMESPACE_KEY);
            verify(jdbc.residueCheck()).setInt(3, MediaLifecycleBarrier.MEDIA_KEY);
            verify(jdbc.residueCheck()).setQueryTimeout(1);
            verify(jdbc.lock()).setInt(1, MediaLifecycleBarrier.NAMESPACE_KEY);
            verify(jdbc.lock()).setInt(2, MediaLifecycleBarrier.MEDIA_KEY);
            verify(jdbc.lock()).setQueryTimeout(2);

            lease.close();
            lease.close();

            verify(jdbc.unlock()).setInt(1, MediaLifecycleBarrier.NAMESPACE_KEY);
            verify(jdbc.unlock()).setInt(2, MediaLifecycleBarrier.MEDIA_KEY);
            verify(jdbc.unlock()).setInt(3, 101);
            verify(jdbc.unlock()).setQueryTimeout(1);
            verify(jdbc.unlock(), times(1)).executeQuery();
            verify(jdbc.connection(), times(1)).close();
            verify(jdbc.connection(), never()).abort(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void interruptionBeforeConnectionAcquisitionIsPreserved() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        PostgresMediaLifecycleBarrier barrier = barrier(dataSource);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(barrier::acquireExclusiveDeletionLease)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("MEDIA_LIFECYCLE_BARRIER_INTERRUPTED")
                    .hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(dataSource, never()).getConnection();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionDuringLockAcquisitionAbortsAndClosesTheUncertainSession()
            throws Exception {
        JdbcFixture jdbc = successfulFixture(false);
        when(jdbc.lock().executeQuery()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new SQLException("synthetic interrupted query");
        });
        PostgresMediaLifecycleBarrier barrier = barrier(jdbc.dataSource());

        try {
            assertThatThrownBy(barrier::acquireExclusiveDeletionLease)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("MEDIA_LIFECYCLE_BARRIER_INTERRUPTED")
                    .hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();

            InOrder cleanup = inOrder(jdbc.connection());
            cleanup.verify(jdbc.connection()).abort(any());
            cleanup.verify(jdbc.connection()).close();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void lockQueryTimeoutIsBoundedAndClosesTheUncertainSession() throws Exception {
        JdbcFixture jdbc = successfulFixture(false);
        when(jdbc.lock().executeQuery())
                .thenThrow(new SQLTimeoutException("synthetic timeout", "57014"));
        PostgresMediaLifecycleBarrier barrier = barrier(jdbc.dataSource());

        assertThatThrownBy(barrier::acquireExclusiveDeletionLease)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_LIFECYCLE_BARRIER_TIMEOUT")
                .hasNoCause();

        verify(jdbc.lock()).setQueryTimeout(2);
        InOrder cleanup = inOrder(jdbc.connection());
        cleanup.verify(jdbc.connection()).abort(any());
        cleanup.verify(jdbc.connection()).close();
    }

    @Test
    void falseOrInexactUnlockAbortsBeforeTheConnectionCanReturnToThePool()
            throws Exception {
        JdbcFixture jdbc = successfulFixture(false);
        AutoCloseable lease = barrier(jdbc.dataSource())
                .acquireExclusiveDeletionLease();

        assertThatThrownBy(lease::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_LIFECYCLE_BARRIER_RELEASE_FAILED")
                .hasNoCause();

        InOrder cleanup = inOrder(jdbc.unlock(), jdbc.connection());
        cleanup.verify(jdbc.unlock()).executeQuery();
        cleanup.verify(jdbc.connection()).abort(any());
        cleanup.verify(jdbc.connection()).close();

        lease.close();
        verify(jdbc.unlock(), times(1)).executeQuery();
        verify(jdbc.connection(), times(1)).abort(any());
        verify(jdbc.connection(), times(1)).close();
    }

    @Test
    void residualSessionLockAbortsBeforeTheExclusiveLockQuery() throws Exception {
        JdbcFixture jdbc = successfulFixture(true);
        ResultSet contaminatedSession = booleanResult(false);
        when(jdbc.residueCheck().executeQuery()).thenReturn(contaminatedSession);

        assertThatThrownBy(() -> barrier(jdbc.dataSource())
                        .acquireExclusiveDeletionLease())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_LIFECYCLE_BARRIER_FAILED")
                .hasNoCause();

        verify(jdbc.residueCheck()).executeQuery();
        verifyNoInteractions(jdbc.lock(), jdbc.unlock());
        InOrder cleanup = inOrder(jdbc.connection());
        cleanup.verify(jdbc.connection()).abort(any());
        cleanup.verify(jdbc.connection()).close();
    }

    @Test
    void abortFailureExplicitlyEvictsDirectHikariConnectionBeforeClose()
            throws Exception {
        JdbcFixture jdbc = successfulFixture(true);
        HikariDataSource pool = mock(HikariDataSource.class);
        when(pool.getConnectionTimeout()).thenReturn(1_000L);
        when(pool.getConnection()).thenReturn(jdbc.connection());
        ResultSet contaminatedSession = booleanResult(false);
        when(jdbc.residueCheck().executeQuery()).thenReturn(contaminatedSession);
        doThrow(new SQLException("synthetic abort failure"))
                .when(jdbc.connection())
                .abort(any());

        assertThatThrownBy(() -> barrier(pool).acquireExclusiveDeletionLease())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_LIFECYCLE_BARRIER_FAILED")
                .hasNoCause();

        InOrder cleanup = inOrder(pool, jdbc.connection());
        cleanup.verify(jdbc.connection()).abort(any());
        cleanup.verify(pool).evictConnection(jdbc.connection());
        cleanup.verify(jdbc.connection()).close();
        verifyNoInteractions(jdbc.lock(), jdbc.unlock());
    }

    @Test
    void abortFailureExplicitlyEvictsUnwrappedHikariConnectionBeforeClose()
            throws Exception {
        JdbcFixture jdbc = successfulFixture(true);
        DataSource wrapper = mock(DataSource.class);
        HikariDataSource pool = mock(HikariDataSource.class);
        when(wrapper.isWrapperFor(HikariDataSource.class)).thenReturn(true);
        when(wrapper.unwrap(HikariDataSource.class)).thenReturn(pool);
        when(wrapper.getConnection()).thenReturn(jdbc.connection());
        when(pool.getConnectionTimeout()).thenReturn(1_000L);
        ResultSet contaminatedSession = booleanResult(false);
        when(jdbc.residueCheck().executeQuery()).thenReturn(contaminatedSession);
        doThrow(new SQLException("synthetic abort failure"))
                .when(jdbc.connection())
                .abort(any());

        assertThatThrownBy(() -> barrier(wrapper).acquireExclusiveDeletionLease())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_LIFECYCLE_BARRIER_FAILED")
                .hasNoCause();

        InOrder cleanup = inOrder(pool, jdbc.connection());
        cleanup.verify(jdbc.connection()).abort(any());
        cleanup.verify(pool).evictConnection(jdbc.connection());
        cleanup.verify(jdbc.connection()).close();
        verifyNoInteractions(jdbc.lock(), jdbc.unlock());
    }

    @Test
    void failureBeforeResidueProofAbortsTheDedicatedConnection() throws Exception {
        JdbcFixture jdbc = successfulFixture(true);
        when(jdbc.connection().prepareStatement("select pg_catalog.pg_backend_pid()"))
                .thenThrow(new SQLException("synthetic pid failure"));

        assertThatThrownBy(() -> barrier(jdbc.dataSource())
                        .acquireExclusiveDeletionLease())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_LIFECYCLE_BARRIER_FAILED")
                .hasNoCause();

        InOrder cleanup = inOrder(jdbc.connection());
        cleanup.verify(jdbc.connection()).abort(any());
        cleanup.verify(jdbc.connection()).close();
        verifyNoInteractions(jdbc.residueCheck(), jdbc.lock(), jdbc.unlock());
    }

    @Test
    void timeoutsAreStrictlyBounded() {
        DataSource dataSource = mock(DataSource.class);

        assertThatThrownBy(() -> new PostgresMediaLifecycleBarrier(
                        dataSource, Duration.ofMillis(999), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("media lifecycle acquire timeout is invalid");
        assertThatThrownBy(() -> new PostgresMediaLifecycleBarrier(
                        dataSource, Duration.ofSeconds(1), Duration.ofMinutes(11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("media lifecycle query timeout is invalid");
    }

    private static PostgresMediaLifecycleBarrier barrier(DataSource dataSource) {
        return new PostgresMediaLifecycleBarrier(
                dataSource, Duration.ofMillis(1_500), Duration.ofSeconds(1));
    }

    private static JdbcFixture successfulFixture(boolean unlockResult) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement backendPid = mock(PreparedStatement.class);
        PreparedStatement residueCheck = mock(PreparedStatement.class);
        PreparedStatement lock = mock(PreparedStatement.class);
        PreparedStatement unlock = mock(PreparedStatement.class);
        ResultSet backendPidResult = mock(ResultSet.class);
        ResultSet residueResult = booleanResult(true);
        ResultSet lockResult = mock(ResultSet.class);
        ResultSet unlockResultSet = booleanResult(unlockResult);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select pg_catalog.pg_backend_pid()"))
                .thenReturn(backendPid);
        when(connection.prepareStatement(argThat(sql ->
                sql.contains("pg_catalog.pg_locks held")
                        && !sql.contains("pg_catalog.pg_advisory_unlock"))))
                .thenReturn(residueCheck);
        when(connection.prepareStatement("select pg_catalog.pg_advisory_lock(?, ?)"))
                .thenReturn(lock);
        when(connection.prepareStatement(contains("pg_catalog.pg_advisory_unlock")))
                .thenReturn(unlock);
        when(backendPid.executeQuery()).thenReturn(backendPidResult);
        when(backendPidResult.next()).thenReturn(true, false);
        when(backendPidResult.getInt(1)).thenReturn(101);
        when(residueCheck.executeQuery()).thenReturn(residueResult);
        when(lock.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true, false);
        when(unlock.executeQuery()).thenReturn(unlockResultSet);
        return new JdbcFixture(
                dataSource, connection, backendPid, residueCheck, lock, unlock);
    }

    private static ResultSet booleanResult(boolean value) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getBoolean(1)).thenReturn(value);
        return result;
    }

    private record JdbcFixture(
            DataSource dataSource,
            Connection connection,
            PreparedStatement backendPid,
            PreparedStatement residueCheck,
            PreparedStatement lock,
            PreparedStatement unlock) {}
}
