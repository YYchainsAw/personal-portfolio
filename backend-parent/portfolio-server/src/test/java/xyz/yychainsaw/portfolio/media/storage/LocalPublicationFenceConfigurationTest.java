package xyz.yychainsaw.portfolio.media.storage;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

class LocalPublicationFenceConfigurationTest {
    private static final String VOLUME_ID = "c".repeat(64);

    @Test
    void exactRequiredPoolCapacityAndCompatibleConnectionTimeoutAreAccepted() {
        try (HikariDataSource dataSource = pool(6, 10_000)) {
            assertThatCode(() -> fence(dataSource, "PT10S"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void poolMustHoldTwoConnectionsPerPublicationPlusConfiguredHeadroom() {
        try (HikariDataSource dataSource = pool(5, 10_000)) {
            assertThatThrownBy(() -> fence(dataSource, "PT10S"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("local publication pool capacity is invalid")
                    .hasNoCause();
        }
    }

    @Test
    void poolConnectionTimeoutCannotOutliveTheFenceAcquisitionDeadline() {
        try (HikariDataSource dataSource = pool(6, 10_000)) {
            assertThatThrownBy(() -> fence(dataSource, "PT9S"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("local publication pool connection timeout is invalid")
                    .hasNoCause();
        }
    }

    private static HikariDataSource pool(int maximumPoolSize, long connectionTimeout) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setConnectionTimeout(connectionTimeout);
        return dataSource;
    }

    private static LocalPublicationFence fence(
            HikariDataSource dataSource, String acquireTimeout) {
        LocalStorageService storage = mock(LocalStorageService.class);
        when(storage.volumeId()).thenReturn(VOLUME_ID);
        return new LocalPublicationFence(
                dataSource, storage, "2", "2", acquireTimeout, "PT2M");
    }
}
