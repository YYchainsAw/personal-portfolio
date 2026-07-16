package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public final class LocalStorageService implements StorageService {
    private static final long MAX_CONTENT_LENGTH = 5L * 1024 * 1024 * 1024;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final String OCTET_STREAM = MediaType.APPLICATION_OCTET_STREAM_VALUE;
    private static final String OBJECT_EXISTS = "STORAGE_OBJECT_ALREADY_EXISTS";
    private static final String UNSAFE_PATH = "LOCAL_UNSAFE_PATH";
    private static final String LENGTH_MISMATCH = "LOCAL_CONTENT_LENGTH_MISMATCH";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path root;
    private final Object rootIdentity;
    private final FileTime rootCreationTime;
    private final boolean posixAttributes;

    public LocalStorageService(LocalStorageProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Local storage properties are required");
        }
        this.root = properties.root().toAbsolutePath().normalize();
        RootState state = initializeRoot();
        this.rootIdentity = state.identity();
        this.rootCreationTime = state.creationTime();
        this.posixAttributes = state.posixAttributes();
    }

    @Override
    public StorageProvider provider() {
        return StorageProvider.LOCAL;
    }

    @Override
    public StoredObject put(
            String objectKey, InputStream input, long contentLength, String contentType) {
        if (input == null) {
            throw new IllegalArgumentException("Storage input is required");
        }
        try (input) {
            ObjectKey key = ObjectKey.parse(objectKey);
            validateLength(contentLength);
            String normalizedContentType = validateContentType(contentType);
            return publish(key, input, contentLength, normalizedContentType, "LOCAL_WRITE_FAILED");
        } catch (IllegalArgumentException | StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException("LOCAL_WRITE_FAILED", exception);
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
            validateRequestedRange(requestedRange, before.size());

            channel = openReadChannel(target);
            BasicFileAttributes after = requireRegularFile(target);
            requireSameIdentity(before, after);
            long totalLength = channel.size();
            if (totalLength <= 0 || totalLength != after.size()) {
                throw unsafePath();
            }
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
                    contentType(target),
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
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw unsafePath();
            }
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
        SeekableByteChannel channel = null;
        try {
            Path sourcePath = resolveTarget(source, false);
            BasicFileAttributes before = requireRegularFile(sourcePath);
            channel = openReadChannel(sourcePath);
            BasicFileAttributes after = requireRegularFile(sourcePath);
            requireSameIdentity(before, after);
            long contentLength = channel.size();
            if (contentLength <= 0 || contentLength != after.size()) {
                throw unsafePath();
            }
            InputStream input = Channels.newInputStream(channel);
            channel = null;
            try (input) {
                publish(target, input, contentLength, contentType(sourcePath), "LOCAL_COPY_FAILED");
            }
        } catch (StorageException exception) {
            if (OBJECT_EXISTS.equals(exception.code()) || UNSAFE_PATH.equals(exception.code())) {
                throw exception;
            }
            throw new StorageException("LOCAL_COPY_FAILED", exception);
        } catch (IOException exception) {
            throw new StorageException("LOCAL_COPY_FAILED", exception);
        } finally {
            closeQuietly(channel);
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
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw unsafePath();
            }
            verifyRootIdentity();
            BasicFileAttributes immediatelyBeforeDelete = requireRegularFile(target);
            requireSameIdentity(attributes, immediatelyBeforeDelete);
            Files.delete(target);
            syncDirectory(target.getParent());
        } catch (NoSuchFileException exception) {
            // Idempotent deletion: an object or safe ancestor disappeared before deletion.
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException("LOCAL_DELETE_FAILED", exception);
        }
    }

    private StoredObject publish(
            ObjectKey key,
            InputStream input,
            long contentLength,
            String contentType,
            String failureCode) throws IOException {
        Path target = resolveTarget(key, true);
        rejectExistingTarget(target);
        Path temporary = target.resolveSibling(
                target.getFileName() + "." + UUID.randomUUID() + ".part");
        boolean temporaryCreated = false;
        try {
            MessageDigest messageDigest = sha256();
            BasicFileAttributes initialIdentity;
            try (FileChannel output = createTemporaryFile(temporary)) {
                temporaryCreated = true;
                initialIdentity = requireRegularFile(temporary);
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

            Path checkedTarget = resolveTarget(key, false);
            if (!checkedTarget.equals(target)) {
                throw unsafePath();
            }
            rejectExistingTarget(target);
            try {
                Files.createLink(target, temporary);
            } catch (FileAlreadyExistsException exception) {
                throw new StorageException(OBJECT_EXISTS, exception);
            }
            BasicFileAttributes publishedIdentity = requireRegularFile(target);
            requireSameIdentity(completedIdentity, publishedIdentity);
            if (!Files.isSameFile(temporary, target)) {
                throw unsafePath();
            }
            verifyFilePermissions(target);
            Files.delete(temporary);
            temporaryCreated = false;
            syncDirectory(target.getParent());

            return new StoredObject(
                    StorageProvider.LOCAL,
                    null,
                    null,
                    key.value(),
                    contentLength,
                    contentType,
                    HexFormat.of().formatHex(messageDigest.digest()));
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException(failureCode, exception);
        } finally {
            if (temporaryCreated) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The primary fixed error is retained; normal filesystems remove this alias.
                }
            }
        }
    }

    private RootState initializeRoot() {
        try {
            if (Files.exists(root, NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = readAttributes(root);
                if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                    throw unsafePath();
                }
            } else {
                createRootDirectories();
            }
            BasicFileAttributes attributes = readAttributes(root);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw unsafePath();
            }
            boolean supportsPosix = Files.getFileStore(root)
                    .supportsFileAttributeView(PosixFileAttributeView.class);
            if (supportsPosix) {
                setPermissions(root, DIRECTORY_PERMISSIONS);
                if (!Files.getPosixFilePermissions(root, NOFOLLOW_LINKS)
                        .equals(DIRECTORY_PERMISSIONS)) {
                    throw unsafePath();
                }
            }
            return new RootState(attributes.fileKey(), attributes.creationTime(), supportsPosix);
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException("LOCAL_INITIALIZATION_FAILED", exception);
        }
    }

    private void createRootDirectories() throws IOException {
        try {
            Files.createDirectories(root, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        } catch (UnsupportedOperationException exception) {
            Files.createDirectories(root);
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
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw unsafePath();
            }
            if (posixAttributes) {
                setPermissions(descendant, DIRECTORY_PERMISSIONS);
                if (!Files.getPosixFilePermissions(descendant, NOFOLLOW_LINKS)
                        .equals(DIRECTORY_PERMISSIONS)) {
                    throw unsafePath();
                }
            }
            parent = descendant;
        }
        verifyRootIdentity();
        return parent.resolve(segments[segments.length - 1]);
    }

    private void createDirectory(Path directory) throws IOException {
        try {
            if (posixAttributes) {
                Files.createDirectory(
                        directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            } else {
                Files.createDirectory(directory);
            }
            syncDirectory(directory.getParent());
        } catch (FileAlreadyExistsException exception) {
            BasicFileAttributes attributes = readAttributes(directory);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw unsafePath();
            }
        }
    }

    private FileChannel createTemporaryFile(Path temporary) throws IOException {
        Set<OpenOption> options = Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS);
        FileChannel channel;
        if (posixAttributes) {
            FileAttribute<Set<PosixFilePermission>> permissions =
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS);
            channel = FileChannel.open(temporary, options, permissions);
        } else {
            channel = FileChannel.open(temporary, options);
        }
        return channel;
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
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw unsafePath();
            }
            throw new StorageException(OBJECT_EXISTS);
        } catch (NoSuchFileException exception) {
            // The createLink publication below remains the authoritative no-replace operation.
        }
    }

    private void verifyRootIdentity() throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = readAttributes(root);
        } catch (NoSuchFileException exception) {
            throw unsafePath();
        }
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw unsafePath();
        }
        if (rootIdentity != null && !rootIdentity.equals(attributes.fileKey())) {
            throw unsafePath();
        }
        if (rootIdentity == null && !rootCreationTime.equals(attributes.creationTime())) {
            throw unsafePath();
        }
    }

    private static BasicFileAttributes requireRegularFile(Path path) throws IOException {
        BasicFileAttributes attributes = readAttributes(path);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw unsafePath();
        }
        return attributes;
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
        } else if (!expected.creationTime().equals(actual.creationTime())) {
            throw unsafePath();
        }
    }

    private void verifyFilePermissions(Path path) throws IOException {
        if (!posixAttributes) {
            return;
        }
        setPermissions(path, FILE_PERMISSIONS);
        if (!Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).equals(FILE_PERMISSIONS)) {
            throw unsafePath();
        }
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, NOFOLLOW_LINKS);
        if (view == null) {
            throw unsafePath();
        }
        view.setPermissions(permissions);
    }

    private static SeekableByteChannel openReadChannel(Path path) throws IOException {
        return Files.newByteChannel(path, Set.of(READ, NOFOLLOW_LINKS));
    }

    private static void validateRequestedRange(Optional<ByteRange> range, long totalLength) {
        if (totalLength <= 0) {
            throw unsafePath();
        }
        if (range.isEmpty()) {
            return;
        }
        ByteRange requested = range.orElseThrow();
        if (requested.startInclusive() >= totalLength || requested.endInclusive() >= totalLength) {
            throw new StorageRangeNotSatisfiableException(totalLength);
        }
    }

    private static void validateLength(long contentLength) {
        if (contentLength <= 0 || contentLength > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("Invalid storage content length");
        }
    }

    private static String validateContentType(String contentType) {
        if (contentType == null
                || contentType.isBlank()
                || contentType.indexOf('\r') >= 0
                || contentType.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid storage content type");
        }
        return contentType.trim();
    }

    private static String contentType(Path path) {
        return MediaTypeFactory.getMediaType(path.getFileName().toString())
                .map(MediaType::toString)
                .orElse(OCTET_STREAM);
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

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, READ)) {
            channel.force(true);
        } catch (AccessDeniedException | UnsupportedOperationException exception) {
            // Directory fsync is unavailable on some providers, including standard Windows NTFS.
        }
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

    private record RootState(Object identity, FileTime creationTime, boolean posixAttributes) {}
}
