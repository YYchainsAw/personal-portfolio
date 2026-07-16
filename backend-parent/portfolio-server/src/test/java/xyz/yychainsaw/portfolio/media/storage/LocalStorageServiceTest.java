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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
