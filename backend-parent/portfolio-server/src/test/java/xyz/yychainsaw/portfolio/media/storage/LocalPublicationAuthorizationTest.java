package xyz.yychainsaw.portfolio.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

class LocalPublicationAuthorizationTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CLEANUP_JOB_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SHA256 = "a".repeat(64);
    private static final String VOLUME_ID = "c".repeat(64);
    private static final String OTHER_VOLUME_ID = "d".repeat(64);
    private static final String KEY =
            "staging/11111111-1111-4111-8111-111111111111/" + SHA256 + ".jpg";
    private static final StorageLocation LOCAL =
            new StorageLocation(StorageProvider.LOCAL, null, null);

    @Test
    void authorizationHasNoPublicConstructorAndRedactsEveryBoundValue() {
        AtomicLong ticker = new AtomicLong();
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization authorization = authorization(
                publication, ticker, new RecordingLease());

        assertThat(LocalPublicationAuthorization.class.getDeclaredConstructors())
                .allMatch(constructor -> !Modifier.isPublic(constructor.getModifiers()));
        assertThat(authorization.toString()).isEqualTo(
                "LocalPublicationAuthorization[REDACTED]");
        assertThat(authorization.toString())
                .doesNotContain(ASSET_ID.toString(), CLEANUP_JOB_ID.toString(), SHA256, KEY,
                        VOLUME_ID, "image/jpeg", "LOCAL");
    }

    @Test
    void exactIdentityOnTheOwningThreadAndLiveFenceIsAccepted() {
        AtomicLong ticker = new AtomicLong(10);
        RecordingLease lease = new RecordingLease();
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization authorization = authorization(publication, ticker, lease);

        assertThatCode(() -> authorization.require(publication, VOLUME_ID))
                .doesNotThrowAnyException();
        assertThat(lease.heldChecks()).isOne();
    }

    @Test
    void streamCheckpointIsCheapButPublicReauthenticationUsesTheStrictFenceCheck() {
        AtomicLong ticker = new AtomicLong(10);
        RecordingLease lease = new RecordingLease();
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization authorization = authorization(publication, ticker, lease);

        authorization.require(publication, VOLUME_ID);
        assertThat(lease.strictChecks()).isZero();

        authorization.reauthenticate();
        assertThat(lease.strictChecks()).isOne();
    }

    @Test
    void publicationClaimIsOneShotEvenWhileTheFenceRemainsHeld() {
        AtomicLong ticker = new AtomicLong(10);
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization authorization = authorization(
                publication, ticker, new RecordingLease());

        assertThatCode(() -> authorization.claim(publication, VOLUME_ID))
                .doesNotThrowAnyException();
        assertFixedFailure(
                () -> authorization.claim(publication, VOLUME_ID),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");
        assertThatCode(() -> authorization.require(publication, VOLUME_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void everyIdentityMismatchIsRejectedWithOneCauseFreeSurface() {
        AtomicLong ticker = new AtomicLong(10);
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization authorization = authorization(
                publication, ticker, new RecordingLease());
        StorageLocation cos = new StorageLocation(
                StorageProvider.TENCENT_COS, "bucket", "ap-hongkong");

        List<LocalStagingPublication> mismatches = List.of(
                new LocalStagingPublication(
                        UUID.fromString("33333333-3333-4333-8333-333333333333"),
                        KEY, SHA256, "image/jpeg", LOCAL, 0, CLEANUP_JOB_ID),
                new LocalStagingPublication(
                        ASSET_ID, KEY + "x", SHA256, "image/jpeg", LOCAL, 0, CLEANUP_JOB_ID),
                new LocalStagingPublication(
                        ASSET_ID, KEY, "b".repeat(64), "image/jpeg", LOCAL, 0, CLEANUP_JOB_ID),
                new LocalStagingPublication(
                        ASSET_ID, KEY, SHA256, "image/png", LOCAL, 0, CLEANUP_JOB_ID),
                new LocalStagingPublication(
                        ASSET_ID, KEY, SHA256, "image/jpeg", cos, 0, CLEANUP_JOB_ID),
                new LocalStagingPublication(
                        ASSET_ID, KEY, SHA256, "image/jpeg", LOCAL, 1, CLEANUP_JOB_ID),
                new LocalStagingPublication(
                        ASSET_ID,
                        KEY,
                        SHA256,
                        "image/jpeg",
                        LOCAL,
                        0,
                        UUID.fromString("44444444-4444-4444-8444-444444444444")));

        for (LocalStagingPublication mismatch : mismatches) {
            assertFixedFailure(
                    () -> authorization.require(mismatch, VOLUME_ID),
                    "LOCAL_STAGING_AUTHORIZATION_INVALID");
        }
    }

    @Test
    void aDifferentOrNonCanonicalVolumeIsRejectedWithoutLeakingTheBoundValue() {
        AtomicLong ticker = new AtomicLong(10);
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization authorization = authorization(
                publication, ticker, new RecordingLease());

        assertFixedFailure(
                () -> authorization.require(publication, OTHER_VOLUME_ID),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");
        assertFixedFailure(
                () -> authorization.reauthenticateVolume(OTHER_VOLUME_ID),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");
        assertFixedFailure(
                () -> authorization.reauthenticateVolume(OTHER_VOLUME_ID.toUpperCase()),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");
        assertThat(authorization.toString()).doesNotContain(VOLUME_ID, OTHER_VOLUME_ID);
    }

    @Test
    void authorizationIsBoundToTheCreatingThread() throws Exception {
        AtomicLong ticker = new AtomicLong(10);
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization authorization = authorization(
                publication, ticker, new RecordingLease());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            StorageException failure = executor.submit(() -> {
                        try {
                            authorization.require(publication, VOLUME_ID);
                            return null;
                        } catch (StorageException exception) {
                            return exception;
                        }
                    })
                    .get(10, TimeUnit.SECONDS);

            assertThat(failure).isNotNull();
            assertThat(failure.code()).isEqualTo("LOCAL_STAGING_AUTHORIZATION_INVALID");
            assertThat(failure).hasNoCause();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void closeImmediatelyInvalidatesAndReleasesExactlyOnce() {
        AtomicLong ticker = new AtomicLong(10);
        RecordingLease lease = new RecordingLease();
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization authorization = authorization(publication, ticker, lease);

        authorization.close();
        authorization.close();

        assertThat(lease.releases()).isOne();
        assertFixedFailure(
                () -> authorization.require(publication, VOLUME_ID),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");
    }

    @Test
    void releasedFenceAndExpiredDeadlineAreBothRejected() {
        AtomicLong ticker = new AtomicLong(10);
        RecordingLease released = new RecordingLease();
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization withoutFence = authorization(publication, ticker, released);
        released.forceReleased();

        assertFixedFailure(
                () -> withoutFence.require(publication, VOLUME_ID),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");

        RecordingLease held = new RecordingLease();
        LocalPublicationAuthorization expired = authorization(publication, ticker, held);
        ticker.set(101);
        assertFixedFailure(
                () -> expired.require(publication, VOLUME_ID),
                "LOCAL_PUBLICATION_DEADLINE_EXCEEDED");
    }

    @Test
    void liveReservationReauthenticationIsCauseFreeAndFailClosed() {
        AtomicLong ticker = new AtomicLong(10);
        AtomicBoolean current = new AtomicBoolean(true);
        LocalStagingPublication publication = publication();
        RecordingLease lease = new RecordingLease();
        lease.authentication(() -> {
            if (!current.get()) {
                throw new IllegalStateException("database detail must not escape");
            }
        });
        LocalPublicationAuthorization authorization = new LocalPublicationAuthorization(
                publication,
                VOLUME_ID,
                100,
                ticker::get,
                lease);

        assertThatCode(() -> authorization.reauthenticate(publication, VOLUME_ID))
                .doesNotThrowAnyException();
        current.set(false);
        assertFixedFailure(
                () -> authorization.reauthenticate(publication, VOLUME_ID),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");
    }

    private static LocalStagingPublication publication() {
        return new LocalStagingPublication(
                ASSET_ID, KEY, SHA256, "image/jpeg", LOCAL, 0, CLEANUP_JOB_ID);
    }

    private static LocalPublicationAuthorization authorization(
            LocalStagingPublication publication,
            AtomicLong ticker,
            RecordingLease lease) {
        return new LocalPublicationAuthorization(
                publication, VOLUME_ID, 100, ticker::get, lease);
    }

    private static void assertFixedFailure(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(StorageException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code))
                .hasMessage(code)
                .hasNoCause();
    }

    private static final class RecordingLease
            implements LocalPublicationAuthorization.FenceLease {
        private final AtomicBoolean held = new AtomicBoolean(true);
        private final AtomicInteger heldChecks = new AtomicInteger();
        private final AtomicInteger strictChecks = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();
        private Runnable authentication = () -> {};

        @Override
        public boolean isHeld() {
            heldChecks.incrementAndGet();
            return held.get();
        }

        @Override
        public void reauthenticate() {
            strictChecks.incrementAndGet();
            if (!held.get()) {
                throw new StorageException("LOCAL_STAGING_AUTHORIZATION_INVALID");
            }
            authentication.run();
        }

        @Override
        public void close() {
            if (held.compareAndSet(true, false)) {
                releases.incrementAndGet();
            }
        }

        void forceReleased() {
            held.set(false);
        }

        int heldChecks() {
            return heldChecks.get();
        }

        int releases() {
            return releases.get();
        }

        int strictChecks() {
            return strictChecks.get();
        }

        void authentication(Runnable authentication) {
            this.authentication = authentication;
        }
    }
}
