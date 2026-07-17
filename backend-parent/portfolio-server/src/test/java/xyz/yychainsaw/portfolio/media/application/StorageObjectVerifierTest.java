package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.storage.ByteRange;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRead;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;

class StorageObjectVerifierTest {
    private static final String KEY = "originals/id/hash.pdf";
    private static final String MIME = "application/pdf";
    private static final byte[] BYTES = "verified-object".getBytes(StandardCharsets.US_ASCII);

    @TempDir Path temporaryDirectory;

    @Test
    void exactObjectIsCopiedWithABoundAndStorageReadClosesExactlyOnce()
            throws Exception {
        CloseCountingInput input = new CloseCountingInput(BYTES, 2);
        FakeStorage storage = new FakeStorage(read(
                input, BYTES.length, Optional.empty(), BYTES.length, MIME));
        StorageObjectVerifier verifier = new StorageObjectVerifier(temporaryDirectory);

        VerifiedMediaObject verified = verifier.verify(
                storage, KEY, MIME, BYTES.length, sha256(BYTES));
        try {
            assertThat(storage.openCalls).isOne();
            assertThat(input.closeCalls).isOne();
            assertThat(input.zeroReads).isEqualTo(2);
            try (InputStream copy = verified.openInput()) {
                assertThat(copy.readAllBytes()).isEqualTo(BYTES);
            }
            assertThat(verified.byteSize()).isEqualTo(BYTES.length);
            assertThat(verified.mimeType()).isEqualTo(MIME);
            assertThat(verified.sha256()).isEqualTo(sha256(BYTES));
            assertThat(files()).hasSize(1);
        } finally {
            verified.close();
        }
        assertThat(files()).isEmpty();
    }

    @Test
    void rangeLengthAndMimeMetadataMustMatchBeforeAnyByteIsRead() throws Exception {
        assertMetadataRejected(read(
                new CloseCountingInput(BYTES, 0),
                BYTES.length,
                Optional.of(new ByteRange(0, BYTES.length - 1L)),
                BYTES.length,
                MIME));
        assertMetadataRejected(read(
                new CloseCountingInput(BYTES, 0),
                BYTES.length + 1L,
                Optional.empty(),
                BYTES.length + 1L,
                MIME));
        assertMetadataRejected(read(
                new CloseCountingInput(BYTES, 0),
                BYTES.length,
                Optional.empty(),
                BYTES.length,
                "application/octet-stream"));
    }

    @Test
    void earlyEofTrailingByteAndWrongDigestAllFailClosedAndDeleteTemporaryCopy()
            throws Exception {
        assertContentRejected(new CloseCountingInput(
                java.util.Arrays.copyOf(BYTES, BYTES.length - 1), 0), sha256(BYTES));
        assertContentRejected(new CloseCountingInput(
                java.util.Arrays.copyOf(BYTES, BYTES.length + 1), 0), sha256(BYTES));
        assertContentRejected(new CloseCountingInput(BYTES, 0), "f".repeat(64));
    }

    @Test
    void hostileZeroLengthReadsCannotHangOrEscapeTheExactByteBudget() throws Exception {
        InfiniteZeroReadInput input = new InfiniteZeroReadInput();
        FakeStorage storage = new FakeStorage(read(
                input, BYTES.length, Optional.empty(), BYTES.length, MIME));
        StorageObjectVerifier verifier = new StorageObjectVerifier(temporaryDirectory);

        assertFixedFailure(() -> verifier.verify(
                storage, KEY, MIME, BYTES.length, sha256(BYTES)));

        assertThat(input.bulkReads).isLessThanOrEqualTo(BYTES.length + 1);
        assertThat(input.singleReads).isLessThanOrEqualTo(BYTES.length + 1);
        assertThat(input.closeCalls).isOne();
        assertThat(files()).isEmpty();
    }

