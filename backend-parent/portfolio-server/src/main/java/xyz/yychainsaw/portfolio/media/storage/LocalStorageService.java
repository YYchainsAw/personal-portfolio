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
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public final class LocalStorageService implements StorageService, AutoCloseable {
    static final int STAGING_CLEANUP_FILE_DELETE_LIMIT = 128;
    static final int STAGING_CLEANUP_DIRECTORY_PRUNE_LIMIT = 128;
    static final int DEFAULT_STAGING_SCAN_ENTRY_LIMIT = 100_000;
    private static final int MAXIMUM_STAGING_SCAN_ENTRY_LIMIT = 1_000_000;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final String OBJECT_EXISTS = "STORAGE_OBJECT_ALREADY_EXISTS";
    private static final String UNSAFE_PATH = "LOCAL_UNSAFE_PATH";
    private static final String LENGTH_MISMATCH = "LOCAL_CONTENT_LENGTH_MISMATCH";
    private static final String STAGING_AUTHORIZATION_REQUIRED =
            "LOCAL_STAGING_AUTHORIZATION_REQUIRED";
    private static final String STAGING_CLEANUP_FAILED = "LOCAL_STAGING_CLEANUP_FAILED";
    private static final String STAGING_EXACT_CLEANUP_FAILED =
            "LOCAL_STAGING_EXACT_CLEANUP_FAILED";
    private static final String STAGING_MIGRATION_REQUIRED =
            "LOCAL_STAGING_MIGRATION_REQUIRED";
    private static final String CLEANUP_IDENTITY_GUARD_PREFIX = "@cleanup-identity-";
    private static final String CLEANUP_VERIFICATION_GUARD_PREFIX =
            "@cleanup-verification-";
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalStorageService.class);
    private static final Pattern CANONICAL_ASSET_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern CANONICAL_STAGING_FILE = Pattern.compile(
            "[0-9a-f]{64}\\.(?:jpg|png|pdf)");
    private static final Pattern EXACT_PART_NAME = Pattern.compile(
            "@part-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern EXACT_IDENTITY_NAME = Pattern.compile(
            "@identity-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final int EXACT_OWNED_FILE_ENTRY_LIMIT = 5;
    private static final Comparator<StagingCandidate> OLDEST_STAGING_FIRST =
            Comparator.comparing(StagingCandidate::modified)
                    .thenComparing(StagingCandidate::stableKey);
    private static final Comparator<StagingCandidate> NEWEST_STAGING_FIRST =
            OLDEST_STAGING_FIRST.reversed();
    private static final Comparator<EmptyDirectoryCandidate> DIRECTORY_KEY_ORDER =
            Comparator.comparing(EmptyDirectoryCandidate::stableKey);
    private static final StorageLocation LOCATION =
            new StorageLocation(StorageProvider.LOCAL, null, null);
    private final Path root;
    private final LocalStorageAccessPolicy accessPolicy;
    private final OperationObserver observer;
    private final int maxStagingScanEntries;

    public LocalStorageService(LocalStorageProperties properties) {
        this(properties, OperationObserver.NOOP, DEFAULT_STAGING_SCAN_ENTRY_LIMIT);
    }

    LocalStorageService(LocalStorageProperties properties, int maxStagingScanEntries) {
        this(properties, OperationObserver.NOOP, maxStagingScanEntries);
    }

    LocalStorageService(LocalStorageProperties properties, OperationObserver observer) {
        this(properties, observer, DEFAULT_STAGING_SCAN_ENTRY_LIMIT);
    }

    LocalStorageService(
            LocalStorageProperties properties,
            OperationObserver observer,
            int maxStagingScanEntries) {
        if (properties == null) {
            throw new IllegalArgumentException("Local storage properties are required");
        }
        if (observer == null) {
            throw new IllegalArgumentException("Local storage operation observer is required");
        }
        requireValidScanLimit(maxStagingScanEntries);
        this.observer = observer;
        this.maxStagingScanEntries = maxStagingScanEntries;
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

    public String volumeId() {
        try {
            return accessPolicy.verifiedVolumeId();
        } catch (StorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException("LOCAL_READ_FAILED", exception);
        }
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
                rejectPlainStagingTarget(key);
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

    public StoredObject putReservedStaging(
            LocalPublicationAuthorization authorization,
            LocalStagingPublication stagingPublication,
            InputStream input,
            long contentLength) {
        if (input == null) {
            throw new IllegalArgumentException("Storage input is required");
        }
        LocalPublication publication = null;
        try {
            try (input) {
                claimReservedStagingAuthorization(authorization, stagingPublication);
                authorization.reauthenticate(stagingPublication, volumeId());
                ObjectKey key = ObjectKey.parse(stagingPublication.objectKey());
                String normalizedContentType = StorageObjectContract.normalizeContentType(
                        key, stagingPublication.mimeType());
                Runnable checkpoint =
                        () -> requireReservedStagingAuthorization(
                                authorization, stagingPublication);
                publication = prepareReserved(
                        key,
                        input,
                        contentLength,
                        normalizedContentType,
                        "LOCAL_WRITE_FAILED",
                        checkpoint,
                        stagingPublication.cleanupJobId());
            }
            Runnable checkpoint =
                    () -> requireReservedStagingAuthorization(
                            authorization, stagingPublication);
            Runnable finalCheckpoint = () -> authorization.reauthenticate(
                    stagingPublication, volumeId());
            return publish(publication, checkpoint, finalCheckpoint);
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
        rejectPlainStagingTarget(target);
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

    public synchronized StagingCleanupResult cleanupStaging(Instant cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("Local staging cleanup cutoff is required");
        }
        long started = System.nanoTime();
        CleanupScan scan = new CleanupScan(maxStagingScanEntries);
        int deleted;
        int pruned;
        try {
            requireCleanupNotInterrupted();
            verifyRootIdentity();
            Path staging = root.resolve("staging");
            BasicFileAttributes stagingAttributes;
            try {
                stagingAttributes = readAttributes(staging);
            } catch (NoSuchFileException missing) {
                return cleanupResult(started, scan, 0);
            }
            requireSafeDirectory(staging, stagingAttributes);
            Object stagingIdentity = stagingAttributes.fileKey();

            try (DirectoryStream<Path> assetEntries = Files.newDirectoryStream(staging)) {
                for (Path assetEntry : assetEntries) {
                    scan.visit();
                    String assetId = fileName(assetEntry);
                    if (!CANONICAL_ASSET_ID.matcher(assetId).matches()) {
                        continue;
                    }
                    BasicFileAttributes directoryAttributes;
                    try {
                        directoryAttributes = readAttributes(assetEntry);
                    } catch (NoSuchFileException concurrentRemoval) {
                        continue;
                    }
                    requireSafeDirectory(assetEntry, directoryAttributes);
                    Object directoryIdentity = observer.stagingCleanupDirectoryIdentity(
                            assetEntry, directoryAttributes);
                    boolean empty = true;
                    try (DirectoryStream<Path> fileEntries =
                            Files.newDirectoryStream(assetEntry)) {
                        for (Path fileEntry : fileEntries) {
                            empty = false;
                            scan.visit();
                            String fileName = fileName(fileEntry);
                            boolean recoveryGuard = isCleanupRecoveryEntry(fileName);
                            if (!recoveryGuard
                                    && !CANONICAL_STAGING_FILE.matcher(fileName).matches()) {
                                continue;
                            }
                            BasicFileAttributes fileAttributes;
                            try {
                                fileAttributes = readAttributes(fileEntry);
                            } catch (NoSuchFileException concurrentRemoval) {
                                continue;
                            }
                            requireSafeFile(fileEntry, fileAttributes);
                            if (recoveryGuard) {
                                requireRecoveryGuardRelationship(fileEntry);
                            }
                            if (fileAttributes.lastModifiedTime().toInstant()
                                    .isAfter(cutoff)) {
                                continue;
                            }
                            scan.candidate(new StagingCandidate(
                                    fileEntry.toAbsolutePath().normalize(),
                                    assetEntry.toAbsolutePath().normalize(),
                                    assetId + "/" + fileName,
                                    recoveryGuard,
                                    fileAttributes.fileKey(),
                                    directoryIdentity,
                                    stagingIdentity,
                                    fileAttributes.size(),
                                    fileAttributes.creationTime().toInstant(),
                                    fileAttributes.lastModifiedTime().toInstant()));
                        }
                    }
                    if (empty && directoryIdentity != null) {
                        scan.emptyDirectory(new EmptyDirectoryCandidate(
                                assetEntry.toAbsolutePath().normalize(),
                                assetId,
                                directoryIdentity,
                                stagingIdentity));
                    }
                }
            }

            deleted = 0;
            List<StagingCandidate> selected = new ArrayList<>(scan.candidates);
            selected.sort(OLDEST_STAGING_FIRST);
            java.util.LinkedHashMap<String, EmptyDirectoryCandidate> directories =
                    new java.util.LinkedHashMap<>();
            for (StagingCandidate candidate : selected) {
                requireCleanupNotInterrupted();
                if (deleteStagingCandidate(candidate, cutoff)) {
                    deleted++;
                }
                if (candidate.directoryIdentity() != null) {
                    directories.putIfAbsent(
                            candidate.directory().toString(),
                            new EmptyDirectoryCandidate(
                                    candidate.directory(),
                                    fileName(candidate.directory()),
                                    candidate.directoryIdentity(),
                                    candidate.stagingIdentity()));
                }
            }
            List<EmptyDirectoryCandidate> preexistingEmpty =
                    new ArrayList<>(scan.emptyDirectories);
            preexistingEmpty.sort(DIRECTORY_KEY_ORDER);
            for (EmptyDirectoryCandidate directory : preexistingEmpty) {
                directories.putIfAbsent(directory.path().toString(), directory);
            }
            pruned = 0;
            for (EmptyDirectoryCandidate directory : directories.values()) {
                if (pruned == STAGING_CLEANUP_DIRECTORY_PRUNE_LIMIT) {
                    break;
                }
                requireCleanupNotInterrupted();
                if (deleteEmptyStagingDirectory(staging, directory)) {
                    pruned++;
                }
            }
        } catch (IOException | RuntimeException failure) {
            logCleanupFailure(started, scan);
            throw new StorageException(STAGING_CLEANUP_FAILED);
        }
        return cleanupResult(started, scan, deleted, pruned);
    }

    public synchronized ReservedStagingCleanupResult cleanupReservedStaging(
            LocalPublicationAuthorization authorization,
            UUID assetId,
            String sha256,
            String mimeType,
            Instant cutoff) {
        if (authorization == null) {
            throw new StorageException("LOCAL_STAGING_AUTHORIZATION_INVALID");
        }
        authorization.requireVolume(volumeId());
        return cleanupReservedStaging(assetId, sha256, mimeType, cutoff);
    }

    synchronized ReservedStagingCleanupResult cleanupReservedStaging(
            UUID assetId, String sha256, String mimeType, Instant cutoff) {
        requireExactCleanupIdentity(assetId, sha256, mimeType);
        if (cutoff == null) {
            throw new IllegalArgumentException(
                    "Local reserved staging cleanup cutoff is required");
        }
        try {
            return cleanupReservedStagingExact(assetId, sha256, mimeType, cutoff);
        } catch (ExactCleanupDeferredException deferred) {
            return ReservedStagingCleanupResult.DEFERRED;
        } catch (IOException | RuntimeException failure) {
            throw new StorageException(STAGING_EXACT_CLEANUP_FAILED);
        }
    }

    public synchronized void auditReservedStaging(
            Map<UUID, LocalStagingAuditExpectation> expectedReservations) {
        try {
            auditReservedStagingExact(requireBoundedAuditExpectations(
                    expectedReservations));
        } catch (IOException | RuntimeException failure) {
            throw new StorageException(STAGING_MIGRATION_REQUIRED);
        }
    }

    private void auditReservedStagingExact(
            Map<UUID, LocalStagingAuditExpectation> expectedReservations)
            throws IOException {
        requireExactCleanupNotInterrupted();
        verifyRootIdentity();
        Path staging = root.resolve("staging").toAbsolutePath().normalize();
        BasicFileAttributes stagingAttributes;
        try {
            stagingAttributes = readAttributes(staging);
        } catch (NoSuchFileException absent) {
            return;
        }
        requireSafeDirectory(staging, stagingAttributes);
        requireSameLocalVolume(staging);
        Object stagingIdentity = observer.stagingCleanupDirectoryIdentity(
                staging, stagingAttributes);
        if (stagingIdentity == null) {
            throw unsafePath();
        }

        int scanned = 0;
        try (DirectoryStream<Path> assetEntries = Files.newDirectoryStream(staging)) {
            for (Path assetEntry : assetEntries) {
                requireExactCleanupNotInterrupted();
                scanned = checkedAuditVisit(scanned);
                String assetName = fileName(assetEntry);
                if (!CANONICAL_ASSET_ID.matcher(assetName).matches()) {
                    throw unsafePath();
                }
                UUID assetId;
                try {
                    assetId = UUID.fromString(assetName);
                } catch (IllegalArgumentException malformed) {
                    throw unsafePath();
                }
                if (!assetName.equals(assetId.toString())) {
                    throw unsafePath();
                }
                LocalStagingAuditExpectation expectation =
                        expectedReservations.get(assetId);
                if (expectation == null || !assetId.equals(expectation.assetId())) {
                    throw unsafePath();
                }

                Path expectedDirectory = staging.resolve(assetName)
                        .toAbsolutePath()
                        .normalize();
                Path directory = assetEntry.toAbsolutePath().normalize();
                if (!directory.equals(expectedDirectory)) {
                    throw unsafePath();
                }
                BasicFileAttributes directoryAttributes = readAttributes(directory);
                requireSafeDirectory(directory, directoryAttributes);
                requireSameLocalVolume(directory);
                Object directoryIdentity = observer.stagingCleanupDirectoryIdentity(
                        directory, directoryAttributes);
                if (directoryIdentity == null) {
                    throw unsafePath();
                }

                String canonicalName = expectation.sha256()
                        + '.'
                        + exactExtension(expectation.mimeType());
                List<ExactStagingEntry> entries = new ArrayList<>();
                int partCount = 0;
                int identityCount = 0;
                try (DirectoryStream<Path> fileEntries =
                        Files.newDirectoryStream(directory)) {
                    for (Path fileEntry : fileEntries) {
                        requireExactCleanupNotInterrupted();
                        scanned = checkedAuditVisit(scanned);
                        if (entries.size() == EXACT_OWNED_FILE_ENTRY_LIMIT) {
                            throw unsafePath();
                        }
                        String name = fileName(fileEntry);
                        ExactStagingEntryKind kind;
                        if (name.equals(canonicalName)) {
                            kind = ExactStagingEntryKind.CANONICAL;
                        } else if (EXACT_PART_NAME.matcher(name).matches()) {
                            if (++partCount > 1) {
                                throw unsafePath();
                            }
                            kind = ExactStagingEntryKind.PART;
                        } else if (EXACT_IDENTITY_NAME.matcher(name).matches()) {
                            if (++identityCount > 1) {
                                throw unsafePath();
                            }
                            kind = ExactStagingEntryKind.PUBLICATION_IDENTITY;
                        } else if (name.equals(
                                CLEANUP_IDENTITY_GUARD_PREFIX + canonicalName)) {
                            kind = ExactStagingEntryKind.PRIMARY_GUARD;
                        } else if (name.equals(
                                CLEANUP_VERIFICATION_GUARD_PREFIX + canonicalName)) {
                            kind = ExactStagingEntryKind.VERIFICATION_GUARD;
                        } else {
                            throw unsafePath();
                        }
                        Path expectedFile = directory.resolve(name)
                                .toAbsolutePath()
                                .normalize();
                        Path file = fileEntry.toAbsolutePath().normalize();
                        if (!file.equals(expectedFile)) {
                            throw unsafePath();
                        }
                        entries.add(exactEntry(file, kind, Instant.MAX));
                    }
                }
                if (entries.size() > 1) {
                    requireAllSameExactFile(entries.get(0), entries);
                }
                requireAuditDirectoryIdentity(
                        staging, stagingIdentity, directory, directoryIdentity);
                for (ExactStagingEntry entry : entries) {
                    requireExactEntry(entry, Instant.MAX);
                }
            }
        }
        verifyRootIdentity();
        BasicFileAttributes finalStaging = readAttributes(staging);
        requireSafeDirectory(staging, finalStaging);
        Object finalStagingIdentity = observer.stagingCleanupDirectoryIdentity(
                staging, finalStaging);
        if (!stagingIdentity.equals(finalStagingIdentity)) {
            throw unsafePath();
        }
        requireSameLocalVolume(staging);
    }

    private Map<UUID, LocalStagingAuditExpectation> requireBoundedAuditExpectations(
            Map<UUID, LocalStagingAuditExpectation> expectations) {
        if (expectations == null || expectations.size() > maxStagingScanEntries) {
            throw unsafePath();
        }
        Map<UUID, LocalStagingAuditExpectation> bounded = new LinkedHashMap<>();
        for (Map.Entry<UUID, LocalStagingAuditExpectation> entry
                : expectations.entrySet()) {
            UUID assetId = entry.getKey();
            LocalStagingAuditExpectation expectation = entry.getValue();
            if (assetId == null
                    || expectation == null
                    || !assetId.equals(expectation.assetId())
                    || bounded.putIfAbsent(assetId, expectation) != null) {
                throw unsafePath();
            }
        }
        return Map.copyOf(bounded);
    }

    private int checkedAuditVisit(int scanned) {
        int next;
        try {
            next = Math.addExact(scanned, 1);
        } catch (ArithmeticException overflow) {
            throw unsafePath();
        }
        if (next > maxStagingScanEntries) {
            throw unsafePath();
        }
        return next;
    }

    private void requireAuditDirectoryIdentity(
            Path staging,
            Object stagingIdentity,
            Path directory,
            Object directoryIdentity) throws IOException {
        verifyRootIdentity();
        BasicFileAttributes stagingAttributes = readAttributes(staging);
        requireSafeDirectory(staging, stagingAttributes);
        Object currentStagingIdentity = observer.stagingCleanupDirectoryIdentity(
                staging, stagingAttributes);
        if (!stagingIdentity.equals(currentStagingIdentity)) {
            throw unsafePath();
        }
        requireSameLocalVolume(staging);
        BasicFileAttributes directoryAttributes = readAttributes(directory);
        requireSafeDirectory(directory, directoryAttributes);
        Object currentDirectoryIdentity = observer.stagingCleanupDirectoryIdentity(
                directory, directoryAttributes);
        if (!directoryIdentity.equals(currentDirectoryIdentity)) {
            throw unsafePath();
        }
        requireSameLocalVolume(directory);
    }

    private ReservedStagingCleanupResult cleanupReservedStagingExact(
            UUID assetId, String sha256, String mimeType, Instant cutoff)
            throws IOException {
        requireExactCleanupNotInterrupted();
        verifyRootIdentity();
        Path staging = root.resolve("staging").toAbsolutePath().normalize();
        BasicFileAttributes stagingAttributes;
        try {
            stagingAttributes = readAttributes(staging);
        } catch (NoSuchFileException absent) {
            return ReservedStagingCleanupResult.CLEANED;
        }
        requireSafeDirectory(staging, stagingAttributes);
        requireSameLocalVolume(staging);
        Object stagingIdentity = observer.stagingCleanupDirectoryIdentity(
                staging, stagingAttributes);
        if (stagingIdentity == null) {
            return ReservedStagingCleanupResult.DEFERRED;
        }

        Path directory = staging.resolve(assetId.toString()).toAbsolutePath().normalize();
        BasicFileAttributes directoryAttributes;
        try {
            directoryAttributes = readAttributes(directory);
        } catch (NoSuchFileException absent) {
            return ReservedStagingCleanupResult.CLEANED;
        }
        requireSafeDirectory(directory, directoryAttributes);
        requireSameLocalVolume(directory);
        Object directoryIdentity = observer.stagingCleanupDirectoryIdentity(
                directory, directoryAttributes);
        if (directoryIdentity == null) {
            return ReservedStagingCleanupResult.DEFERRED;
        }

        String canonicalName = sha256 + '.' + exactExtension(mimeType);
        ExactStagingEntries entries = inspectExactStagingEntries(
                directory,
                canonicalName,
                cutoff,
                isYoung(directoryAttributes, cutoff));
        if (entries.young()) {
            return ReservedStagingCleanupResult.DEFERRED;
        }

        ExactDirectorySnapshot snapshot = new ExactDirectorySnapshot(
                staging,
                stagingIdentity,
                directory,
                directoryIdentity);
        if (entries.entries().isEmpty()) {
            deleteExactEmptyDirectory(snapshot, cutoff, true);
            return ReservedStagingCleanupResult.CLEANED;
        }

        Path primary = directory.resolve(
                CLEANUP_IDENTITY_GUARD_PREFIX + canonicalName);
        Path verification = directory.resolve(
                CLEANUP_VERIFICATION_GUARD_PREFIX + canonicalName);
        ExactStagingEntry anchor = entries.primary();
        if (anchor == null) {
            ExactStagingEntry source = entries.entries().get(0);
            observer.beforeExactStagingCleanupMutation(source.path());
            requireExactDirectory(snapshot, false, cutoff);
            requireExactEntry(source, cutoff);
            Files.createLink(primary, source.path());
            requireSafeFile(primary, readAttributes(primary));
            if (!Files.isSameFile(primary, source.path())) {
                throw unsafePath();
            }
            syncExactDirectory(directory);
            anchor = exactEntry(primary, ExactStagingEntryKind.PRIMARY_GUARD, cutoff);
        }
        requireAllSameExactFile(anchor, entries.entries());

        for (ExactStagingEntry entry : entries.entries()) {
            if (entry.kind() != ExactStagingEntryKind.PRIMARY_GUARD
                    && entry.kind() != ExactStagingEntryKind.VERIFICATION_GUARD) {
                deleteExactAlias(entry, anchor.path(), snapshot, cutoff);
            }
        }

        ExactStagingEntry currentPrimary = exactEntry(
                primary, ExactStagingEntryKind.PRIMARY_GUARD, cutoff);
        ExactStagingEntry currentVerification;
        try {
            currentVerification = exactEntry(
                    verification, ExactStagingEntryKind.VERIFICATION_GUARD, cutoff);
        } catch (NoSuchFileException absent) {
            observer.beforeExactStagingCleanupMutation(currentPrimary.path());
            requireExactDirectory(snapshot, false, cutoff);
            requireExactEntry(currentPrimary, cutoff);
            Files.createLink(verification, currentPrimary.path());
            requireSafeFile(verification, readAttributes(verification));
            if (!Files.isSameFile(verification, currentPrimary.path())) {
                throw unsafePath();
            }
            syncExactDirectory(directory);
            currentVerification = exactEntry(
                    verification, ExactStagingEntryKind.VERIFICATION_GUARD, cutoff);
        }
        if (!Files.isSameFile(currentPrimary.path(), currentVerification.path())) {
            throw unsafePath();
        }

        deleteExactAlias(currentVerification, currentPrimary.path(), snapshot, cutoff);
        deleteExactLastAlias(currentPrimary, snapshot, cutoff);
        requireExactDirectory(snapshot, false, cutoff);
        ensureExactDirectoryEmpty(directory);
        deleteExactEmptyDirectory(snapshot, cutoff, false);
        return ReservedStagingCleanupResult.CLEANED;
    }

    private ExactStagingEntries inspectExactStagingEntries(
            Path directory,
            String canonicalName,
            Instant cutoff,
            boolean directoryYoung) throws IOException {
        List<ExactStagingEntry> entries = new ArrayList<>();
        int partCount = 0;
        int identityCount = 0;
        boolean young = directoryYoung;
        ExactStagingEntry primary = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (entries.size() == EXACT_OWNED_FILE_ENTRY_LIMIT) {
                    throw unsafePath();
                }
                String name = fileName(path);
                ExactStagingEntryKind kind;
                if (name.equals(canonicalName)) {
                    kind = ExactStagingEntryKind.CANONICAL;
                } else if (EXACT_PART_NAME.matcher(name).matches()) {
                    if (++partCount > 1) {
                        throw unsafePath();
                    }
                    kind = ExactStagingEntryKind.PART;
                } else if (EXACT_IDENTITY_NAME.matcher(name).matches()) {
                    if (++identityCount > 1) {
                        throw unsafePath();
                    }
                    kind = ExactStagingEntryKind.PUBLICATION_IDENTITY;
                } else if (name.equals(CLEANUP_IDENTITY_GUARD_PREFIX + canonicalName)) {
                    kind = ExactStagingEntryKind.PRIMARY_GUARD;
                } else if (name.equals(
                        CLEANUP_VERIFICATION_GUARD_PREFIX + canonicalName)) {
                    kind = ExactStagingEntryKind.VERIFICATION_GUARD;
                } else {
                    throw unsafePath();
                }
                ExactStagingEntry entry = exactEntry(path, kind, cutoff);
                young |= entry.created().isAfter(cutoff)
                        || entry.modified().isAfter(cutoff);
                entries.add(entry);
                if (kind == ExactStagingEntryKind.PRIMARY_GUARD) {
                    primary = entry;
                }
            }
        }
        if (entries.size() > 1) {
            ExactStagingEntry first = entries.get(0);
            requireAllSameExactFile(first, entries);
        }
        return new ExactStagingEntries(List.copyOf(entries), primary, young);
    }

    private ExactStagingEntry exactEntry(
            Path path, ExactStagingEntryKind kind, Instant cutoff) throws IOException {
        BasicFileAttributes attributes = readAttributes(path);
        requireSafeFile(path, attributes);
        requireSameLocalVolume(path);
        Object identity = observer.exactStagingCleanupFileIdentity(path, attributes);
        return new ExactStagingEntry(
                path.toAbsolutePath().normalize(),
                kind,
                identity,
                attributes.size(),
                attributes.creationTime().toInstant(),
                attributes.lastModifiedTime().toInstant());
    }

    private void deleteExactAlias(
            ExactStagingEntry entry,
            Path anchor,
            ExactDirectorySnapshot directory,
            Instant cutoff) throws IOException {
        observer.beforeExactStagingCleanupMutation(entry.path());
        requireExactDirectory(directory, false, cutoff);
        requireExactEntry(entry, cutoff);
        if (entry.path().equals(anchor) || !Files.isSameFile(entry.path(), anchor)) {
            throw unsafePath();
        }
        Files.delete(entry.path());
        syncExactDirectory(directory.directory());
    }

    private void deleteExactLastAlias(
            ExactStagingEntry entry,
            ExactDirectorySnapshot directory,
            Instant cutoff) throws IOException {
        observer.beforeExactStagingCleanupMutation(entry.path());
        requireExactDirectory(directory, false, cutoff);
        requireExactEntry(entry, cutoff);
        Files.delete(entry.path());
        syncExactDirectory(directory.directory());
    }

    private void deleteExactEmptyDirectory(
            ExactDirectorySnapshot snapshot, Instant cutoff, boolean requireOld)
            throws IOException {
        observer.beforeExactStagingCleanupMutation(snapshot.directory());
        requireExactDirectory(snapshot, requireOld, cutoff);
        ensureExactDirectoryEmpty(snapshot.directory());
        Files.delete(snapshot.directory());
        syncExactDirectory(snapshot.staging());
        try {
            readAttributes(snapshot.directory());
            throw unsafePath();
        } catch (NoSuchFileException deleted) {
            // Exact absence is required before the reservation can be released.
        }
    }

    private void requireExactDirectory(
            ExactDirectorySnapshot expected,
            boolean requireOld,
            Instant cutoff) throws IOException {
        verifyRootIdentity();
        BasicFileAttributes stagingAttributes = readAttributes(expected.staging());
        requireSafeDirectory(expected.staging(), stagingAttributes);
        Object currentStagingIdentity = observer.stagingCleanupDirectoryIdentity(
                expected.staging(), stagingAttributes);
        if (!expected.stagingIdentity().equals(currentStagingIdentity)) {
            throw unsafePath();
        }
        requireSameLocalVolume(expected.staging());
        BasicFileAttributes directoryAttributes = readAttributes(expected.directory());
        requireSafeDirectory(expected.directory(), directoryAttributes);
        Object currentDirectoryIdentity = observer.stagingCleanupDirectoryIdentity(
                expected.directory(), directoryAttributes);
        if (!expected.directoryIdentity().equals(currentDirectoryIdentity)) {
            throw unsafePath();
        }
        requireSameLocalVolume(expected.directory());
        if (requireOld
                && isYoung(directoryAttributes, cutoff)) {
            throw new ExactCleanupDeferredException();
        }
    }

    private void requireExactEntry(ExactStagingEntry expected, Instant cutoff)
            throws IOException {
        BasicFileAttributes attributes = readAttributes(expected.path());
        requireSafeFile(expected.path(), attributes);
        requireSameLocalVolume(expected.path());
        Object actualIdentity = observer.exactStagingCleanupFileIdentity(
                expected.path(), attributes);
        if (expected.identity() != null || actualIdentity != null) {
            if (!java.util.Objects.equals(expected.identity(), actualIdentity)) {
                throw unsafePath();
            }
        } else if (expected.size() != attributes.size()
                || !expected.created().equals(attributes.creationTime().toInstant())
                || !expected.modified().equals(attributes.lastModifiedTime().toInstant())) {
            throw unsafePath();
        }
        if (isYoung(attributes, cutoff)) {
            throw new ExactCleanupDeferredException();
        }
    }

    private static boolean isYoung(
            BasicFileAttributes attributes, Instant cutoff) {
        return attributes.creationTime().toInstant().isAfter(cutoff)
                || attributes.lastModifiedTime().toInstant().isAfter(cutoff);
    }

    private static void requireAllSameExactFile(
            ExactStagingEntry anchor, List<ExactStagingEntry> entries) throws IOException {
        for (ExactStagingEntry entry : entries) {
            if (!Files.isSameFile(anchor.path(), entry.path())) {
                throw unsafePath();
            }
        }
    }

    private static void ensureExactDirectoryEmpty(Path directory) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            if (entries.iterator().hasNext()) {
                throw unsafePath();
            }
        }
    }

    private void syncExactDirectory(Path directory) throws IOException {
        observer.beforeExactStagingCleanupSync(directory);
        accessPolicy.syncDirectory(directory);
    }

    private void requireSameLocalVolume(Path path) throws IOException {
        if (!Files.getFileStore(root).equals(Files.getFileStore(path))) {
            throw unsafePath();
        }
    }

    private static void requireExactCleanupIdentity(
            UUID assetId, String sha256, String mimeType) {
        if (assetId == null
                || sha256 == null
                || !sha256.matches("[0-9a-f]{64}")
                || !("image/jpeg".equals(mimeType)
                        || "image/png".equals(mimeType)
                        || "application/pdf".equals(mimeType))) {
            throw new IllegalArgumentException(
                    "Local reserved staging cleanup identity is invalid");
        }
    }

    private static String exactExtension(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> throw new IllegalArgumentException(
                    "Local reserved staging cleanup identity is invalid");
        };
    }

    private static void requireExactCleanupNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new StorageException(STAGING_EXACT_CLEANUP_FAILED);
        }
    }

    private boolean deleteStagingCandidate(StagingCandidate candidate, Instant cutoff)
            throws IOException {
        try {
            verifyRootIdentity();
            requireCanonicalCleanupPath(candidate);
            BasicFileAttributes stagingAttributes =
                    readAttributes(root.resolve("staging"));
            requireSafeDirectory(root.resolve("staging"), stagingAttributes);
            requireIdentity(candidate.stagingIdentity(), stagingAttributes);
            BasicFileAttributes directoryAttributes = readAttributes(candidate.directory());
            requireSafeDirectory(candidate.directory(), directoryAttributes);
            requireIdentity(candidate.directoryIdentity(), directoryAttributes);
            BasicFileAttributes fileAttributes = readAttributes(candidate.path());
            requireSafeFile(candidate.path(), fileAttributes);
            requireInitialFileSnapshot(candidate, fileAttributes);
            if (fileAttributes.lastModifiedTime().toInstant().isAfter(cutoff)) {
                return false;
            }
            if (candidate.recoveryGuard()) {
                try (LocalCleanupIdentity identity = LocalCleanupIdentity.capture(
                        candidate.path(),
                        fileAttributes,
                        recoveryCounterpart(candidate.path()),
                        accessPolicy,
                        LocalCleanupIdentity.GuardReleaseObserver.NOOP)) {
                    observer.beforeStagingCleanupDelete(candidate.path());
                    BasicFileAttributes immediatelyBeforeDelete =
                            readAttributes(candidate.path());
                    requireSafeFile(candidate.path(), immediatelyBeforeDelete);
                    requireInitialFileSnapshot(candidate, immediatelyBeforeDelete);
                    identity.require(candidate.path());
                    requireRecoveryGuardRelationship(candidate.path());
                    if (immediatelyBeforeDelete.lastModifiedTime()
                            .toInstant()
                            .isAfter(cutoff)) {
                        return false;
                    }
                    Files.delete(candidate.path());
                    accessPolicy.syncDirectory(candidate.directory());
                    return true;
                }
            }
            try (LocalCleanupIdentity identity = LocalCleanupIdentity.capture(
                    candidate.path(),
                    fileAttributes,
                    candidate.directory().resolve(
                            CLEANUP_IDENTITY_GUARD_PREFIX + fileName(candidate.path())),
                    accessPolicy,
                    observer::beforeStagingCleanupGuardRelease)) {
                observer.beforeStagingCleanupDelete(candidate.path());
                BasicFileAttributes immediatelyBeforeDelete =
                        readAttributes(candidate.path());
                requireSafeFile(candidate.path(), immediatelyBeforeDelete);
                identity.require(candidate.path());
                if (immediatelyBeforeDelete.lastModifiedTime()
                        .toInstant()
                        .isAfter(cutoff)) {
                    return false;
                }
                Files.delete(candidate.path());
                accessPolicy.syncDirectory(candidate.directory());
                return true;
            }
        } catch (NoSuchFileException concurrentRemoval) {
            return false;
        }
    }

    private boolean deleteEmptyStagingDirectory(
            Path staging, EmptyDirectoryCandidate candidate) throws IOException {
        if (candidate.identity() == null) {
            return false;
        }
        try {
            verifyRootIdentity();
            BasicFileAttributes stagingAttributes = readAttributes(staging);
            requireSafeDirectory(staging, stagingAttributes);
            requireIdentity(candidate.stagingIdentity(), stagingAttributes);
            Path expected = staging.resolve(candidate.stableKey())
                    .toAbsolutePath()
                    .normalize();
            if (!candidate.path().equals(expected)) {
                throw unsafePath();
            }
            BasicFileAttributes attributes = readAttributes(candidate.path());
            requireSafeDirectory(candidate.path(), attributes);
            requireIdentity(candidate.identity(), attributes);
            Files.delete(candidate.path());
            accessPolicy.syncDirectory(staging);
            return true;
        } catch (NoSuchFileException | DirectoryNotEmptyException concurrentChange) {
            return false;
        }
    }

    private void requireCanonicalCleanupPath(StagingCandidate candidate) {
        Path expectedDirectory = root.resolve("staging")
                .resolve(fileName(candidate.directory()))
                .toAbsolutePath()
                .normalize();
        Path expectedFile = expectedDirectory.resolve(fileName(candidate.path()))
                .toAbsolutePath()
                .normalize();
        String candidateFileName = fileName(candidate.path());
        boolean canonicalName = candidate.recoveryGuard()
                ? isCleanupRecoveryEntry(candidateFileName)
                : CANONICAL_STAGING_FILE.matcher(candidateFileName).matches();
        if (!canonicalName
                || !CANONICAL_ASSET_ID.matcher(fileName(candidate.directory())).matches()
                || !candidate.directory().equals(expectedDirectory)
                || !candidate.path().equals(expectedFile)
                || !candidate.stableKey().equals(
                        fileName(candidate.directory()) + "/" + fileName(candidate.path()))) {
            throw unsafePath();
        }
    }

    private void requireRecoveryGuardRelationship(Path guard) throws IOException {
        String guardName = fileName(guard);
        String originalName = cleanupRecoveryBaseName(guardName);
        if (originalName == null) {
            throw unsafePath();
        }
        Path directory = guard.getParent();
        List<Path> related = List.of(
                directory.resolve(originalName).toAbsolutePath().normalize(),
                directory.resolve(CLEANUP_IDENTITY_GUARD_PREFIX + originalName)
                        .toAbsolutePath()
                        .normalize(),
                directory.resolve(CLEANUP_VERIFICATION_GUARD_PREFIX + originalName)
                        .toAbsolutePath()
                        .normalize());
        for (Path path : related) {
            try {
                BasicFileAttributes attributes = readAttributes(path);
                requireSafeFile(path, attributes);
                if (!Files.isSameFile(guard, path)) {
                    throw unsafePath();
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
            throw unsafePath();
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
        return CANONICAL_STAGING_FILE.matcher(baseName).matches() ? baseName : null;
    }

    private void requireSafeDirectory(Path path, BasicFileAttributes attributes)
            throws IOException {
        if (!isRealDirectory(attributes)) {
            throw unsafePath();
        }
        accessPolicy.verifyDirectory(path);
    }

    private void requireSafeFile(Path path, BasicFileAttributes attributes)
            throws IOException {
        if (!isRealRegularFile(attributes)) {
            throw unsafePath();
        }
        accessPolicy.verifyFile(path);
    }

    private static void requireIdentity(
            Object expected, BasicFileAttributes actual) {
        if (expected != null && !expected.equals(actual.fileKey())) {
            throw unsafePath();
        }
    }

    private static void requireInitialFileSnapshot(
            StagingCandidate expected, BasicFileAttributes actual) {
        requireIdentity(expected.fileIdentity(), actual);
        if (expected.fileIdentity() == null
                && (expected.size() != actual.size()
                        || !expected.created().equals(
                                actual.creationTime().toInstant())
                        || !expected.modified().equals(
                                actual.lastModifiedTime().toInstant()))) {
            throw unsafePath();
        }
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw unsafePath();
        }
        return fileName.toString();
    }

    private static void requireCleanupNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new StorageException(STAGING_CLEANUP_FAILED);
        }
    }

    private static void requireValidScanLimit(int maximumEntries) {
        if (maximumEntries < 1 || maximumEntries > MAXIMUM_STAGING_SCAN_ENTRY_LIMIT) {
            throw new IllegalArgumentException("Local staging scan limit is invalid");
        }
    }

    private static StagingCleanupResult cleanupResult(
            long started, CleanupScan scan, int deleted) {
        return cleanupResult(started, scan, deleted, 0);
    }

    private static StagingCleanupResult cleanupResult(
            long started, CleanupScan scan, int deleted, int pruned) {
        long elapsedNanos = System.nanoTime() - started;
        Duration elapsed = Duration.ofNanos(Math.max(0, elapsedNanos));
        StagingCleanupResult result = new StagingCleanupResult(
                scan.scanned, scan.candidateCount, (long) deleted + pruned, elapsed);
        LOGGER.info(
                "Local staging cleanup scanned={} candidates={} deleted={} "
                        + "objectsDeleted={} directoriesPruned={} elapsedMs={}",
                result.scanned(),
                result.candidates(),
                result.deleted(),
                deleted,
                pruned,
                result.elapsed().toMillis());
        return result;
    }

    private static void logCleanupFailure(long started, CleanupScan scan) {
        long elapsedNanos = System.nanoTime() - started;
        LOGGER.warn(
                "Local staging cleanup failed scanned={} candidates={} elapsedMs={}",
                scan.scanned,
                scan.candidateCount,
                Duration.ofNanos(Math.max(0, elapsedNanos)).toMillis());
    }

    private LocalPublication prepare(
            ObjectKey key,
            InputStream input,
            long contentLength,
            String contentType,
            String failureCode) throws IOException {
        return prepare(key, input, contentLength, contentType, failureCode, () -> {});
    }

    private LocalPublication prepare(
            ObjectKey key,
            InputStream input,
            long contentLength,
            String contentType,
            String failureCode,
            Runnable checkpoint) throws IOException {
        return prepare(
                key,
                input,
                contentLength,
                contentType,
                failureCode,
                checkpoint,
                null);
    }

    private LocalPublication prepareReserved(
            ObjectKey key,
            InputStream input,
            long contentLength,
            String contentType,
            String failureCode,
            Runnable checkpoint,
            UUID cleanupJobId) throws IOException {
        if (cleanupJobId == null) {
            throw new IllegalArgumentException("Local cleanup job id is required");
        }
        return prepare(
                key,
                input,
                contentLength,
                contentType,
                failureCode,
                checkpoint,
                cleanupJobId);
    }

    private LocalPublication prepare(
            ObjectKey key,
            InputStream input,
            long contentLength,
            String contentType,
            String failureCode,
            Runnable checkpoint,
            UUID reservedCleanupJobId) throws IOException {
        StorageObjectContract.validateContentLength(contentLength);
        checkpoint.run();
        Path target = resolveTarget(key, true, checkpoint);
        checkpoint.run();
        rejectExistingTarget(target);
        Path temporary = reservedCleanupJobId == null
                ? LocalReservedNames.newPart(target.getParent())
                : LocalReservedNames.reservedPart(
                        target.getParent(), reservedCleanupJobId);
        LocalFileIdentity identity = null;
        BasicFileAttributes initialIdentity = null;
        boolean prepared = false;
        try {
            MessageDigest messageDigest = sha256();
            checkpoint.run();
            try (FileChannel output = createTemporaryFile(temporary)) {
                initialIdentity = requireRegularFile(temporary);
                Object fileKey = observer.publicationFileKey(temporary, initialIdentity);
                checkpoint.run();
                identity = reservedCleanupJobId == null
                        ? LocalFileIdentity.capture(
                                temporary,
                                fileKey,
                                observer::createPublicationIdentityGuard)
                        : LocalFileIdentity.captureReserved(
                                temporary,
                                fileKey,
                                reservedCleanupJobId,
                                observer::createPublicationIdentityGuard);
                copyExactly(input, output, messageDigest, contentLength, checkpoint);
                checkpoint.run();
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
            checkpoint.run();
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
        return publish(publication, () -> {}, () -> {});
    }

    private StoredObject publish(LocalPublication publication, Runnable checkpoint)
            throws IOException {
        return publish(publication, checkpoint, checkpoint);
    }

    private StoredObject publish(
            LocalPublication publication,
            Runnable checkpoint,
            Runnable finalCheckpoint) throws IOException {
        try {
            checkpoint.run();
            Path checkedTarget = resolveTarget(publication.key(), false, checkpoint);
            if (!checkedTarget.equals(publication.target())) {
                throw unsafePath();
            }
            publication.identity().require(publication.temporary());
            checkpoint.run();
            rejectExistingTarget(publication.target());
            finalCheckpoint.run();
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
        return resolveTarget(key, createParents, () -> {});
    }

    private Path resolveTarget(
            ObjectKey key, boolean createParents, Runnable checkpoint) throws IOException {
        checkpoint.run();
        verifyRootIdentity();
        String[] segments = key.segments();
        Path parent = root;
        for (int index = 0; index < segments.length - 1; index++) {
            Path descendant = parent.resolve(segments[index]);
            checkpoint.run();
            BasicFileAttributes attributes;
            try {
                attributes = readAttributes(descendant);
            } catch (NoSuchFileException exception) {
                if (!createParents) {
                    throw exception;
                }
                checkpoint.run();
                createDirectory(descendant);
                checkpoint.run();
                attributes = readAttributes(descendant);
            }
            if (!isRealDirectory(attributes)) {
                throw unsafePath();
            }
            accessPolicy.verifyDirectory(descendant);
            parent = descendant;
        }
        checkpoint.run();
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
        copyExactly(input, output, digest, contentLength, () -> {});
    }

    private void copyExactly(
            InputStream input,
            FileChannel output,
            MessageDigest digest,
            long contentLength,
            Runnable checkpoint) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long remaining = contentLength;
        while (remaining > 0) {
            int requested = (int) Math.min(buffer.length, remaining);
            checkpoint.run();
            int count = input.read(buffer, 0, requested);
            checkpoint.run();
            if (count < 0) {
                throw new StorageException(LENGTH_MISMATCH);
            }
            if (count == 0) {
                checkpoint.run();
                int single = input.read();
                checkpoint.run();
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
                checkpoint.run();
            }
            remaining -= count;
        }
        checkpoint.run();
        int trailing = input.read();
        checkpoint.run();
        if (trailing >= 0) {
            throw new StorageException(LENGTH_MISMATCH);
        }
    }

    private void requireReservedStagingAuthorization(
            LocalPublicationAuthorization authorization,
            LocalStagingPublication stagingPublication) {
        if (authorization == null) {
            throw new StorageException("LOCAL_STAGING_AUTHORIZATION_INVALID");
        }
        authorization.require(stagingPublication, volumeId());
        try {
            stagingPublication.requireInitialLocalIdentity();
        } catch (NullPointerException invalidPublication) {
            throw new StorageException("LOCAL_STAGING_AUTHORIZATION_INVALID");
        }
    }

    private void claimReservedStagingAuthorization(
            LocalPublicationAuthorization authorization,
            LocalStagingPublication stagingPublication) {
        if (authorization == null) {
            throw new StorageException("LOCAL_STAGING_AUTHORIZATION_INVALID");
        }
        authorization.claim(stagingPublication, volumeId());
        try {
            stagingPublication.requireInitialLocalIdentity();
        } catch (NullPointerException invalidPublication) {
            throw new StorageException("LOCAL_STAGING_AUTHORIZATION_INVALID");
        }
    }

    private static void rejectPlainStagingTarget(ObjectKey key) {
        String[] segments = key.segments();
        if (segments.length > 0 && isAsciiStagingSegment(segments[0])) {
            throw new StorageException(STAGING_AUTHORIZATION_REQUIRED);
        }
    }

    private static boolean isAsciiStagingSegment(String segment) {
        String expected = "staging";
        if (segment == null || segment.length() != expected.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            char actual = segment.charAt(index);
            if (actual >= 'A' && actual <= 'Z') {
                actual = (char) (actual + ('a' - 'A'));
            }
            if (actual != expected.charAt(index)) {
                return false;
            }
        }
        return true;
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

        default void beforeStagingCleanupDelete(Path target) throws IOException {}

        default void beforeStagingCleanupGuardRelease(Path guard, Path target)
                throws IOException {}

        default Object stagingCleanupDirectoryIdentity(
                Path directory, BasicFileAttributes attributes) throws IOException {
            return attributes.fileKey();
        }

        default Object exactStagingCleanupFileIdentity(
                Path path, BasicFileAttributes attributes) throws IOException {
            return attributes.fileKey();
        }

        default void beforeExactStagingCleanupMutation(Path target) throws IOException {}

        default void beforeExactStagingCleanupSync(Path directory) throws IOException {}
    }

    private enum ExactStagingEntryKind {
        CANONICAL,
        PART,
        PUBLICATION_IDENTITY,
        PRIMARY_GUARD,
        VERIFICATION_GUARD
    }

    private record ExactStagingEntry(
            Path path,
            ExactStagingEntryKind kind,
            Object identity,
            long size,
            Instant created,
            Instant modified) {}

    private record ExactStagingEntries(
            List<ExactStagingEntry> entries,
            ExactStagingEntry primary,
            boolean young) {}

    private record ExactDirectorySnapshot(
            Path staging,
            Object stagingIdentity,
            Path directory,
            Object directoryIdentity) {}

    private static final class ExactCleanupDeferredException extends RuntimeException {
        private ExactCleanupDeferredException() {
            super(null, null, false, false);
        }
    }

    private record StagingCandidate(
            Path path,
            Path directory,
            String stableKey,
            boolean recoveryGuard,
            Object fileIdentity,
            Object directoryIdentity,
            Object stagingIdentity,
            long size,
            Instant created,
            Instant modified) {}

    private record EmptyDirectoryCandidate(
            Path path, String stableKey, Object identity, Object stagingIdentity) {}

    private static final class CleanupScan {
        private final int maximumEntries;
        private final PriorityQueue<StagingCandidate> candidates =
                new PriorityQueue<>(NEWEST_STAGING_FIRST);
        private final PriorityQueue<EmptyDirectoryCandidate> emptyDirectories =
                new PriorityQueue<>(DIRECTORY_KEY_ORDER.reversed());
        private long scanned;
        private long candidateCount;

        private CleanupScan(int maximumEntries) {
            this.maximumEntries = maximumEntries;
        }

        private void visit() {
            requireCleanupNotInterrupted();
            scanned++;
            if (scanned > maximumEntries) {
                throw new StorageException(STAGING_CLEANUP_FAILED);
            }
        }

        private void candidate(StagingCandidate candidate) {
            candidateCount++;
            keepOldest(
                    candidates,
                    candidate,
                    OLDEST_STAGING_FIRST,
                    STAGING_CLEANUP_FILE_DELETE_LIMIT);
        }

        private void emptyDirectory(EmptyDirectoryCandidate candidate) {
            keepOldest(
                    emptyDirectories,
                    candidate,
                    DIRECTORY_KEY_ORDER,
                    STAGING_CLEANUP_DIRECTORY_PRUNE_LIMIT);
        }

        private static <T> void keepOldest(
                PriorityQueue<T> selected,
                T candidate,
                Comparator<T> order,
                int limit) {
            if (selected.size() < limit) {
                selected.add(candidate);
                return;
            }
            if (order.compare(candidate, selected.peek()) < 0) {
                selected.remove();
                selected.add(candidate);
            }
        }
    }

}
