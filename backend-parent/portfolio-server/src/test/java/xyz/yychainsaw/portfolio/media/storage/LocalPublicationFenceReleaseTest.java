package xyz.yychainsaw.portfolio.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

class LocalPublicationFenceReleaseTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CLEANUP_JOB_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SHA256 = "a".repeat(64);
    private static final String VOLUME_ID = "c".repeat(64);

    @Test
    void fenceExistsOnlyInTheServletApplication() {
        ConditionalOnWebApplication condition =
                LocalPublicationFence.class.getAnnotation(
                        ConditionalOnWebApplication.class);

        assertThat(condition).isNotNull();
        assertThat(condition.type()).isEqualTo(Type.SERVLET);
    }

    @Test
    void falseUnlockResultAbortsThePhysicalConnectionBeforePoolReuse() throws Exception {
        JdbcFixture jdbc = fixture(false, false);
        LocalPublicationFence fence = fence(jdbc.dataSource());
        LocalPublicationAuthorization authorization = fence.acquire(publication());

        assertThatThrownBy(authorization::close)
                .isInstanceOfSatisfying(StorageException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                "LOCAL_PUBLICATION_FENCE_RELEASE_FAILED"))
                .hasMessage("LOCAL_PUBLICATION_FENCE_RELEASE_FAILED")
                .hasNoCause();

        verify(jdbc.connection()).abort(any());
        assertThat(jdbc.physicallyClosed()).isTrue();
    }

    @Test
    void failedUnlockAndFailedAbortRetainTheLocalPermitFailClosed() throws Exception {
        JdbcFixture jdbc = fixture(false, true);
        LocalPublicationFence fence = fence(jdbc.dataSource());
        LocalPublicationAuthorization authorization = fence.acquire(publication());

        assertThatThrownBy(authorization::close)
                .isInstanceOf(StorageException.class)
                .hasMessage("LOCAL_PUBLICATION_FENCE_RELEASE_FAILED");
        assertThatThrownBy(() -> fence.acquire(publication()))
                .isInstanceOfSatisfying(StorageException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                "LOCAL_PUBLICATION_FENCE_TIMEOUT"))
                .hasNoCause();
        verify(jdbc.dataSource(), times(1)).getConnection();
    }

    private static JdbcFixture fixture(boolean unlockResult, boolean abortFails)
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement tryLock = mock(PreparedStatement.class);
        PreparedStatement backendPid = mock(PreparedStatement.class);
        PreparedStatement authentication = mock(PreparedStatement.class);
        PreparedStatement unlock = mock(PreparedStatement.class);
        ResultSet tryResult = result(true);
        ResultSet backendPidResult = mock(ResultSet.class);
        ResultSet authenticationResult = result(true);
        ResultSet unlockResultSet = result(unlockResult);
        AtomicBoolean closed = new AtomicBoolean();

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("select pg_try_advisory_lock(?, ?)"))
                .thenReturn(tryLock);
        when(connection.prepareStatement("select pg_backend_pid()"))
                .thenReturn(backendPid);
        when(connection.prepareStatement(contains("local_staging_reservation")))
                .thenReturn(authentication);
        when(connection.prepareStatement(contains("pg_advisory_unlock")))
                .thenReturn(unlock);
        when(tryLock.executeQuery()).thenReturn(tryResult);
        when(backendPid.executeQuery()).thenReturn(backendPidResult);
        when(backendPidResult.next()).thenReturn(true, false);
        when(backendPidResult.getInt(1)).thenReturn(101);
        when(authentication.executeQuery()).thenReturn(authenticationResult);
        when(unlock.executeQuery()).thenReturn(unlockResultSet);
        when(connection.isClosed()).thenAnswer(ignored -> closed.get());
        doAnswer(invocation -> {
                    if (abortFails) {
                        throw new java.sql.SQLException("synthetic abort failure");
                    }
                    closed.set(true);
                    return null;
                })
                .when(connection)
                .abort(any());
        return new JdbcFixture(dataSource, connection, closed);
    }

    private static ResultSet result(boolean value) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getBoolean(1)).thenReturn(value);
        return result;
    }

    private static LocalPublicationFence fence(DataSource dataSource) {
        return new LocalPublicationFence(
                dataSource,
                VOLUME_ID,
                1,
                Duration.ofMillis(20),
                Duration.ofSeconds(1),
                System::nanoTime,
                ignored -> 7);
    }

    private static LocalStagingPublication publication() {
        return new LocalStagingPublication(
                ASSET_ID,
                "staging/" + ASSET_ID + '/' + SHA256 + ".jpg",
                SHA256,
                "image/jpeg",
                new StorageLocation(StorageProvider.LOCAL, null, null),
                0,
                CLEANUP_JOB_ID);
    }

    private record JdbcFixture(
            DataSource dataSource,
            Connection connection,
            AtomicBoolean physicallyClosedState) {
        boolean physicallyClosed() {
            return physicallyClosedState.get();
        }
    }
}
