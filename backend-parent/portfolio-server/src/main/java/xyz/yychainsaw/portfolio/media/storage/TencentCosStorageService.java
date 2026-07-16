package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public final class TencentCosStorageService implements StorageService, AutoCloseable {
    static final int SCRATCH_CLEANUP_DELETE_LIMIT = 128;
    private static final int DEFAULT_SCRATCH_SCAN_ENTRY_LIMIT = 100_000;
    private static final int MAXIMUM_SCRATCH_SCAN_ENTRY_LIMIT = 1_000_000;
    private static final Duration MAXIMUM_SIGNED_GET_TTL = Duration.ofMinutes(5);
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final String LENGTH_MISMATCH = "COS_CONTENT_LENGTH_MISMATCH";
    private static final String STAGING_CLEANUP_FAILED = "COS_STAGING_CLEANUP_FAILED";
    private static final String CLEANUP_IDENTITY_GUARD_PREFIX = "@cleanup-identity-";
    private static final String CLEANUP_VERIFICATION_GUARD_PREFIX =
            "@cleanup-verification-";
    private static final Logger LOGGER =
            LoggerFactory.getLogger(TencentCosStorageService.class);
    private static final Pattern CANONICAL_SCRATCH_FILE = Pattern.compile(
            "@part-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Comparator<ScratchCandidate> OLDEST_SCRATCH_FIRST =
            Comparator.comparing(ScratchCandidate::modified)
                    .thenComparing(ScratchCandidate::stableKey);
    private static final Comparator<ScratchCandidate> NEWEST_SCRATCH_FIRST =
            OLDEST_SCRATCH_FIRST.reversed();
    private final CosClientPort client;
    private final TencentCosProperties properties;
    private final StorageLocation location;
    private final Clock clock;
    private final Path stagingRoot;
    private final LocalStorageAccessPolicy stagingAccess;
    private final StagingObserver stagingObserver;
    private final int maxScratchScanEntries;

    TencentCosStorageService(
            CosClientPort client,
            TencentCosProperties properties,
            Clock clock,
            Path stagingRoot) {
        this(
                client,
                properties,
                clock,
                stagingRoot,
                StagingObserver.NOOP,
                DEFAULT_SCRATCH_SCAN_ENTRY_LIMIT);
    }

    TencentCosStorageService(
            CosClientPort client,
            TencentCosProperties properties,
            Clock clock,
            Path stagingRoot,
            StagingObserver stagingObserver) {
        this(
                client,
                properties,
                clock,
                stagingRoot,
                stagingObserver,
                DEFAULT_SCRATCH_SCAN_ENTRY_LIMIT);
    }

    TencentCosStorageService(
            CosClientPort client,
            TencentCosProperties properties,
            Clock clock,
            Path stagingRoot,
            StagingObserver stagingObserver,
            int maxScratchScanEntries) {
        if (client == null
                || properties == null
                || clock == null
                || stagingRoot == null
                || stagingObserver == null) {
            throw new IllegalArgumentException("COS storage configuration is required");
        }
        requireValidScanLimit(maxScratchScanEntries);
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
        this.maxScratchScanEntries = maxScratchScanEntries;
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

    public synchronized StagingCleanupResult cleanupScratch(Instant cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("COS scratch cleanup cutoff is required");
        }
        long started = System.nanoTime();
        long scanned = 0;
        long candidateCount = 0;
        PriorityQueue<ScratchCandidate> selected =
                new PriorityQueue<>(NEWEST_SCRATCH_FIRST);
        int deleted;
        try {
            requireCleanupNotInterrupted();
            stagingAccess.verifyRoot();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(stagingRoot)) {
                for (Path entry : entries) {
                    requireCleanupNotInterrupted();
                    scanned++;
                    if (scanned > maxScratchScanEntries) {
                        throw new StorageException(STAGING_CLEANUP_FAILED);
                    }
                    String fileName = fileName(entry);
                    boolean recoveryGuard = isCleanupRecoveryEntry(fileName);
                    if (!recoveryGuard
                            && !CANONICAL_SCRATCH_FILE.matcher(fileName).matches()) {
                        continue;
                    }
                    BasicFileAttributes attributes;
                    try {
                        attributes = Files.readAttributes(
                                entry, BasicFileAttributes.class, NOFOLLOW_LINKS);
                    } catch (NoSuchFileException concurrentRemoval) {
                        continue;
                    }
                    requireSafeScratchFile(entry, attributes);
                    if (recoveryGuard) {
                        requireRecoveryGuardRelationship(entry);
                    }
                    if (attributes.lastModifiedTime().toInstant().isAfter(cutoff)) {
                        continue;
                    }
                    candidateCount++;
                    ScratchCandidate candidate = new ScratchCandidate(
                            entry.toAbsolutePath().normalize(),
                            fileName,
                            recoveryGuard,
                            attributes.fileKey(),
                            attributes.size(),
                            attributes.creationTime().toInstant(),
                            attributes.lastModifiedTime().toInstant());
                    keepOldestScratch(selected, candidate);
                }
            }

            deleted = 0;
            List<ScratchCandidate> ordered = new ArrayList<>(selected);
            ordered.sort(OLDEST_SCRATCH_FIRST);
            for (ScratchCandidate candidate : ordered) {
                requireCleanupNotInterrupted();
                if (deleteScratchCandidate(candidate, cutoff)) {
                    deleted++;
                }
            }
        } catch (IOException | RuntimeException failure) {
            logCleanupFailure(started, scanned, candidateCount);
            throw new StorageException(STAGING_CLEANUP_FAILED);
        }
        return cleanupResult(started, scanned, candidateCount, deleted);
    }

    private boolean deleteScratchCandidate(ScratchCandidate candidate, Instant cutoff)
            throws IOException {
        try {
            stagingAccess.verifyRoot();
            Path expected = stagingRoot.resolve(candidate.stableKey())
                    .toAbsolutePath()
                    .normalize();
            boolean canonicalName = candidate.recoveryGuard()
                    ? isCleanupRecoveryEntry(candidate.stableKey())
                    : CANONICAL_SCRATCH_FILE.matcher(candidate.stableKey()).matches();
            if (!canonicalName || !candidate.path().equals(expected)) {
                throw new StorageException(STAGING_CLEANUP_FAILED);
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    candidate.path(), BasicFileAttributes.class, NOFOLLOW_LINKS);
            requireSafeScratchFile(candidate.path(), attributes);
            requireInitialScratchSnapshot(candidate, attributes);
            if (attributes.lastModifiedTime().toInstant().isAfter(cutoff)) {
                return false;
            }
            if (candidate.recoveryGuard()) {
                try (LocalCleanupIdentity identity = LocalCleanupIdentity.capture(
                        candidate.path(),
                        attributes,
                        recoveryCounterpart(candidate.path()),
                        stagingAccess,
                        LocalCleanupIdentity.GuardReleaseObserver.NOOP)) {
                    stagingObserver.beforeCleanup(candidate.path());
                    BasicFileAttributes immediatelyBeforeDelete = Files.readAttributes(
                            candidate.path(), BasicFileAttributes.class, NOFOLLOW_LINKS);
                    requireSafeScratchFile(candidate.path(), immediatelyBeforeDelete);
                    requireInitialScratchSnapshot(candidate, immediatelyBeforeDelete);
                    identity.require(candidate.path());
                    requireRecoveryGuardRelationship(candidate.path());
                    if (immediatelyBeforeDelete.lastModifiedTime()
                            .toInstant()
                            .isAfter(cutoff)) {
                        return false;
                    }
                    Files.delete(candidate.path());
                    stagingAccess.syncDirectory(stagingRoot);
                    return true;
                }
            }
            try (LocalCleanupIdentity identity = LocalCleanupIdentity.capture(
                    candidate.path(),
                    attributes,
                    stagingRoot.resolve(CLEANUP_IDENTITY_GUARD_PREFIX + candidate.stableKey()),
                    stagingAccess,
                    stagingObserver::beforeScavengeGuardRelease)) {
                stagingObserver.beforeCleanup(candidate.path());
                BasicFileAttributes immediatelyBeforeDelete = Files.readAttributes(
                        candidate.path(), BasicFileAttributes.class, NOFOLLOW_LINKS);
                requireSafeScratchFile(candidate.path(), immediatelyBeforeDelete);
                identity.require(candidate.path());
                if (immediatelyBeforeDelete.lastModifiedTime()
                        .toInstant()
                        .isAfter(cutoff)) {
                    return false;
                }
                Files.delete(candidate.path());
                stagingAccess.syncDirectory(stagingRoot);
                return true;
            }
        } catch (NoSuchFileException concurrentRemoval) {
            return false;
        }
    }

    private void requireSafeScratchFile(Path path, BasicFileAttributes attributes)
            throws IOException {
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.isOther()) {
            throw new StorageException(STAGING_CLEANUP_FAILED);
        }
        stagingAccess.verifyFile(path);
    }

    private void requireRecoveryGuardRelationship(Path guard) throws IOException {
        String guardName = fileName(guard);
        String originalName = cleanupRecoveryBaseName(guardName);
        if (originalName == null) {
            throw new StorageException(STAGING_CLEANUP_FAILED);
        }
        List<Path> related = List.of(
                stagingRoot.resolve(originalName).toAbsolutePath().normalize(),
                stagingRoot.resolve(CLEANUP_IDENTITY_GUARD_PREFIX + originalName)
                        .toAbsolutePath()
                        .normalize(),
                stagingRoot.resolve(CLEANUP_VERIFICATION_GUARD_PREFIX + originalName)
                        .toAbsolutePath()
                        .normalize());
        for (Path path : related) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                requireSafeScratchFile(path, attributes);
                if (!Files.isSameFile(guard, path)) {
                    throw new StorageException(STAGING_CLEANUP_FAILED);
                }
            } catch (NoSuchFileException missingRelatedEntry) {
                // A crash may leave either fixed guard slot as the sole link.
            }
        }
    }

    private static Path recoveryCounterpart(Path recovery) {
        String fileName = fileName(recovery);
        String baseName = cleanupRecoveryBaseName(fileName);
        if (baseName == null) {
            throw new StorageException(STAGING_CLEANUP_FAILED);
        }
        String counterpartPrefix = fileName.startsWith(CLEANUP_IDENTITY_GUARD_PREFIX)
                ? CLEANUP_VERIFICATION_GUARD_PREFIX
                : CLEANUP_IDENTITY_GUARD_PREFIX;
        return recovery.getParent().resolve(counterpartPrefix + baseName)
                .toAbsolutePath()
                .normalize();
    }

    private static boolean isCleanupRecoveryEntry(String fileName) {
        return cleanupRecoveryBaseName(fileName) != null;
    }

    private static String cleanupRecoveryBaseName(String fileName) {
        String baseName;
        if (fileName.startsWith(CLEANUP_IDENTITY_GUARD_PREFIX)) {
            baseName = fileName.substring(CLEANUP_IDENTITY_GUARD_PREFIX.length());
        } else if (fileName.startsWith(CLEANUP_VERIFICATION_GUARD_PREFIX)) {
            baseName = fileName.substring(CLEANUP_VERIFICATION_GUARD_PREFIX.length());
        } else {
            return null;
        }
        return CANONICAL_SCRATCH_FILE.matcher(baseName).matches() ? baseName : null;
    }

    private static void requireInitialScratchSnapshot(
            ScratchCandidate expected, BasicFileAttributes actual) {
        if (expected.identity() != null
                && !expected.identity().equals(actual.fileKey())) {
            throw new StorageException(STAGING_CLEANUP_FAILED);
        }
        if (expected.identity() == null
                && (expected.size() != actual.size()
                        || !expected.created().equals(
                                actual.creationTime().toInstant())
                        || !expected.modified().equals(
                                actual.lastModifiedTime().toInstant()))) {
            throw new StorageException(STAGING_CLEANUP_FAILED);
        }
    }

    private static void keepOldestScratch(
            PriorityQueue<ScratchCandidate> selected, ScratchCandidate candidate) {
        if (selected.size() < SCRATCH_CLEANUP_DELETE_LIMIT) {
            selected.add(candidate);
            return;
        }
        if (OLDEST_SCRATCH_FIRST.compare(candidate, selected.peek()) < 0) {
            selected.remove();
            selected.add(candidate);
        }
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw new StorageException(STAGING_CLEANUP_FAILED);
        }
        return fileName.toString();
    }

    private static void requireCleanupNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new StorageException(STAGING_CLEANUP_FAILED);
        }
    }

    private static void requireValidScanLimit(int maximumEntries) {
        if (maximumEntries < 1 || maximumEntries > MAXIMUM_SCRATCH_SCAN_ENTRY_LIMIT) {
            throw new IllegalArgumentException("COS scratch scan limit is invalid");
        }
    }

    private static StagingCleanupResult cleanupResult(
            long started, long scanned, long candidates, int deleted) {
        Duration elapsed = elapsed(started);
        StagingCleanupResult result =
                new StagingCleanupResult(scanned, candidates, deleted, elapsed);
        LOGGER.info(
                "COS scratch cleanup scanned={} candidates={} deleted={} elapsedMs={}",
                result.scanned(),
                result.candidates(),
                result.deleted(),
                result.elapsed().toMillis());
        return result;
    }

    private static void logCleanupFailure(
            long started, long scanned, long candidates) {
        LOGGER.warn(
                "COS scratch cleanup failed scanned={} candidates={} elapsedMs={}",
                scanned,
                candidates,
                elapsed(started).toMillis());
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - started));
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

        default void beforeScavengeGuardRelease(Path guard, Path target)
                throws IOException {}
    }

    private record ScratchCandidate(
            Path path,
            String stableKey,
            boolean recoveryGuard,
            Object identity,
            long size,
            Instant created,
            Instant modified) {}
}
