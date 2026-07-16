package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public final class LocalStorageService implements StorageService, AutoCloseable {
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final String OBJECT_EXISTS = "STORAGE_OBJECT_ALREADY_EXISTS";
    private static final String UNSAFE_PATH = "LOCAL_UNSAFE_PATH";
    private static final String LENGTH_MISMATCH = "LOCAL_CONTENT_LENGTH_MISMATCH";
    private static final StorageLocation LOCATION =
            new StorageLocation(StorageProvider.LOCAL, null, null);
    private final Path root;
    private final LocalStorageAccessPolicy accessPolicy;
    private final OperationObserver observer;

    public LocalStorageService(LocalStorageProperties properties) {
        this(properties, OperationObserver.NOOP);
    }

    LocalStorageService(LocalStorageProperties properties, OperationObserver observer) {
        if (properties == null) {
            throw new IllegalArgumentException("Local storage properties are required");
        }
        if (observer == null) {
            throw new IllegalArgumentException("Local storage operation observer is required");
        }
        this.observer = observer;
        this.root = properties.root().toAbsolutePath().normalize();
        this.accessPolicy = LocalStorageAccessPolicy.initialize(root);
    }

    @Override
    public StorageProvider provider() {
        return LOCATION.provider();
    }

    @Override
    public StorageLocation location() {
        return LOCATION;
    }

    @Override
    public void close() throws IOException {
        accessPolicy.close();
    }

    @Override
    public StoredObject put(
            String objectKey, InputStream input, long contentLength, String contentType) {
        if (input == null) {
            throw new IllegalArgumentException("Storage input is required");
        }
        LocalPublication publication = null;
        try {
            try (input) {
                ObjectKey key = ObjectKey.parse(objectKey);
                StorageObjectContract.validateContentLength(contentLength);
                String normalizedContentType =
                        StorageObjectContract.normalizeContentType(key, contentType);
                publication = prepare(
                        key, input, contentLength, normalizedContentType, "LOCAL_WRITE_FAILED");
            }
            return publish(publication);
        } catch (IllegalArgumentException | StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException("LOCAL_WRITE_FAILED", exception);
        } finally {
            if (publication != null) {
                publication.cleanupFailure();
            }
        }
    }

    @Override
    public StorageRead open(String objectKey, Optional<ByteRange> requestedRange) {
        ObjectKey key = ObjectKey.parse(objectKey);
        if (requestedRange == null) {
            throw new IllegalArgumentException("Storage range is required");
        }

        SeekableByteChannel channel = null;
        try {
            Path target = resolveTarget(key, false);
            BasicFileAttributes before = requireRegularFile(target);
            verifyStoredFile(target);
            requireValidStoredLength(before.size());
            validateRequestedRange(requestedRange, before.size());

            channel = openReadChannel(target);
            BasicFileAttributes after = requireRegularFile(target);
            requireSameIdentity(before, after);
            verifyStoredFile(target);
            long totalLength = channel.size();
            if (totalLength != after.size()) {
                throw unsafePath();
            }
            requireValidStoredLength(totalLength);
            validateRequestedRange(requestedRange, totalLength);

            String etag = digest(channel, totalLength);
            ByteRange servedRange = requestedRange.orElse(null);
            long start = servedRange == null ? 0 : servedRange.startInclusive();
            long responseLength = servedRange == null ? totalLength : servedRange.length();
            channel.position(start);
            BoundedInputStream input = new BoundedInputStream(
                    Channels.newInputStream(channel), responseLength);
            channel = null;
            return new StorageRead(
                    input,
                    totalLength,
                    requestedRange,
                    responseLength,
                    StorageObjectContract.contentType(key),
                    etag);
        } catch (IllegalArgumentException
                | StorageException
                | StorageRangeNotSatisfiableException exception) {
            closeQuietly(channel);
            throw exception;
        } catch (IOException exception) {
            closeQuietly(channel);
            throw new StorageException("LOCAL_READ_FAILED", exception);
        }
    }

    @Override
    public URI signedGet(String objectKey, Duration ttl) {
        ObjectKey.parse(objectKey);
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Invalid signed URL TTL");
        }
        throw new UnsupportedOperationException("Local storage is streamed by the application");
    }

    @Override
    public boolean exists(String objectKey) {
        ObjectKey key = ObjectKey.parse(objectKey);
        try {
            Path target = resolveTarget(key, false);
            BasicFileAttributes attributes;
            try {
                attributes = readAttributes(target);
            } catch (NoSuchFileException exception) {
                return false;
            }
            if (!isRealRegularFile(attributes)) {
                throw unsafePath();
            }
            verifyStoredFile(target);
            requireValidStoredLength(attributes.size());
            verifyRootIdentity();
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException("LOCAL_EXISTS_FAILED", exception);
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        ObjectKey source = ObjectKey.parse(sourceKey);
        ObjectKey target = ObjectKey.parse(targetKey);
        String sourceContentType = StorageObjectContract.contentType(source);
        if (!sourceContentType.equals(StorageObjectContract.contentType(target))) {
            throw new IllegalArgumentException("Invalid storage content type");
        }
        SeekableByteChannel channel = null;
        LocalPublication publication = null;
        try {
            Path sourcePath = resolveTarget(source, false);
            BasicFileAttributes before = requireRegularFile(sourcePath);
            verifyStoredFile(sourcePath);
            requireValidStoredLength(before.size());
            channel = openReadChannel(sourcePath);
            BasicFileAttributes after = requireRegularFile(sourcePath);
            requireSameIdentity(before, after);
            verifyStoredFile(sourcePath);
            long contentLength = channel.size();
            if (contentLength != after.size()) {
                throw unsafePath();
            }
            requireValidStoredLength(contentLength);
            InputStream input = Channels.newInputStream(channel);
            try (input) {
                publication = prepare(
                        target, input, contentLength, sourceContentType, "LOCAL_COPY_FAILED");
            }
            observer.copySourceClosed(channel, publication.target());
            channel = null;
            publish(publication);
        } catch (StorageException exception) {
            if (OBJECT_EXISTS.equals(exception.code()) || UNSAFE_PATH.equals(exception.code())) {
                throw exception;
            }
            throw new StorageException("LOCAL_COPY_FAILED", exception);
        } catch (IOException exception) {
            throw new StorageException("LOCAL_COPY_FAILED", exception);
        } finally {
            closeQuietly(channel);
            if (publication != null) {
                publication.cleanupFailure();
            }
        }
    }

    @Override
    public void delete(String objectKey) {
        ObjectKey key = ObjectKey.parse(objectKey);
        try {
            Path target = resolveTarget(key, false);
            BasicFileAttributes attributes;
            try {
                attributes = readAttributes(target);
            } catch (NoSuchFileException exception) {
                return;
            }
            if (!isRealRegularFile(attributes)) {
                throw unsafePath();
            }
            verifyRootIdentity();
            BasicFileAttributes immediatelyBeforeDelete = requireRegularFile(target);
            requireSameIdentity(attributes, immediatelyBeforeDelete);
            verifyStoredFile(target);
            Files.delete(target);
            accessPolicy.syncDirectory(target.getParent());
        } catch (NoSuchFileException exception) {
            // Idempotent deletion: an object or safe ancestor disappeared before deletion.
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException("LOCAL_DELETE_FAILED", exception);
        }
    }

    private LocalPublication prepare(
            ObjectKey key,
            InputStream input,
            long contentLength,
            String contentType,
            String failureCode) throws IOException {
        StorageObjectContract.validateContentLength(contentLength);
        Path target = resolveTarget(key, true);
        rejectExistingTarget(target);
        Path temporary = LocalReservedNames.newPart(target.getParent());
        LocalFileIdentity identity = null;
        BasicFileAttributes initialIdentity = null;
        boolean prepared = false;
        try {
            MessageDigest messageDigest = sha256();
            try (FileChannel output = createTemporaryFile(temporary)) {
                initialIdentity = requireRegularFile(temporary);
                Object fileKey = observer.publicationFileKey(temporary, initialIdentity);
                identity = LocalFileIdentity.capture(
                        temporary, fileKey, observer::createPublicationIdentityGuard);
                copyExactly(input, output, messageDigest, contentLength);
                output.force(true);
                if (output.size() != contentLength) {
                    throw new StorageException(LENGTH_MISMATCH);
                }
            }
            BasicFileAttributes completedIdentity = requireRegularFile(temporary);
            requireSameIdentity(initialIdentity, completedIdentity);
            if (completedIdentity.size() != contentLength) {
                throw new StorageException(LENGTH_MISMATCH);
            }
            verifyFilePermissions(temporary);
            observer.temporaryReady(temporary, target);
            identity.require(temporary);

            LocalPublication publication = new LocalPublication(
                    key,
                    target,
                    temporary,
                    identity,
                    contentLength,
                    contentType,
                    HexFormat.of().formatHex(messageDigest.digest()),
                    failureCode);
            prepared = true;
            return publication;
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException(failureCode, exception);
        } finally {
            if (!prepared) {
                if (identity != null) {
                    identity.cleanupCreatedName(temporary);
                } else {
                    cleanupUnpublishedCreation(temporary, initialIdentity);
                }
            }
        }
    }

    private StoredObject publish(LocalPublication publication) throws IOException {
        try {
            Path checkedTarget = resolveTarget(publication.key(), false);
            if (!checkedTarget.equals(publication.target())) {
                throw unsafePath();
            }
            publication.identity().require(publication.temporary());
            rejectExistingTarget(publication.target());
            try {
                Files.createLink(publication.target(), publication.temporary());
            } catch (FileAlreadyExistsException exception) {
                throw new StorageException(OBJECT_EXISTS, exception);
            }
            publication.markTargetLinked();
            observer.targetLinked(publication.temporary(), publication.target());
            publication.identity().require(publication.target());
            if (!Files.isSameFile(publication.temporary(), publication.target())) {
                throw unsafePath();
            }
            verifyFilePermissions(publication.target());
            publication.deleteTemporaryAfterValidation();
            observer.beforePublicationCommit(publication.target());
            accessPolicy.syncDirectory(publication.target().getParent());
            publication.markCompleted();
            publication.finishIdentityGuard();

            return new StoredObject(
                    StorageProvider.LOCAL,
                    null,
                    null,
                    publication.key().value(),
                    publication.contentLength(),
                    publication.contentType(),
                    publication.etag());
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException(publication.failureCode(), exception);
        }
    }

    private Path resolveTarget(ObjectKey key, boolean createParents) throws IOException {
        verifyRootIdentity();
        String[] segments = key.segments();
        Path parent = root;
        for (int index = 0; index < segments.length - 1; index++) {
            Path descendant = parent.resolve(segments[index]);
            BasicFileAttributes attributes;
            try {
                attributes = readAttributes(descendant);
            } catch (NoSuchFileException exception) {
                if (!createParents) {
                    throw exception;
                }
                createDirectory(descendant);
                attributes = readAttributes(descendant);
            }
            if (!isRealDirectory(attributes)) {
                throw unsafePath();
            }
            accessPolicy.verifyDirectory(descendant);
            parent = descendant;
        }
        verifyRootIdentity();
        return parent.resolve(segments[segments.length - 1]);
    }

    private void createDirectory(Path directory) throws IOException {
        accessPolicy.createDirectory(directory);
    }

    private FileChannel createTemporaryFile(Path temporary) throws IOException {
        return accessPolicy.createFile(temporary);
    }

    private void copyExactly(
            InputStream input,
            FileChannel output,
            MessageDigest digest,
            long contentLength) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long remaining = contentLength;
        while (remaining > 0) {
            int requested = (int) Math.min(buffer.length, remaining);
            int count = input.read(buffer, 0, requested);
            if (count < 0) {
                throw new StorageException(LENGTH_MISMATCH);
            }
            if (count == 0) {
                int single = input.read();
                if (single < 0) {
                    throw new StorageException(LENGTH_MISMATCH);
                }
                buffer[0] = (byte) single;
                count = 1;
            }
            digest.update(buffer, 0, count);
            ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
            while (bytes.hasRemaining()) {
                output.write(bytes);
            }
            remaining -= count;
        }
        if (input.read() >= 0) {
            throw new StorageException(LENGTH_MISMATCH);
        }
    }

    private static String digest(SeekableByteChannel channel, long expectedLength) throws IOException {
        MessageDigest digest = sha256();
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE);
        long read = 0;
        while (true) {
            int count = channel.read(buffer);
            if (count < 0) {
                break;
            }
            if (count == 0) {
                continue;
            }
            read += count;
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        if (read != expectedLength || channel.size() != expectedLength) {
            throw unsafePath();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void rejectExistingTarget(Path target) throws IOException {
        try {
            BasicFileAttributes attributes = readAttributes(target);
            if (!isRealRegularFile(attributes)) {
                throw unsafePath();
            }
            throw new StorageException(OBJECT_EXISTS);
        } catch (NoSuchFileException exception) {
            // The createLink publication below remains the authoritative no-replace operation.
        }
    }

    private void verifyRootIdentity() throws IOException {
        accessPolicy.verifyRoot();
    }

    private static BasicFileAttributes requireRegularFile(Path path) throws IOException {
        BasicFileAttributes attributes = readAttributes(path);
        if (!isRealRegularFile(attributes)) {
            throw unsafePath();
        }
        return attributes;
    }

    private static boolean isRealDirectory(BasicFileAttributes attributes) {
        return attributes.isDirectory()
                && !attributes.isSymbolicLink()
                && !attributes.isOther();
    }

    private static boolean isRealRegularFile(BasicFileAttributes attributes) {
        return attributes.isRegularFile()
                && !attributes.isSymbolicLink()
                && !attributes.isOther();
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
    }

    private static void requireSameIdentity(
            BasicFileAttributes expected, BasicFileAttributes actual) {
        Object expectedKey = expected.fileKey();
        Object actualKey = actual.fileKey();
        if (expectedKey != null || actualKey != null) {
            if (!java.util.Objects.equals(expectedKey, actualKey)) {
                throw unsafePath();
            }
        }
    }

    private void verifyFilePermissions(Path path) throws IOException {
        accessPolicy.verifyFile(path);
    }

    private void verifyStoredFile(Path path) throws IOException {
        accessPolicy.verifyFile(path);
    }

    private static SeekableByteChannel openReadChannel(Path path) throws IOException {
        return Files.newByteChannel(path, Set.of(READ, NOFOLLOW_LINKS));
    }

    private static void validateRequestedRange(Optional<ByteRange> range, long totalLength) {
        if (totalLength <= 0) {
            throw unsafePath();
        }
        StorageObjectContract.validateRange(range, totalLength);
    }

    private static void requireValidStoredLength(long contentLength) {
        try {
            StorageObjectContract.validateContentLength(contentLength);
        } catch (IllegalArgumentException exception) {
            throw unsafePath();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static StorageException unsafePath() {
        return new StorageException(UNSAFE_PATH);
    }

    private static void closeQuietly(SeekableByteChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Preserve the fixed primary storage failure.
        }
    }

    private static void cleanupUnpublishedCreation(
            Path temporary, BasicFileAttributes initialIdentity) {
        if (initialIdentity == null) {
            return;
        }
        try {
            BasicFileAttributes currentIdentity = requireRegularFile(temporary);
            requireSameIdentity(initialIdentity, currentIdentity);
            Files.deleteIfExists(temporary);
        } catch (IOException | StorageException ignored) {
            // The secure parent excludes untrusted replacement; preserve any unverified name.
        }
    }

    interface OperationObserver {
        OperationObserver NOOP = new OperationObserver() {};

        default void temporaryReady(Path temporary, Path target) throws IOException {}

        default void targetLinked(Path temporary, Path target) throws IOException {}

        default void copySourceClosed(SeekableByteChannel source, Path target) throws IOException {}

        default Object publicationFileKey(Path path, BasicFileAttributes attributes)
                throws IOException {
            return attributes.fileKey();
        }

        default void createPublicationIdentityGuard(Path guard, Path source) throws IOException {
            Files.createLink(guard, source);
        }

        default void beforePublicationCommit(Path target) throws IOException {}
    }

}
