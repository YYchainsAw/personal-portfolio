package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public final class TencentCosStorageService implements StorageService, AutoCloseable {
    private static final Duration MAXIMUM_SIGNED_GET_TTL = Duration.ofMinutes(5);
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final String LENGTH_MISMATCH = "COS_CONTENT_LENGTH_MISMATCH";
    private final CosClientPort client;
    private final TencentCosProperties properties;
    private final StorageLocation location;
    private final Clock clock;
    private final Path stagingRoot;
    private final LocalStorageAccessPolicy stagingAccess;
    private final StagingObserver stagingObserver;

    TencentCosStorageService(
            CosClientPort client,
            TencentCosProperties properties,
            Clock clock,
            Path stagingRoot) {
        this(client, properties, clock, stagingRoot, StagingObserver.NOOP);
    }

    TencentCosStorageService(
            CosClientPort client,
            TencentCosProperties properties,
            Clock clock,
            Path stagingRoot,
            StagingObserver stagingObserver) {
        if (client == null
                || properties == null
                || clock == null
                || stagingRoot == null
                || stagingObserver == null) {
            throw new IllegalArgumentException("COS storage configuration is required");
        }
        this.client = client;
        this.properties = properties;
        this.location = new StorageLocation(
                StorageProvider.TENCENT_COS,
                properties.bucket(),
                properties.region());
        this.clock = clock;
        try {
            this.stagingRoot = stagingRoot.toAbsolutePath().normalize();
            this.stagingAccess = LocalStorageAccessPolicy.initialize(this.stagingRoot);
        } catch (RuntimeException exception) {
            throw new StorageException("COS_WRITE_FAILED");
        }
        this.stagingObserver = stagingObserver;
    }

    @Override
    public StorageProvider provider() {
        return location.provider();
    }

    @Override
    public StorageLocation location() {
        return location;
    }

    @Override
    public StoredObject put(
            String objectKey, InputStream input, long contentLength, String contentType) {
        if (input == null) {
            throw new IllegalArgumentException("Storage input is required");
        }
        ObjectKey key;
        String normalizedContentType;
        try {
            key = ObjectKey.parse(objectKey);
            StorageObjectContract.validateContentLength(contentLength);
            normalizedContentType =
                    StorageObjectContract.normalizeContentType(key, contentType);
        } catch (IllegalArgumentException exception) {
            closeCallerQuietly(input);
            throw new IllegalArgumentException(exception.getMessage());
        }

        Path staged = null;
        try {
            try (input) {
                staged = stage(input, contentLength);
            }
        } catch (StorageException exception) {
            cleanupUnpublished(staged);
            throw sanitizedStagingFailure(exception);
        } catch (IOException | RuntimeException exception) {
            cleanupUnpublished(staged);
            throw new StorageException("COS_WRITE_FAILED");
        }

        StoredObject result;
        boolean published = false;
        try {
            try (FileChannel channel = FileChannel.open(
                            staged, Set.of(READ, NOFOLLOW_LINKS));
                    InputStream stagedInput = new BoundedInputStream(
                            Channels.newInputStream(channel), contentLength)) {
                try {
                    result = client.putCreateOnly(
                            properties.bucket(),
                            key.value(),
                            stagedInput,
                            contentLength,
                            normalizedContentType);
                    published = true;
                } catch (StorageException exception) {
                    if ("STORAGE_OBJECT_ALREADY_EXISTS".equals(exception.code())) {
                        throw exception;
                    }
                    throw new StorageException("COS_WRITE_FAILED");
                } catch (RuntimeException exception) {
                    throw new StorageException("COS_WRITE_FAILED");
                }
            }
        } catch (StorageException exception) {
            cleanupUnpublished(staged);
            if ("STORAGE_OBJECT_ALREADY_EXISTS".equals(exception.code())) {
                throw new StorageException("STORAGE_OBJECT_ALREADY_EXISTS");
            }
            throw new StorageException("COS_WRITE_FAILED");
        } catch (IOException exception) {
            if (published) {
                cleanupPublishedQuietly(staged);
                throw new StorageException("COS_STAGING_CLEANUP_FAILED");
            }
            cleanupUnpublished(staged);
            throw new StorageException("COS_WRITE_FAILED");
        }

        StorageException responseFailure = validatePutResult(
                result, key, contentLength, normalizedContentType);
        try {
            cleanupPublished(staged);
        } catch (IOException | RuntimeException exception) {
            throw new StorageException("COS_STAGING_CLEANUP_FAILED");
        }
        if (responseFailure != null) {
            throw responseFailure;
        }
        return result;
    }

    @Override
    public StorageRead open(String objectKey, Optional<ByteRange> range) {
        ObjectKey key = ObjectKey.parse(objectKey);
        if (range == null) {
            throw new IllegalArgumentException("Storage range is required");
        }
        StorageRead read;
        try {
            read = client.open(properties.bucket(), key.value(), range);
        } catch (StorageRangeNotSatisfiableException exception) {
            throw new StorageRangeNotSatisfiableException(exception.totalLength());
        } catch (RuntimeException exception) {
            throw new StorageException("COS_READ_FAILED");
        }
        try {
            if (read == null
                    || !range.equals(read.range())
                    || !StorageObjectContract.normalizeContentType(key, read.contentType())
                            .equals(read.contentType())) {
                throw new StorageException("COS_INVALID_RESPONSE");
            }
            return read;
        } catch (IllegalArgumentException exception) {
            closeQuietly(read);
            throw new StorageException("COS_INVALID_RESPONSE");
        } catch (StorageException exception) {
            closeQuietly(read);
            throw exception;
        }
    }

    @Override
    public URI signedGet(String objectKey, Duration ttl) {
        ObjectKey key = ObjectKey.parse(objectKey);
        if (ttl == null
                || ttl.isZero()
                || ttl.isNegative()
                || ttl.compareTo(MAXIMUM_SIGNED_GET_TTL) > 0) {
            throw new IllegalArgumentException(
                    "COS signed GET TTL must be within five minutes");
        }
        Instant expiresAt;
        try {
            expiresAt = clock.instant().plus(ttl);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new StorageException("COS_SIGN_FAILED");
        }
        URI signed;
        try {
            signed = client.signGet(properties.bucket(), key.value(), expiresAt);
        } catch (RuntimeException exception) {
            throw new StorageException("COS_SIGN_FAILED");
        }
        requireOfficialSignedUri(signed, key);
        return signed;
    }

    @Override
    public boolean exists(String objectKey) {
        ObjectKey key = ObjectKey.parse(objectKey);
        try {
            return client.exists(properties.bucket(), key.value());
        } catch (RuntimeException exception) {
            throw new StorageException("COS_EXISTS_FAILED");
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        ObjectKey source = ObjectKey.parse(sourceKey);
        ObjectKey target = ObjectKey.parse(targetKey);
        if (!StorageObjectContract.contentType(source)
                .equals(StorageObjectContract.contentType(target))) {
            throw new IllegalArgumentException("Invalid storage content type");
        }
        try {
            client.copyCreateOnly(properties.bucket(), source.value(), target.value());
        } catch (StorageException exception) {
            if ("STORAGE_OBJECT_ALREADY_EXISTS".equals(exception.code())) {
                throw new StorageException("STORAGE_OBJECT_ALREADY_EXISTS");
            }
            throw new StorageException("COS_COPY_FAILED");
        } catch (RuntimeException exception) {
            throw new StorageException("COS_COPY_FAILED");
        }
    }

    @Override
    public void delete(String objectKey) {
        ObjectKey key = ObjectKey.parse(objectKey);
        try {
            client.delete(properties.bucket(), key.value());
        } catch (RuntimeException exception) {
            throw new StorageException("COS_DELETE_FAILED");
        }
    }

    @Override
    public void close() throws IOException {
        stagingAccess.close();
    }

    private void requireOfficialSignedUri(URI signed, ObjectKey key) {
        String expectedHost = properties.bucket() + ".cos." + properties.region()
                + ".myqcloud.com";
        if (signed == null
                || !"https".equalsIgnoreCase(signed.getScheme())
                || signed.getHost() == null
                || !expectedHost.equalsIgnoreCase(signed.getHost())
                || !expectedHost.equalsIgnoreCase(signed.getRawAuthority())
                || signed.getRawUserInfo() != null
                || signed.getPort() != -1
                || !("/" + key.value()).equals(signed.getRawPath())
                || signed.getRawQuery() == null
                || signed.getRawQuery().isBlank()
                || signed.getRawFragment() != null) {
            throw new StorageException("COS_INVALID_RESPONSE");
        }
    }

    private Path stage(InputStream input, long contentLength) throws IOException {
        stagingAccess.verifyRoot();
        Path staged = LocalReservedNames.newPart(stagingRoot);
        boolean complete = false;
        try {
            try (FileChannel output = stagingAccess.createFile(staged)) {
                copyExactly(input, output, contentLength);
                output.force(true);
                if (output.size() != contentLength) {
                    throw new StorageException(LENGTH_MISMATCH);
                }
            }
            stagingObserver.afterStageClosed(staged);
            stagingAccess.verifyRoot();
            stagingAccess.verifyFile(staged);
            BasicFileAttributes attributes = Files.readAttributes(
                    staged, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()
                    || attributes.isSymbolicLink()
                    || attributes.isOther()
                    || attributes.size() != contentLength) {
                throw new StorageException("COS_WRITE_FAILED");
            }
            stagingAccess.syncDirectory(stagingRoot);
            complete = true;
            return staged;
        } finally {
            if (!complete) {
                cleanupUnpublished(staged);
            }
        }
    }

    private static void copyExactly(
            InputStream input, FileChannel output, long contentLength) throws IOException {
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

    private StorageException validatePutResult(
            StoredObject result,
            ObjectKey key,
            long contentLength,
            String contentType) {
        if (result == null
                || result.provider() != StorageProvider.TENCENT_COS
                || !properties.bucket().equals(result.bucket())
                || !properties.region().equals(result.region())
                || !key.value().equals(result.objectKey())
                || result.contentLength() != contentLength
                || !contentType.equals(result.contentType())) {
            return new StorageException("COS_INVALID_RESPONSE");
        }
        return null;
    }

    private void cleanupPublished(Path staged) throws IOException {
        stagingObserver.beforeCleanup(staged);
        Files.delete(staged);
        stagingAccess.syncDirectory(stagingRoot);
    }

    private void cleanupPublishedQuietly(Path staged) {
        try {
            cleanupPublished(staged);
        } catch (IOException | RuntimeException ignored) {
            // Publication already succeeded; the fixed cleanup failure remains primary.
        }
    }

    private void cleanupUnpublished(Path staged) {
        if (staged == null) {
            return;
        }
        try {
            stagingObserver.beforeCleanup(staged);
            if (Files.deleteIfExists(staged)) {
                stagingAccess.syncDirectory(stagingRoot);
            }
        } catch (IOException | RuntimeException ignored) {
            // Preserve the fixed pre-publication failure.
        }
    }

    private static void closeQuietly(StorageRead read) {
        if (read == null) {
            return;
        }
        try {
            read.close();
        } catch (IOException | RuntimeException ignored) {
            // Preserve the fixed invalid-response failure.
        }
    }

    private static StorageException sanitizedStagingFailure(StorageException failure) {
        if (LENGTH_MISMATCH.equals(failure.code())) {
            return new StorageException(LENGTH_MISMATCH);
        }
        return new StorageException("COS_WRITE_FAILED");
    }

    private static void closeCallerQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException | RuntimeException ignored) {
            // Preserve the fixed validation failure without retaining caller details.
        }
    }

    interface StagingObserver {
        StagingObserver NOOP = new StagingObserver() {
            @Override
            public void afterStageClosed(Path path) {}

            @Override
            public void beforeCleanup(Path path) {}
        };

        void afterStageClosed(Path path) throws IOException;

        void beforeCleanup(Path path) throws IOException;
    }
}
