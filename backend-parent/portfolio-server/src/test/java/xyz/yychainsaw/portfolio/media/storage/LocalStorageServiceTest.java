package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

class LocalStorageServiceTest {
    private static final String CONTENT_TYPE = "application/octet-stream";

    @TempDir
    Path temporaryDirectory;

    private final List<LocalStorageService> openServices = new ArrayList<>();
    private final List<Path> externalCleanup = new ArrayList<>();
    private Path storageBoundary;

    @AfterEach
    void closeServicesAndRemoveExternalTestDirectories() throws Exception {
        for (int index = openServices.size() - 1; index >= 0; index--) {
            openServices.get(index).close();
        }
        for (int index = externalCleanup.size() - 1; index >= 0; index--) {
            deleteTree(externalCleanup.get(index));
        }
    }

    @Test
    void writesAndReadsWithinConfiguredRoot() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        byte[] bytes = "portfolio".getBytes(UTF_8);

        StoredObject stored = service.put(
                "originals/asset.bin", tracking(bytes), bytes.length, CONTENT_TYPE);

        assertThat(service.location())
                .isEqualTo(new StorageLocation(StorageProvider.LOCAL, null, null));
        assertThat(service.location().provider()).isEqualTo(service.provider());
        assertThat(stored.provider()).isEqualTo(StorageProvider.LOCAL);
        assertThat(stored.bucket()).isNull();
        assertThat(stored.region()).isNull();
        assertThat(stored.objectKey()).isEqualTo("originals/asset.bin");
        assertThat(stored.contentLength()).isEqualTo(bytes.length);
        assertThat(stored.contentType()).isEqualTo(CONTENT_TYPE);
        assertThat(stored.etag()).matches("[0-9a-f]{64}");
        try (StorageRead read = service.open("originals/asset.bin", Optional.empty())) {
            assertThat(read.inputStream().readAllBytes()).isEqualTo(bytes);
            assertThat(read.totalLength()).isEqualTo(bytes.length);
            assertThat(read.range()).isEmpty();
            assertThat(read.contentLength()).isEqualTo(bytes.length);
            assertThat(read.contentType()).isEqualTo(CONTENT_TYPE);
            assertThat(read.etag()).isEqualTo(stored.etag());
        }
        assertNoTemporaryFiles(root);
    }

    @Test
    void putOwnsAndClosesTheInputExactlyOnceOnSuccess() {
        CloseTrackingInputStream input = tracking("owned".getBytes(UTF_8));

        service(storageRoot())
                .put("asset.bin", input, 5, CONTENT_TYPE);

        assertThat(input.closeCount()).isOne();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "/absolute", "//server/share", "C:/drive", "C:\\drive",
            "a\\b", "a//b", "a/", ".", "..", "a/./b", "a/../b", "a/b:bad", "a/b bad",
            "a/\u0000b", "a/\u001fb", "a/é"})
    void rejectsNonCanonicalOrUnsafeObjectKeysBeforeFilesystemAccess(String key) {
        CloseTrackingInputStream input = tracking(new byte[] {1});

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service(storageRoot())
                        .put(key, input, 1, CONTENT_TYPE))
                .withMessage("Invalid storage object key");
        assertThat(input.closeCount()).isOne();
    }

    @Test
    void rejectsOverlongObjectKeysWithoutEchoingThem() {
        String key = "a".repeat(1025);
        CloseTrackingInputStream input = tracking(new byte[] {1});

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service(storageRoot())
                        .put(key, input, 1, CONTENT_TYPE))
                .withMessage("Invalid storage object key");
        assertThat(input.closeCount()).isOne();
    }

    @Test
    void acceptsBoundedAsciiSafeSegments() throws Exception {
        LocalStorageService service = service(storageRoot());

        service.put("originals/2026/asset-1_2.3.bin", tracking(new byte[] {7}), 1, CONTENT_TYPE);

        try (StorageRead read = service.open("originals/2026/asset-1_2.3.bin", Optional.empty())) {
            assertThat(read.inputStream().readAllBytes()).containsExactly(7);
        }
    }

    @Test
    void validationFailureStillClosesOwnedInputWithoutCreatingFiles() throws Exception {
        Path root = storageRoot();
        CloseTrackingInputStream zeroLength = tracking(new byte[0]);
        CloseTrackingInputStream excessiveLength = tracking(new byte[0]);
        CloseTrackingInputStream blankType = tracking(new byte[] {1});

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service(root).put("zero.bin", zeroLength, 0, CONTENT_TYPE))
                .withMessage("Invalid storage content length");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service(root).put(
                        "huge.bin", excessiveLength, Long.MAX_VALUE, CONTENT_TYPE))
                .withMessage("Invalid storage content length");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service(root).put("type.bin", blankType, 1, "   "))
                .withMessage("Invalid storage content type");
        assertThat(zeroLength.closeCount()).isOne();
        assertThat(excessiveLength.closeCount()).isOne();
        assertThat(blankType.closeCount()).isOne();
        assertNoTemporaryFiles(root);
    }

    @Test
    void rejectsDeclaredContentTypeThatDoesNotMatchTheObjectKeyExtension() throws Exception {
        Path root = storageRoot();
        CloseTrackingInputStream input = tracking("pdf".getBytes(UTF_8));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service(root).put("asset.bin", input, 3, "application/pdf"))
                .withMessage("Invalid storage content type");

        assertThat(input.closeCount()).isOne();
        assertThat(Files.exists(root.resolve("asset.bin"), NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void normalizesDeclaredContentTypeAndReturnsItConsistently() throws Exception {
        LocalStorageService service = service(storageRoot());

        StoredObject stored = service.put(
                "asset.bin", tracking(new byte[] {1}), 1, "Application/Octet-Stream");

        assertThat(stored.contentType()).isEqualTo(CONTENT_TYPE);
        try (StorageRead read = service.open("asset.bin", Optional.empty())) {
            assertThat(read.contentType()).isEqualTo(stored.contentType());
        }
    }

    @Test
    void localStoragePropertiesRejectExplicitDangerousRootsButKeepTheDefault() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalStorageProperties(Path.of("")))
                .withMessage("Invalid local storage root");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalStorageProperties(Path.of(".")))
                .withMessage("Invalid local storage root");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalStorageProperties(temporaryDirectory.getRoot()))
                .withMessage("Invalid local storage root");

        assertThat(new LocalStorageProperties(null).root()).isEqualTo(Path.of("../runtime/media"));
    }

    @Test
    void rejectsNullInputBeforePublication() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service(storageRoot())
                        .put("asset.bin", null, 1, CONTENT_TYPE))
                .withMessage("Storage input is required");
    }

    @Test
    void rejectsShortInputClosesItAndRemovesTemporaryFile() throws Exception {
        Path root = storageRoot();
        CloseTrackingInputStream input = tracking("short".getBytes(UTF_8));

        assertStorageFailure(
                () -> service(root).put("asset.bin", input, 6, CONTENT_TYPE),
                "LOCAL_CONTENT_LENGTH_MISMATCH");

        assertThat(input.closeCount()).isOne();
        assertThat(Files.exists(root.resolve("asset.bin"), NOFOLLOW_LINKS)).isFalse();
        assertNoTemporaryFiles(root);
    }

    @Test
    void rejectsLongInputAfterProbingExactlyOneExtraByte() throws Exception {
        Path root = storageRoot();
        ProbeTrackingInputStream input = new ProbeTrackingInputStream("too-long".getBytes(UTF_8));

        assertStorageFailure(
                () -> service(root).put("asset.bin", input, 3, CONTENT_TYPE),
                "LOCAL_CONTENT_LENGTH_MISMATCH");

        assertThat(input.bytesReturned()).isEqualTo(4);
        assertThat(input.closeCount()).isOne();
        assertNoTemporaryFiles(root);
    }

    @Test
    void closesThrowingInputAndRemovesTemporaryFile() throws Exception {
        Path root = storageRoot();
        ThrowingInputStream input = new ThrowingInputStream();

        assertStorageFailure(
                () -> service(root).put("asset.bin", input, 1, CONTENT_TYPE),
                "LOCAL_WRITE_FAILED");

        assertThat(input.closeCount()).isOne();
        assertNoTemporaryFiles(root);
    }

    @Test
    void aPreExistingTargetIsNeverOverwritten() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        service.put("asset.bin", tracking("first".getBytes(UTF_8)), 5, CONTENT_TYPE);

        assertStorageFailure(
                () -> service.put("asset.bin", tracking("later".getBytes(UTF_8)), 5, CONTENT_TYPE),
                "STORAGE_OBJECT_ALREADY_EXISTS");

        assertThat(Files.readAllBytes(root.resolve("asset.bin"))).isEqualTo("first".getBytes(UTF_8));
        assertNoTemporaryFiles(root);
    }

    @Test
    void concurrentPublishersProduceOneCompleteWinnerWithoutOverwrite() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        int attempts = 12;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PutAttempt>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < attempts; index++) {
                byte[] payload = ("payload-" + index).getBytes(UTF_8);
                futures.add(executor.submit(() -> {
                    CloseTrackingInputStream input = tracking(payload);
                    start.await();
                    try {
                        service.put("winner.bin", input, payload.length, CONTENT_TYPE);
                        return new PutAttempt(payload, null, input.closeCount());
                    } catch (StorageException exception) {
                        return new PutAttempt(payload, exception, input.closeCount());
                    }
                }));
            }
            start.countDown();

            List<PutAttempt> results = new ArrayList<>();
            for (Future<PutAttempt> future : futures) {
                results.add(future.get(10, SECONDS));
            }
            assertThat(results).filteredOn(result -> result.failure() == null).hasSize(1);
            assertThat(results).filteredOn(result -> result.failure() != null).allSatisfy(result ->
                    assertThat(result.failure().code()).isEqualTo("STORAGE_OBJECT_ALREADY_EXISTS"));
            assertThat(results).allSatisfy(result -> assertThat(result.closeCount()).isOne());
            PutAttempt winner = results.stream()
                    .filter(result -> result.failure() == null)
                    .findFirst()
                    .orElseThrow();
            assertThat(Files.readAllBytes(root.resolve("winner.bin"))).isEqualTo(winner.payload());
            assertNoTemporaryFiles(root);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void readsFirstLastAndBoundedInclusiveRanges() throws Exception {
        LocalStorageService service = service(storageRoot());
        byte[] bytes = "portfolio".getBytes(UTF_8);
        StoredObject stored = service.put("asset.txt", tracking(bytes), bytes.length, "text/plain");

        assertRange(service, new ByteRange(0, 0), "p", bytes.length, stored.etag());
        assertRange(service, new ByteRange(bytes.length - 1, bytes.length - 1), "o", bytes.length,
                stored.etag());
        assertRange(service, new ByteRange(1, 4), "ortf", bytes.length, stored.etag());
    }

    @Test
    void rejectsRangesStartingAtOrEndingAtTheObjectLength() {
        LocalStorageService service = service(storageRoot());
        service.put("asset.bin", tracking(new byte[] {1, 2, 3}), 3, CONTENT_TYPE);

        assertUnsatisfiable(service, new ByteRange(3, 3), 3);
        assertUnsatisfiable(service, new ByteRange(0, 3), 3);
    }

    @Test
    void boundedStreamCapsSingleBulkAndSkipOperations() throws Exception {
        CloseTrackingInputStream source = tracking("abcdef".getBytes(UTF_8));
        try (BoundedInputStream input = new BoundedInputStream(source, 4)) {
            assertThat(input.read()).isEqualTo('a');
            assertThat(input.skip(2)).isEqualTo(2);
            byte[] destination = new byte[8];
            assertThat(input.read(destination, 0, destination.length)).isOne();
            assertThat(destination[0]).isEqualTo((byte) 'd');
            assertThat(input.read()).isEqualTo(-1);
            assertThat(input.skip(10)).isZero();
        }
        assertThat(source.closeCount()).isOne();
    }

    @Test
    void boundedStreamSkipFallsBackWhenBulkReadsMakeNoProgress() throws Exception {
        try (BoundedInputStream input = new BoundedInputStream(
                new ZeroProgressBulkInputStream("abc".getBytes(UTF_8)), 3)) {
            assertThat(input.skip(2)).isEqualTo(2);
            assertThat(input.read()).isEqualTo('c');
        }
    }

    @Test
    void boundedStreamThrowsOnPrematureEofAndClosesUnderlyingStreamExactlyOnce() throws Exception {
        CloseTrackingInputStream source = tracking(new byte[] {1});
        BoundedInputStream input = new BoundedInputStream(source, 2);

        assertThat(input.read()).isOne();
        assertThatThrownBy(input::read)
                .isInstanceOf(IOException.class)
                .hasMessage("Unexpected end of storage object");
        input.close();
        input.close();

        assertThat(source.closeCount()).isOne();
    }

    @Test
    void boundedStreamRejectsMarkAndReset() throws Exception {
        try (BoundedInputStream input = new BoundedInputStream(
                new ByteArrayInputStream(new byte[] {1}), 1)) {
            assertThat(input.markSupported()).isFalse();
            input.mark(1);
            assertThatThrownBy(input::reset)
                    .isInstanceOf(IOException.class)
                    .hasMessage("Mark/reset is not supported");
        }
    }

    @Test
    void copyPublishesAnIndependentCreateOnlyObject() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        service.put("source.bin", tracking("source".getBytes(UTF_8)), 6, CONTENT_TYPE);

        service.copy("source.bin", "nested/target.bin");
        assertThat(Files.readAllBytes(root.resolve("nested/target.bin")))
                .isEqualTo("source".getBytes(UTF_8));

        assertStorageFailure(
                () -> service.copy("source.bin", "nested/target.bin"),
                "STORAGE_OBJECT_ALREADY_EXISTS");
        assertThat(Files.readAllBytes(root.resolve("nested/target.bin")))
                .isEqualTo("source".getBytes(UTF_8));
        assertNoTemporaryFiles(root);
    }

    @Test
    void copyRejectsAContentTypeMismatchBetweenSourceAndTargetExtensions() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        service.put("source.pdf", tracking("pdf".getBytes(UTF_8)), 3, "application/pdf");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.copy("source.pdf", "target.bin"))
                .withMessage("Invalid storage content type");

        assertThat(Files.exists(root.resolve("target.bin"), NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void deleteIsIdempotentForMissingObjectsAndRemovesRegularObjects() {
        LocalStorageService service = service(storageRoot());
        service.delete("missing.bin");
        service.put("asset.bin", tracking(new byte[] {1}), 1, CONTENT_TYPE);

        service.delete("asset.bin");
        service.delete("asset.bin");

        assertThat(service.exists("asset.bin")).isFalse();
    }

    @Test
    void existsIsFalseOnlyForAMissingSafeRegularObject() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        assertThat(service.exists("missing.bin")).isFalse();
        service.put("asset.bin", tracking(new byte[] {1}), 1, CONTENT_TYPE);
        assertThat(service.exists("asset.bin")).isTrue();
        Files.createDirectory(root.resolve("directory.bin"));

        assertStorageFailure(() -> service.exists("directory.bin"), "LOCAL_UNSAFE_PATH");
    }

    @Test
    void localSignedGetIsAlwaysUnsupportedAfterValidatingArguments() {
        LocalStorageService service = service(storageRoot());

        assertThatThrownBy(() -> service.signedGet("asset.bin", Duration.ofMinutes(1)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Local storage is streamed by the application");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.signedGet("../asset.bin", Duration.ofMinutes(1)))
                .withMessage("Invalid storage object key");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.signedGet("asset.bin", Duration.ZERO))
                .withMessage("Invalid signed URL TTL");
    }

    @Test
    void byteRangeAndStorageValueTypesRejectIncompleteStates() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ByteRange(-1, 0))
                .withMessage("Invalid byte range");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ByteRange(2, 1))
                .withMessage("Invalid byte range");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ByteRange(0, Long.MAX_VALUE))
                .withMessage("Invalid byte range");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StoredObject(
                        null, null, null, "asset.bin", 1, CONTENT_TYPE, "0".repeat(64)))
                .withMessage("Storage provider is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StorageRead(
                        null, 1, Optional.empty(), 1, CONTENT_TYPE, "0".repeat(64)))
                .withMessage("Storage input is required");
    }

    @Test
    void storedObjectUsesTheSharedContentLengthBound() {
        long maximum = 5L * 1024 * 1024 * 1024;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StoredObject(
                        StorageProvider.LOCAL,
                        null,
                        null,
                        "asset.bin",
                        maximum + 1,
                        CONTENT_TYPE,
                        "0".repeat(64)))
                .withMessage("Invalid storage content length");

        StoredObject valid = new StoredObject(
                StorageProvider.LOCAL,
                null,
                null,
                "asset.bin",
                maximum,
                CONTENT_TYPE,
                "0".repeat(64));
        assertThat(valid.contentLength()).isEqualTo(maximum);
    }

    @Test
    void storageLocationRequiresTheExactProviderSpecificShape() {
        assertThat(StorageLocation.class.isRecord()).isTrue();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StorageLocation(null, null, null))
                .withMessage("Storage location is invalid");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StorageLocation(
                        StorageProvider.LOCAL, "private-root", null))
                .withMessage("Storage location is invalid");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StorageLocation(
                        StorageProvider.TENCENT_COS, null, "ap-guangzhou"))
                .withMessage("Storage location is invalid");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StorageLocation(
                        StorageProvider.TENCENT_COS, "portfolio-1234567890", "   "))
                .withMessage("Storage location is invalid");
    }

    @Test
    void storedObjectRequiresTheKeysCanonicalContentType() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StoredObject(
                        StorageProvider.LOCAL,
                        null,
                        null,
                        "asset.pdf",
                        1,
                        CONTENT_TYPE,
                        "0".repeat(64)))
                .withMessage("Invalid storage content type");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StoredObject(
                        StorageProvider.LOCAL,
                        null,
                        null,
                        "asset.pdf",
                        1,
                        "application/*",
                        "0".repeat(64)))
                .withMessage("Invalid storage content type");

        StoredObject valid = new StoredObject(
                StorageProvider.LOCAL,
                null,
                null,
                "asset.pdf",
                1,
                "Application/Pdf",
                "0".repeat(64));
        assertThat(valid.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void storageReadUsesTheSharedTotalLengthBound() {
        long maximum = 5L * 1024 * 1024 * 1024;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StorageRead(
                        new ByteArrayInputStream(new byte[0]),
                        maximum + 1,
                        Optional.empty(),
                        maximum + 1,
                        CONTENT_TYPE,
                        "0".repeat(64)))
                .withMessage("Invalid storage content length");

        StorageRead valid = new StorageRead(
                new ByteArrayInputStream(new byte[0]),
                maximum,
                Optional.empty(),
                maximum,
                CONTENT_TYPE,
                "0".repeat(64));
        assertThat(valid.totalLength()).isEqualTo(maximum);
    }

    @Test
    void storageReadRequiresAConcreteNormalizedContentType() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StorageRead(
                        new ByteArrayInputStream(new byte[0]),
                        1,
                        Optional.empty(),
                        1,
                        "not a media type",
                        "0".repeat(64)))
                .withMessage("Invalid storage content type");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StorageRead(
                        new ByteArrayInputStream(new byte[0]),
                        1,
                        Optional.empty(),
                        1,
                        "application/*",
                        "0".repeat(64)))
                .withMessage("Invalid storage content type");

        StorageRead valid = new StorageRead(
                new ByteArrayInputStream(new byte[0]),
                1,
                Optional.empty(),
                1,
                "Application/Octet-Stream",
                "0".repeat(64));
        assertThat(valid.contentType()).isEqualTo(CONTENT_TYPE);
    }

    @Test
    void stagingCleanupDeletesTheInclusiveOldBoundaryAndPreservesEverythingElse()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        String oldKey = stagingKey(
                "11111111-1111-4111-8111-111111111111", sha(1), "jpg");
        String youngKey = stagingKey(
                "22222222-2222-4222-8222-222222222222", sha(2), "png");
        String nonCanonicalDirectoryKey = stagingKey(
                "not-a-canonical-asset", sha(3), "jpg");
        String nonCanonicalFileKey = stagingKey(
                "33333333-3333-4333-8333-333333333333",
                "not-a-canonical-sha",
                "jpg");
        String originalKey = "originals/44444444-4444-4444-8444-444444444444/"
                + sha(5) + ".pdf";
        stage(service, root, oldKey, cutoff);
        stage(service, root, youngKey, cutoff.plusSeconds(1));
        String canonicalDirectorySeed = stagingKey(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", sha(3), "jpg");
        stage(service, root, canonicalDirectorySeed, cutoff.minusSeconds(60));
        Files.move(
                root.resolve(canonicalDirectorySeed).getParent(),
                root.resolve(nonCanonicalDirectoryKey).getParent());
        String canonicalFileSeed = stagingKey(
                "33333333-3333-4333-8333-333333333333", sha(10), "jpg");
        stage(service, root, canonicalFileSeed, cutoff.minusSeconds(60));
        Files.move(root.resolve(canonicalFileSeed), root.resolve(nonCanonicalFileKey));
        stage(service, root, originalKey, cutoff.minusSeconds(60));
        Path unknown = root.resolve("staging").resolve("@unknown-reserved");
        Files.write(unknown, new byte[] {9});
        boolean prunesOldDirectory = hasFileIdentity(root.resolve(oldKey).getParent());

        StagingCleanupResult result = service.cleanupStaging(cutoff);

        assertThat(result.deleted()).isEqualTo(prunesOldDirectory ? 2 : 1);
        assertThat(result.scanned()).isPositive();
        assertThat(result.candidates()).isOne();
        assertThat(result.elapsed().isNegative()).isFalse();
        assertThat(root.resolve(oldKey)).doesNotExist();
        assertThat(Files.exists(root.resolve(oldKey).getParent()))
                .isEqualTo(!prunesOldDirectory);
        assertThat(root.resolve(youngKey)).exists();
        assertThat(root.resolve(nonCanonicalDirectoryKey)).exists();
        assertThat(root.resolve(nonCanonicalFileKey)).exists();
        assertThat(root.resolve(originalKey)).exists();
        assertThat(unknown).exists();
    }

    @Test
    void stagingCleanupIsAMissingRootNoOpAndRejectsAnUnsafeCanonicalNode() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");

        assertThat(service.cleanupStaging(cutoff).deleted()).isZero();

        String seed = stagingKey(
                "11111111-1111-4111-8111-111111111111", sha(1), "jpg");
        stage(service, root, seed, cutoff.plusSeconds(1));
        Path unsafe = root.resolve("staging")
                .resolve("22222222-2222-4222-8222-222222222222");
        Files.write(unsafe, new byte[] {1});

        assertStorageFailure(
                () -> service.cleanupStaging(cutoff),
                "LOCAL_STAGING_CLEANUP_FAILED");
        assertThat(unsafe).exists();
        assertThat(root.resolve(seed)).exists();
    }

    @Test
    void fullStreamingSelectionIgnoresYoungAndUnknownEntriesAndDeletesOldestFirst()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        String asset = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        int limit = LocalStorageService.STAGING_CLEANUP_FILE_DELETE_LIMIT;
        List<Path> eligible = new ArrayList<>();
        for (int index = 0; index < limit + 3; index++) {
            String key = stagingKey(asset, sha(10_000 + index), "jpg");
            Instant modified = cutoff.minusSeconds(index + 1L);
            stage(service, root, key, modified);
            eligible.add(root.resolve(key));
        }
        for (int index = 0; index < limit + 7; index++) {
            String key = stagingKey(asset, sha(20_000 + index), "png");
            stage(service, root, key, cutoff.plusSeconds(index + 1L));
        }
        Path directory = root.resolve("staging").resolve(asset);
        for (int index = 0; index < limit + 7; index++) {
            Files.write(directory.resolve("000-unknown-" + index), new byte[] {1});
        }

        assertThat(service.cleanupStaging(cutoff).deleted()).isEqualTo(limit);

        assertThat(eligible.subList(0, 3)).allMatch(Files::exists);
        assertThat(eligible.subList(3, eligible.size())).noneMatch(Files::exists);
        assertThat(service.cleanupStaging(cutoff).deleted()).isEqualTo(3);
        assertThat(eligible).noneMatch(Files::exists);
        assertThat(directory).exists();
        assertThat(service.cleanupStaging(cutoff).deleted()).isZero();
    }

    @Test
    void exceedingTheConfiguredScanCeilingFailsBeforeAnyDeletion() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                LocalStorageService.OperationObserver.NOOP,
                3);
        openServices.add(service);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        List<Path> staged = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            String key = stagingKey(
                    "11111111-1111-4111-8111-111111111111",
                    sha(index + 1),
                    "jpg");
            stage(service, root, key, cutoff.minusSeconds(index));
            staged.add(root.resolve(key));
        }

        assertStorageFailure(
                () -> service.cleanupStaging(cutoff),
                "LOCAL_STAGING_CLEANUP_FAILED");
        assertThat(staged).allMatch(Files::exists);
    }

    @Test
    void cleanupIsRepeatSafeWhenAnotherReplicaDeletesTheCandidateFirst() throws Exception {
        Path root = storageRoot();
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        AtomicBoolean deletedByReplica = new AtomicBoolean();
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public void beforeStagingCleanupDelete(Path target) throws IOException {
                        if (deletedByReplica.compareAndSet(false, true)) {
                            Files.delete(target);
                        }
                    }
                });
        openServices.add(service);
        String key = stagingKey(
                "11111111-1111-4111-8111-111111111111", sha(1), "jpg");
        stage(service, root, key, cutoff);
        boolean prunesDirectory = hasFileIdentity(root.resolve(key).getParent());

        assertThat(service.cleanupStaging(cutoff).deleted())
                .isEqualTo(prunesDirectory ? 1 : 0);
        assertThat(root.resolve(key)).doesNotExist();
        assertThat(service.cleanupStaging(cutoff).deleted()).isZero();
    }

    @Test
    void cleanupFailsClosedWhenASelectedFilesIdentityIsExchanged() throws Exception {
        Path root = storageRoot();
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        String key = stagingKey(
                "11111111-1111-4111-8111-111111111111", sha(1), "jpg");
        AtomicReference<LocalStorageService> holder = new AtomicReference<>();
        AtomicBoolean exchanged = new AtomicBoolean();
        Path backup = root.resolve("staging")
                .resolve("11111111-1111-4111-8111-111111111111")
                .resolve("@old-candidate");
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public void beforeStagingCleanupDelete(Path target) throws IOException {
                        if (!exchanged.compareAndSet(false, true)) {
                            return;
                        }
                        Files.move(target, backup);
                        putStagingReserved(
                                holder.get(), key, tracking(new byte[] {2}), 1);
                        Files.setLastModifiedTime(target, FileTime.from(cutoff));
                    }
                });
        holder.set(service);
        openServices.add(service);
        stage(service, root, key, cutoff);

        assertStorageFailure(
                () -> service.cleanupStaging(cutoff),
                "LOCAL_STAGING_CLEANUP_FAILED");
        assertThat(root.resolve(key)).exists();
        assertThat(backup).exists();
    }

    @Test
    void deterministicIdentityGuardRecoversAfterItsFirstReleaseIsRefused()
            throws Exception {
        Path root = storageRoot();
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        String asset = "11111111-1111-4111-8111-111111111111";
        String key = stagingKey(asset, sha(1), "jpg");
        AtomicInteger releases = new AtomicInteger();
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public void beforeStagingCleanupGuardRelease(
                            Path guard, Path target) throws IOException {
                        if (releases.incrementAndGet() == 1) {
                            throw new IOException("simulated guard release refusal");
                        }
                    }
                });
        openServices.add(service);
        stage(service, root, key, cutoff);
        boolean prunesDirectory = hasFileIdentity(root.resolve(key).getParent());

        assertStorageFailure(
                () -> service.cleanupStaging(cutoff),
                "LOCAL_STAGING_CLEANUP_FAILED");

        Path directory = root.resolve("staging").resolve(asset);
        assertThat(root.resolve(key)).doesNotExist();
        try (var entries = Files.list(directory)) {
            assertThat(entries.filter(path -> path.getFileName()
                            .toString()
                            .startsWith("@cleanup-identity-")))
                    .hasSize(1);
        }

        assertThat(service.cleanupStaging(cutoff).deleted())
                .isEqualTo(prunesDirectory ? 2 : 1);
        assertThat(Files.exists(directory)).isEqualTo(!prunesDirectory);
        assertThat(releases).hasValue(1);
    }

    @Test
    void verificationGuardSlotRecoversAsTheOnlyRemainingLink() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        String key = stagingKey(
                "11111111-1111-4111-8111-111111111111", sha(1), "jpg");
        stage(service, root, key, cutoff);
        Path target = root.resolve(key);
        Path directory = target.getParent();
        boolean prunesDirectory = hasFileIdentity(directory);
        Path verification = directory.resolve(
                "@cleanup-verification-" + target.getFileName());
        Files.createLink(verification, target);
        Files.delete(target);

        assertThat(service.cleanupStaging(cutoff).deleted())
                .isEqualTo(prunesDirectory ? 2 : 1);
        assertThat(verification).doesNotExist();
        assertThat(directory.resolve(
                "@cleanup-identity-" + target.getFileName())).doesNotExist();
        assertThat(Files.exists(directory)).isEqualTo(!prunesDirectory);
    }

    @Test
    void equalPrimaryAndVerificationGuardSlotsAreBothRecoveredWithinTheBound()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        String key = stagingKey(
                "11111111-1111-4111-8111-111111111111", sha(1), "jpg");
        stage(service, root, key, cutoff);
        Path target = root.resolve(key);
        Path directory = target.getParent();
        boolean prunesDirectory = hasFileIdentity(directory);
        Path primary = directory.resolve("@cleanup-identity-" + target.getFileName());
        Path verification = directory.resolve(
                "@cleanup-verification-" + target.getFileName());
        Files.createLink(primary, target);
        Files.createLink(verification, target);
        Files.delete(target);

        assertThat(service.cleanupStaging(cutoff).deleted())
                .isEqualTo(prunesDirectory ? 3 : 2);
        assertThat(primary).doesNotExist();
        assertThat(verification).doesNotExist();
    }

    @Test
    void differentGuardSlotIdentitiesFailClosedBeforeDeletingEither() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        String asset = "11111111-1111-4111-8111-111111111111";
        String firstKey = stagingKey(asset, sha(1), "jpg");
        String secondKey = stagingKey(asset, sha(2), "jpg");
        stage(service, root, firstKey, cutoff);
        stage(service, root, secondKey, cutoff);
        Path first = root.resolve(firstKey);
        Path second = root.resolve(secondKey);
        Path primary = first.getParent().resolve(
                "@cleanup-identity-" + first.getFileName());
        Path verification = first.getParent().resolve(
                "@cleanup-verification-" + first.getFileName());
        Files.createLink(primary, first);
        Files.createLink(verification, second);
        Files.delete(first);
        Files.delete(second);

        assertStorageFailure(
                () -> service.cleanupStaging(cutoff),
                "LOCAL_STAGING_CLEANUP_FAILED");
        assertThat(primary).exists();
        assertThat(verification).exists();
    }

    @Test
    void nullDirectoryIdentityRetainsTheDirectoryButStillDeletesTheStronglyBoundFile()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public Object stagingCleanupDirectoryIdentity(
                            Path directory, BasicFileAttributes attributes) {
                        return null;
                    }
                });
        openServices.add(service);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        String key = stagingKey(
                "11111111-1111-4111-8111-111111111111", sha(1), "jpg");
        stage(service, root, key, cutoff);
        Path target = root.resolve(key);

        assertThat(service.cleanupStaging(cutoff).deleted()).isOne();
        assertThat(target).doesNotExist();
        assertThat(target.getParent()).isDirectory().isEmptyDirectory();
    }

    @Test
    void concurrentCleanupCallsNeverDoubleDeleteAStagingObject() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        String key = stagingKey(
                "11111111-1111-4111-8111-111111111111", sha(1), "jpg");
        stage(service, root, key, cutoff);
        boolean prunesDirectory = hasFileIdentity(root.resolve(key).getParent());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StagingCleanupResult> first =
                    executor.submit(() -> service.cleanupStaging(cutoff));
            Future<StagingCleanupResult> second =
                    executor.submit(() -> service.cleanupStaging(cutoff));

            assertThat(first.get(10, SECONDS).deleted()
                            + second.get(10, SECONDS).deleted())
                    .isEqualTo(prunesDirectory ? 2 : 1);
            assertThat(root.resolve(key)).doesNotExist();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    @Test
    void unknownSymbolicLinksAreNeverFollowedOrDeletedWhenThePlatformAllowsThem()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        String seed = stagingKey(
                "11111111-1111-4111-8111-111111111111", sha(1), "jpg");
        stage(service, root, seed, cutoff.plusSeconds(1));
        Path outside = root.resolve("outside-do-not-delete");
        Files.write(outside, new byte[] {1});
        Path link = root.resolve("staging").resolve("@unknown-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            return;
        }

        assertThat(service.cleanupStaging(cutoff).deleted()).isZero();
        assertThat(Files.exists(link, NOFOLLOW_LINKS)).isTrue();
        assertThat(outside).exists();
    }

    @Test
    void interruptedCleanupFailsBeforeDeletingAnySelectedCandidate() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        String key = stagingKey(
                "11111111-1111-4111-8111-111111111111", sha(1), "jpg");
        stage(service, root, key, cutoff);

        Thread.currentThread().interrupt();
        try {
            assertStorageFailure(
                    () -> service.cleanupStaging(cutoff),
                    "LOCAL_STAGING_CLEANUP_FAILED");
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }
        assertThat(root.resolve(key)).exists();
    }

    @Test
    void stagingCleanupResultRejectsIncompleteOrNegativeMetrics() {
        for (long[] values : List.of(
                new long[] {-1, 0, 0},
                new long[] {0, -1, 0},
                new long[] {0, 0, -1})) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new StagingCleanupResult(
                            values[0], values[1], values[2], Duration.ZERO))
                    .withMessage("Invalid staging cleanup result")
                    .withNoCause();
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StagingCleanupResult(0, 0, 0, null))
                .withMessage("Invalid staging cleanup result")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StagingCleanupResult(
                        0, 0, 0, Duration.ofNanos(-1)))
                .withMessage("Invalid staging cleanup result")
                .withNoCause();
    }

    private static void stage(
            LocalStorageService service, Path root, String key, Instant modified) {
        String contentType = key.endsWith(".jpg")
                ? "image/jpeg"
                : key.endsWith(".png")
                        ? "image/png"
                        : "application/pdf";
        if (key.startsWith("staging/")) {
            putStagingReserved(service, key, tracking(new byte[] {1}), 1);
        } else {
            service.put(key, tracking(new byte[] {1}), 1, contentType);
        }
        try {
            Files.setLastModifiedTime(root.resolve(key), FileTime.from(modified));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void putStagingReserved(
            LocalStorageService service,
            String key,
            InputStream input,
            long contentLength) {
        String[] segments = key.split("/");
        String filename = segments[2];
        int extension = filename.lastIndexOf('.');
        String sha256 = filename.substring(0, extension);
        String mimeType = switch (filename.substring(extension + 1)) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "pdf" -> "application/pdf";
            default -> throw new IllegalArgumentException("test staging extension is invalid");
        };
        LocalStagingPublication publication = new LocalStagingPublication(
                UUID.fromString(segments[1]),
                key,
                sha256,
                mimeType,
                new StorageLocation(StorageProvider.LOCAL, null, null),
                0,
                UUID.randomUUID());
        LocalPublicationAuthorization authorization = new LocalPublicationAuthorization(
                publication,
                service.volumeId(),
                Long.MAX_VALUE,
                System::nanoTime,
                new LocalPublicationAuthorization.FenceLease() {
                    @Override
                    public boolean isHeld() {
                        return true;
                    }

                    @Override
                    public void close() {}
                });
        service.putReservedStaging(authorization, publication, input, contentLength);
    }

    private static String stagingKey(String assetId, String sha256, String extension) {
        return "staging/" + assetId + "/" + sha256 + "." + extension;
    }

    private static String sha(int value) {
        return String.format(java.util.Locale.ROOT, "%064x", value);
    }

    private static boolean hasFileIdentity(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS)
                .fileKey() != null;
    }

    private LocalStorageService service(Path root) {
        LocalStorageService service = new LocalStorageService(new LocalStorageProperties(root));
        openServices.add(service);
        return service;
    }

    private Path storageRoot() {
        try {
            FileStore store = Files.getFileStore(temporaryDirectory);
            if (store.supportsFileAttributeView(AclFileAttributeView.class)
                    && !store.supportsFileAttributeView("posix")) {
                if (storageBoundary == null) {
                    storageBoundary = createWindowsTestBoundary();
                }
                return storageBoundary.resolve("store");
            }
            return temporaryDirectory.resolve("store");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static CloseTrackingInputStream tracking(byte[] bytes) {
        return new CloseTrackingInputStream(bytes);
    }

    private static void assertRange(
            LocalStorageService service,
            ByteRange range,
            String expected,
            long totalLength,
            String etag) throws Exception {
        try (StorageRead read = service.open("asset.txt", Optional.of(range))) {
            assertThat(read.inputStream().readAllBytes()).isEqualTo(expected.getBytes(UTF_8));
            assertThat(read.totalLength()).isEqualTo(totalLength);
            assertThat(read.range()).contains(range);
            assertThat(read.contentLength()).isEqualTo(expected.length());
            assertThat(read.contentType()).isEqualTo("text/plain");
            assertThat(read.etag()).isEqualTo(etag);
        }
    }

    private static void assertUnsatisfiable(
            LocalStorageService service, ByteRange range, long totalLength) {
        assertThatThrownBy(() -> service.open("asset.bin", Optional.of(range)))
                .isInstanceOfSatisfying(StorageRangeNotSatisfiableException.class,
                        exception -> assertThat(exception.totalLength()).isEqualTo(totalLength))
                .hasMessage("Storage byte range is not satisfiable");
    }

    private static void assertStorageFailure(ThrowingOperation operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(StorageException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code))
                .hasMessage(code)
                .hasMessageNotContaining("asset")
                .hasMessageNotContaining("linked")
                .hasMessageNotContaining("winner");
    }

    private static void assertNoTemporaryFiles(Path root) throws IOException {
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            assertThat(paths.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".part") || name.startsWith("@part-");
            }))
                    .isEmpty();
        }
    }

    private Path createWindowsTestBoundary() throws IOException {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path boundary = Files.createTempDirectory(home, ".portfolio-media-test-");
        externalCleanup.add(boundary);
        return boundary;
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

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private record PutAttempt(byte[] payload, StorageException failure, int closeCount) {}

    private static class CloseTrackingInputStream extends ByteArrayInputStream {
        private final AtomicInteger closes = new AtomicInteger();

        private CloseTrackingInputStream(byte[] bytes) {
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

    private static final class ProbeTrackingInputStream extends InputStream {
        private final byte[] bytes;
        private int position;
        private int returned;
        private int closes;

        private ProbeTrackingInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public int read() {
            if (position == bytes.length) {
                return -1;
            }
            returned++;
            return bytes[position++] & 0xff;
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (position == bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - position);
            System.arraycopy(bytes, position, destination, offset, count);
            position += count;
            returned += count;
            return count;
        }

        @Override
        public void close() {
            closes++;
        }

        int bytesReturned() {
            return returned;
        }

        int closeCount() {
            return closes;
        }
    }

    private static final class ThrowingInputStream extends InputStream {
        private int closes;

        @Override
        public int read() throws IOException {
            throw new IOException("synthetic read failure");
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            throw new IOException("synthetic read failure");
        }

        @Override
        public void close() {
            closes++;
        }

        int closeCount() {
            return closes;
        }
    }

    private static final class ZeroProgressBulkInputStream extends InputStream {
        private final byte[] bytes;
        private int position;
        private int zeroProgressReads;

        private ZeroProgressBulkInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public int read() {
            return position == bytes.length ? -1 : bytes[position++] & 0xff;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            if (++zeroProgressReads > 2) {
                throw new IOException("bulk read made no progress");
            }
            return 0;
        }
    }
}