    @Test
    void storageReadCloseFailureIsSanitizedAndTemporaryCopyIsDeleted() throws Exception {
        CloseCountingInput input = new CloseCountingInput(BYTES, 0, true);
        FakeStorage storage = new FakeStorage(read(
                input, BYTES.length, Optional.empty(), BYTES.length, MIME));
        StorageObjectVerifier verifier = new StorageObjectVerifier(temporaryDirectory);

        assertFixedFailure(() -> verifier.verify(
                storage, KEY, MIME, BYTES.length, sha256(BYTES)));

        assertThat(input.closeCalls).isOne();
        assertThat(files()).isEmpty();
    }

    private void assertMetadataRejected(StorageRead read) throws IOException {
        CloseCountingInput input = (CloseCountingInput) read.inputStream();
        FakeStorage storage = new FakeStorage(read);
        StorageObjectVerifier verifier = new StorageObjectVerifier(temporaryDirectory);

        assertFixedFailure(() -> verifier.verify(
                storage, KEY, MIME, BYTES.length, sha256(BYTES)));

        assertThat(input.bulkReads).isZero();
        assertThat(input.singleReads).isZero();
        assertThat(input.closeCalls).isOne();
        assertThat(files()).isEmpty();
    }

    private void assertContentRejected(CloseCountingInput input, String expectedSha)
            throws IOException {
        FakeStorage storage = new FakeStorage(read(
                input, BYTES.length, Optional.empty(), BYTES.length, MIME));
        StorageObjectVerifier verifier = new StorageObjectVerifier(temporaryDirectory);

        assertFixedFailure(() -> verifier.verify(
                storage, KEY, MIME, BYTES.length, expectedSha));

        assertThat(input.closeCalls).isOne();
        assertThat(files()).isEmpty();
    }

    private static void assertFixedFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_FINALIZATION_FAILED")
                .hasNoCause()
                .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
    }

    private static StorageRead read(
            InputStream input,
            long totalLength,
            Optional<ByteRange> range,
            long contentLength,
            String mime) {
        return new StorageRead(
                input, totalLength, range, contentLength, mime, "test-etag");
    }

    private java.util.List<Path> files() throws IOException {
        try (var paths = Files.list(temporaryDirectory)) {
            return paths.toList();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class FakeStorage implements StorageService {
        private final StorageRead read;
        private int openCalls;

        private FakeStorage(StorageRead read) {
            this.read = read;
        }

        @Override
        public StorageProvider provider() {
            return StorageProvider.LOCAL;
        }

        @Override
        public StorageLocation location() {
            return new StorageLocation(StorageProvider.LOCAL, null, null);
        }

        @Override
        public StorageRead open(String objectKey, Optional<ByteRange> range) {
            openCalls++;
            assertThat(objectKey).isEqualTo(KEY);
            assertThat(range).isEmpty();
            return read;
        }

        @Override
        public StoredObject put(
                String objectKey, InputStream input, long contentLength, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI signedGet(String objectKey, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copy(String sourceKey, String targetKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            throw new UnsupportedOperationException();
        }
    }

    private static class CloseCountingInput extends InputStream {
        private final ByteArrayInputStream delegate;
        private final int zeroReadLimit;
        private final boolean closeFails;
        private int zeroReads;
        private int bulkReads;
        private int singleReads;
        private int closeCalls;

        private CloseCountingInput(byte[] bytes, int zeroReadLimit) {
            this(bytes, zeroReadLimit, false);
        }

        private CloseCountingInput(byte[] bytes, int zeroReadLimit, boolean closeFails) {
            this.delegate = new ByteArrayInputStream(bytes);
            this.zeroReadLimit = zeroReadLimit;
            this.closeFails = closeFails;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            bulkReads++;
            if (zeroReads < zeroReadLimit) {
                zeroReads++;
                return 0;
            }
            return delegate.read(bytes, offset, length);
        }

        @Override
        public int read() {
            singleReads++;
            return delegate.read();
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            if (closeFails) {
                throw new IOException("private close detail");
            }
        }
    }

    private static final class InfiniteZeroReadInput extends InputStream {
        private int bulkReads;
        private int singleReads;
        private int closeCalls;

        @Override
        public int read(byte[] bytes, int offset, int length) {
            bulkReads++;
            return 0;
        }

        @Override
        public int read() {
            singleReads++;
            return 0;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
