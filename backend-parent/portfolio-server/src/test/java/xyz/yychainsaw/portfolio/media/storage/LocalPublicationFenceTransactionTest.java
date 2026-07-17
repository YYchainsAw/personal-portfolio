package xyz.yychainsaw.portfolio.media.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

class LocalPublicationFenceTransactionTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CLEANUP_JOB_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SHA256 = "a".repeat(64);
    private static final String VOLUME_ID = "c".repeat(64);

    @Test
    void acquireRejectsAnAmbientSpringTransactionBeforePermitOrConnectionUse() {
        DataSource dataSource = mock(DataSource.class);
        LocalPublicationFence fence = new LocalPublicationFence(
                dataSource,
                VOLUME_ID,
                1,
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                System::nanoTime,
                LocalPublicationFence::stableAssetLockKey);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThatThrownBy(() -> fence.acquire(publication()))
                    .isInstanceOfSatisfying(
                            StorageException.class,
                            failure -> org.assertj.core.api.Assertions.assertThat(failure.code())
                                    .isEqualTo("LOCAL_PUBLICATION_FENCE_TRANSACTION_ACTIVE"))
                    .hasMessage("LOCAL_PUBLICATION_FENCE_TRANSACTION_ACTIVE")
                    .hasNoCause();
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verifyNoInteractions(dataSource);
    }

    @Test
    void acquireRejectsActiveSynchronizationWithoutAnActualTransaction() {
        DataSource dataSource = mock(DataSource.class);
        LocalPublicationFence fence = new LocalPublicationFence(
                dataSource,
                VOLUME_ID,
                1,
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                System::nanoTime,
                LocalPublicationFence::stableAssetLockKey);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> fence.acquire(publication()))
                    .isInstanceOfSatisfying(
                            StorageException.class,
                            failure -> org.assertj.core.api.Assertions.assertThat(failure.code())
                                    .isEqualTo("LOCAL_PUBLICATION_FENCE_TRANSACTION_ACTIVE"))
                    .hasMessage("LOCAL_PUBLICATION_FENCE_TRANSACTION_ACTIVE")
                    .hasNoCause();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verifyNoInteractions(dataSource);
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
}
