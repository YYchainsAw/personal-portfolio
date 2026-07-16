package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

class LocalStorageReservedPublicationTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CLEANUP_JOB_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SHA256 = "a".repeat(64);
    private static final String KEY =
            "staging/11111111-1111-4111-8111-111111111111/" + SHA256 + ".jpg";
    private static final StorageLocation LOCAL =
            new StorageLocation(StorageProvider.LOCAL, null, null);

    @TempDir Path temporaryDirectory;

    private final List<LocalStorageService> services = new ArrayList<>();
    private final List<Path> externalCleanup = new ArrayList<>();
    private Path storageBoundary;

    @AfterEach
    void closeServicesAndRemoveExternalTestDirectories() throws Exception {
        for (LocalStorageService service : services) {
            service.close();
        }
        for (Path path : externalCleanup) {
            deleteTree(path);
        }
    }

    @Test
    void plainPutRejectsAStagingTargetBeforeFilesystemMutationAndClosesInput() {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        CloseTrackingInput input = new CloseTrackingInput(new byte[] {1});
        long before = entryCount(root);

        assertFixedFailure(
                () -> service.put(KEY, input, 1, "image/jpeg"),
                "LOCAL_STAGING_AUTHORIZATION_REQUIRED");

        assertThat(input.closeCount()).isOne();
        assertThat(entryCount(root)).isEqualTo(before);
        assertThat(root.resolve("staging")).doesNotExist();
    }

    @Test
    void plainCopyRejectsAStagingTargetBeforeOpeningTheSourceOrCreatingParents() {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        long before = entryCount(root);

        assertFixedFailure(
                () -> service.copy("missing.jpg", KEY),
                "LOCAL_STAGING_AUTHORIZATION_REQUIRED");

        assertThat(entryCount(root)).isEqualTo(before);
        assertThat(root.resolve("staging")).doesNotExist();
    }

    @Test
    void plainPutRejectsAsciiCaseVariantsOfStagingBeforeFilesystemMutation() {
        for (String prefix : List.of("STAGING", "StAgInG")) {
            Path root = storageRoot().resolve("put-" + prefix);
            LocalStorageService service = service(root);
            CloseTrackingInput input = new CloseTrackingInput(new byte[] {1});
            String key = prefix + KEY.substring("staging".length());
            long before = entryCount(root);

            assertFixedFailure(
                    () -> service.put(key, input, 1, "image/jpeg"),
                    "LOCAL_STAGING_AUTHORIZATION_REQUIRED");

            assertThat(input.closeCount()).isOne();
            assertThat(entryCount(root)).isEqualTo(before);
            assertThat(root.resolve(prefix)).doesNotExist();
        }
    }

    @Test
    void plainCopyRejectsAsciiCaseVariantsOfStagingBeforeOpeningTheSource() {
        for (String prefix : List.of("STAGING", "StAgInG")) {
            Path root = storageRoot().resolve("copy-" + prefix);
            LocalStorageService service = service(root);
            String key = prefix + KEY.substring("staging".length());
            long before = entryCount(root);

            assertFixedFailure(
                    () -> service.copy("missing.jpg", key),
                    "LOCAL_STAGING_AUTHORIZATION_REQUIRED");

            assertThat(entryCount(root)).isEqualTo(before);
            assertThat(root.resolve(prefix)).doesNotExist();
        }
    }

    @Test
    void exactLiveAuthorizationPublishesCreateOnlyAndClosesTheInput() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        MutableLease lease = new MutableLease();
        AtomicLong ticker = new AtomicLong(10);
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization authorization = authorization(
                service, publication, ticker, lease);
        CloseTrackingInput input = new CloseTrackingInput(new byte[] {1, 2, 3});

        StoredObject stored = service.putReservedStaging(
                authorization, publication, input, 3);

        assertThat(stored.objectKey()).isEqualTo(KEY);
        assertThat(stored.provider()).isEqualTo(StorageProvider.LOCAL);
        assertThat(Files.readAllBytes(root.resolve(KEY))).containsExactly(1, 2, 3);
        assertThat(input.closeCount()).isOne();
        assertThat(lease.isHeld()).isTrue();

        long entriesAfterFirstAttempt = entryCount(root);
        CloseTrackingInput replay = new CloseTrackingInput(new byte[] {1, 2, 3});
        assertFixedFailure(
                () -> service.putReservedStaging(
                        authorization, publication, replay, 3),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");
        assertThat(replay.closeCount()).isOne();
        assertThat(entryCount(root)).isEqualTo(entriesAfterFirstAttempt);
    }

    @Test
    void authorizationFromAnotherStorageRootFailsBeforeFilesystemMutation() {
        Path firstRoot = storageRoot().resolve("first-volume");
        Path secondRoot = storageRoot().resolve("second-volume");
        LocalStorageService first = service(firstRoot);
        LocalStorageService second = service(secondRoot);
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization authorization = authorization(
                first, publication, new AtomicLong(10), new MutableLease());
        CloseTrackingInput input = new CloseTrackingInput(new byte[] {1});
        long before = entryCount(secondRoot);

        assertThat(first.volumeId()).isNotEqualTo(second.volumeId());
        assertFixedFailure(
                () -> second.putReservedStaging(
                        authorization, publication, input, 1),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");

        assertThat(input.closeCount()).isOne();
        assertThat(entryCount(secondRoot)).isEqualTo(before);
        assertThat(secondRoot.resolve(KEY)).doesNotExist();
    }

    @Test
    void changedRootMarkerFailsClosedBeforeReservedPublicationMutation()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization authorization = authorization(
                service, publication, new AtomicLong(10), new MutableLease());
        Files.write(
                root.resolve(".portfolio-storage-root@guard"),
                new byte[32]);
        CloseTrackingInput input = new CloseTrackingInput(new byte[] {1});

        assertFixedFailure(
                () -> service.putReservedStaging(
                        authorization, publication, input, 1),
                "LOCAL_UNSAFE_PATH");

        assertThat(input.closeCount()).isOne();
        assertThat(root.resolve(KEY)).doesNotExist();
        assertThat(allPublicationFiles(root)).isEmpty();
    }

    @Test
    void repeatedAcquiredTokensAfterCrashStayWithinOneDeterministicPartAndIdentityPair()
            throws Exception {
        Path root = storageRoot();
        AtomicInteger crashWindows = new AtomicInteger();
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public Object publicationFileKey(
                            Path path,
                            java.nio.file.attribute.BasicFileAttributes attributes) {
                        return null;
                    }

                    @Override
                    public void temporaryReady(Path temporary, Path target)
                            throws IOException {
                        crashWindows.incrementAndGet();
                        Files.delete(temporary);
                        Files.write(temporary, new byte[] {9});
                        throw new IOException("synthetic crash-window replacement");
                    }
                });
        services.add(service);
        LocalStagingPublication publication = publication();
        AtomicLong ticker = new AtomicLong(10);

        for (int attempt = 0; attempt < 4; attempt++) {
            try (LocalPublicationAuthorization authorization = authorization(
                    service, publication, ticker, new MutableLease())) {
                assertThatThrownBy(() -> service.putReservedStaging(
                                authorization,
                                publication,
                                new CloseTrackingInput(new byte[] {1}),
                                1))
                        .isInstanceOfSatisfying(
                                StorageException.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo("LOCAL_WRITE_FAILED"));
            }
        }

        Path assetDirectory = root.resolve("staging").resolve(ASSET_ID.toString());
        List<String> names;
        try (var entries = Files.list(assetDirectory)) {
            names = entries.map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
        assertThat(crashWindows).hasValue(1);
        assertThat(names).containsExactly(
                "@identity-" + CLEANUP_JOB_ID,
                "@part-" + CLEANUP_JOB_ID);
        assertThat(root.resolve(KEY)).doesNotExist();
    }

    @Test
    void everyWrongTokenBindingIsRejectedWithZeroFilesystemMutation() {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        AtomicLong ticker = new AtomicLong(10);
        LocalStagingPublication exact = publication();
        LocalPublicationAuthorization authorization = authorization(
                service, exact, ticker, new MutableLease());
        StorageLocation cos = new StorageLocation(
                StorageProvider.TENCENT_COS, "bucket", "ap-hongkong");
        List<LocalStagingPublication> wrong = List.of(
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
                        ASSET_ID, KEY, SHA256, "image/jpeg", LOCAL, 1, CLEANUP_JOB_ID));
        long before = entryCount(root);

        for (LocalStagingPublication mismatch : wrong) {
            CloseTrackingInput input = new CloseTrackingInput(new byte[] {1});
            assertFixedFailure(
                    () -> service.putReservedStaging(
                            authorization, mismatch, input, 1),
                    "LOCAL_STAGING_AUTHORIZATION_INVALID");
            assertThat(input.closeCount()).isOne();
            assertThat(entryCount(root)).isEqualTo(before);
        }
    }

    @Test
    void wrongThreadClosedAuthorizationAndReleasedFenceAllMakeZeroFilesystemCalls()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        AtomicLong ticker = new AtomicLong(10);
        LocalStagingPublication publication = publication();
        long before = entryCount(root);
        LocalPublicationAuthorization threadBound = authorization(
                service, publication, ticker, new MutableLease());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            StorageException wrongThread = executor.submit(() -> {
                        try {
                            service.putReservedStaging(
                                    threadBound,
                                    publication,
                                    new CloseTrackingInput(new byte[] {1}),
                                    1);
                            return null;
                        } catch (StorageException exception) {
                            return exception;
                        }
                    })
                    .get(10, TimeUnit.SECONDS);
            assertThat(wrongThread).isNotNull();
            assertThat(wrongThread.code()).isEqualTo(
                    "LOCAL_STAGING_AUTHORIZATION_INVALID");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        LocalPublicationAuthorization closed = authorization(
                service, publication, ticker, new MutableLease());
        closed.close();
        assertFixedFailure(
                () -> service.putReservedStaging(
                        closed, publication, new CloseTrackingInput(new byte[] {1}), 1),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");

        MutableLease releasedLease = new MutableLease();
        LocalPublicationAuthorization released = authorization(
                service, publication, ticker, releasedLease);
        releasedLease.releaseExternally();
        assertFixedFailure(
                () -> service.putReservedStaging(
                        released, publication, new CloseTrackingInput(new byte[] {1}), 1),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");

        assertThat(entryCount(root)).isEqualTo(before);
    }

    @Test
    void deadlineIsCheckedAfterInputReadsAndImmediatelyBeforeCanonicalLink()
            throws Exception {
        Path root = storageRoot();
        AtomicLong ticker = new AtomicLong(10);
        LocalStorageService afterRead = service(root);
        LocalStagingPublication publication = publication();
        LocalPublicationAuthorization readDeadline = authorization(
                afterRead, publication, ticker, new MutableLease());
        InputStream advancingInput = new ByteArrayInputStream(new byte[] {1}) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, length);
                ticker.set(101);
                return count;
            }
        };

        assertFixedFailure(
                () -> afterRead.putReservedStaging(
                        readDeadline, publication, advancingInput, 1),
                "LOCAL_PUBLICATION_DEADLINE_EXCEEDED");
        assertThat(root.resolve(KEY)).doesNotExist();
        assertThat(allPublicationFiles(root)).isEmpty();

        Path secondRoot = storageRoot().resolve("second");
        ticker.set(10);
        LocalStorageService beforeLink = new LocalStorageService(
                new LocalStorageProperties(secondRoot),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public void temporaryReady(Path temporary, Path target) {
                        ticker.set(101);
                    }
                });
        services.add(beforeLink);
        LocalPublicationAuthorization linkDeadline = authorization(
                beforeLink, publication, ticker, new MutableLease());

        assertFixedFailure(
                () -> beforeLink.putReservedStaging(
                        linkDeadline,
                        publication,
                        new CloseTrackingInput(new byte[] {1}),
                        1),
                "LOCAL_PUBLICATION_DEADLINE_EXCEEDED");
        assertThat(secondRoot.resolve(KEY)).doesNotExist();
        assertThat(allPublicationFiles(secondRoot)).isEmpty();
    }

    @Test
    void reservationAdvancedWhileWritingAPartAbortsBeforeTheCanonicalLink()
            throws Exception {
        Path root = storageRoot();
        AtomicLong ticker = new AtomicLong(10);
        AtomicBoolean reservationCurrent = new AtomicBoolean(true);
        AtomicInteger reauthentications = new AtomicInteger();
        LocalStagingPublication publication = publication();
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public void temporaryReady(Path temporary, Path target) {
                        reservationCurrent.set(false);
                    }
                });
        services.add(service);
        LocalPublicationAuthorization authorization = new LocalPublicationAuthorization(
                publication,
                service.volumeId(),
                100,
                ticker::get,
                new MutableLease(() -> {
                    reauthentications.incrementAndGet();
                    if (!reservationCurrent.get()) {
                        throw new IllegalStateException("reservation generation advanced");
                    }
                }));

        assertFixedFailure(
                () -> service.putReservedStaging(
                        authorization,
                        publication,
                        new CloseTrackingInput(new byte[] {1, 2, 3}),
                        3),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");

        assertThat(reauthentications).hasValue(2);
        assertThat(root.resolve(KEY)).doesNotExist();
        assertThat(allPublicationFiles(root)).isEmpty();
    }

    @Test
    void ordinaryNonStagingPublicationKeepsItsExistingBehavior() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);

        service.put("originals/asset.jpg", new ByteArrayInputStream(new byte[] {7}), 1,
                "image/jpeg");

        assertThat(Files.readAllBytes(root.resolve("originals/asset.jpg")))
                .containsExactly(7);
    }

    @Test
    void ordinaryPublicationKeepsRandomPartAndIdentityNames() throws Exception {
        Path root = storageRoot();
        List<Path> parts = new ArrayList<>();
        List<Path> identities = new ArrayList<>();
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public Object publicationFileKey(
                            Path path,
                            java.nio.file.attribute.BasicFileAttributes attributes) {
                        return null;
                    }

                    @Override
                    public void createPublicationIdentityGuard(Path guard, Path source)
                            throws IOException {
                        identities.add(guard);
                        Files.createLink(guard, source);
                    }

                    @Override
                    public void temporaryReady(Path temporary, Path target) {
                        parts.add(temporary);
                    }
                });
        services.add(service);

        service.put(
                "originals/first.jpg",
                new ByteArrayInputStream(new byte[] {1}),
                1,
                "image/jpeg");
        service.put(
                "originals/second.jpg",
                new ByteArrayInputStream(new byte[] {2}),
                1,
                "image/jpeg");

        assertThat(parts).hasSize(2).doesNotHaveDuplicates();
        assertThat(identities).hasSize(2).doesNotHaveDuplicates();
        assertThat(parts).allMatch(path -> path.getFileName().toString()
                .matches("@part-[0-9a-f-]{36}"));
        assertThat(identities).allMatch(path -> path.getFileName().toString()
                .matches("@identity-[0-9a-f-]{36}"));
    }

    @Test
    void successorGenerationFenceCannotAuthorizeAnotherPublicationAttempt() {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        AtomicLong ticker = new AtomicLong(10);
        LocalStagingPublication successor = new LocalStagingPublication(
                ASSET_ID, KEY, SHA256, "image/jpeg", LOCAL, 1, CLEANUP_JOB_ID);
        LocalPublicationAuthorization authorization = authorization(
                service, successor, ticker, new MutableLease());
        CloseTrackingInput input = new CloseTrackingInput(new byte[] {1});
        long before = entryCount(root);

        assertFixedFailure(
                () -> service.putReservedStaging(
                        authorization, successor, input, 1),
                "LOCAL_STAGING_AUTHORIZATION_INVALID");

        assertThat(input.closeCount()).isOne();
        assertThat(entryCount(root)).isEqualTo(before);
        assertThat(root.resolve("staging")).doesNotExist();
    }

    private static LocalStagingPublication publication() {
        return new LocalStagingPublication(
                ASSET_ID, KEY, SHA256, "image/jpeg", LOCAL, 0, CLEANUP_JOB_ID);
    }

    private static LocalPublicationAuthorization authorization(
            LocalStorageService service,
            LocalStagingPublication publication,
            AtomicLong ticker,
            MutableLease lease) {
        return new LocalPublicationAuthorization(
                publication, service.volumeId(), 100, ticker::get, lease);
    }

    private LocalStorageService service(Path root) {
        LocalStorageService service = new LocalStorageService(new LocalStorageProperties(root));
        services.add(service);
        return service;
    }

    private Path storageRoot() {
        try {
            FileStore store = Files.getFileStore(temporaryDirectory);
            if (store.supportsFileAttributeView(AclFileAttributeView.class)
                    && !store.supportsFileAttributeView("posix")) {
                if (storageBoundary == null) {
                    storageBoundary = Files.createTempDirectory(
                            Path.of(System.getProperty("user.home")),
                            ".portfolio-media-reserved-test-");
                    externalCleanup.add(storageBoundary);
                }
                return storageBoundary.resolve("store");
            }
            return temporaryDirectory.resolve("store");
        } catch (IOException exception) {
            throw new IllegalStateException("test storage root unavailable", exception);
        }
    }

    private static long entryCount(Path root) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.count();
        } catch (IOException exception) {
            throw new IllegalStateException("test entry count failed", exception);
        }
    }

    private static List<Path> allPublicationFiles(Path root) throws IOException {
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS))
                    .filter(path -> !path.getFileName()
                            .toString()
                            .equals(".portfolio-storage-root@guard"))
                    .toList();
        }
    }

    private static void assertFixedFailure(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(StorageException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code))
                .hasMessage(code)
                .hasNoCause();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class CloseTrackingInput extends ByteArrayInputStream {
        private final AtomicInteger closes = new AtomicInteger();

        private CloseTrackingInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
            super.close();
        }

        int closeCount() {
            return closes.get();
        }
    }

    private static final class MutableLease
            implements LocalPublicationAuthorization.FenceLease {
        private final AtomicBoolean held = new AtomicBoolean(true);
        private final Runnable authentication;

        private MutableLease() {
            this(() -> {});
        }

        private MutableLease(Runnable authentication) {
            this.authentication = authentication;
        }

        @Override
        public boolean isHeld() {
            return held.get();
        }

        @Override
        public void reauthenticate() {
            authentication.run();
            LocalPublicationAuthorization.FenceLease.super.reauthenticate();
        }

        @Override
        public void close() {
            held.set(false);
        }

        void releaseExternally() {
            held.set(false);
        }
    }
}
