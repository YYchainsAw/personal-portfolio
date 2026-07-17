package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

final class LocalCleanupIdentity implements AutoCloseable {
    private static final String UNSAFE_PATH = "LOCAL_UNSAFE_PATH";

    private final LocalStorageAccessPolicy accessPolicy;
    private final Object fileKey;
    private final Path guard;
    private final Path target;
    private final boolean createdGuard;
    private final GuardReleaseObserver releaseObserver;

    private LocalCleanupIdentity(
            LocalStorageAccessPolicy accessPolicy,
            Object fileKey,
            Path guard,
            Path target,
            boolean createdGuard,
            GuardReleaseObserver releaseObserver) {
        this.accessPolicy = accessPolicy;
        this.fileKey = fileKey;
        this.guard = guard;
        this.target = target;
        this.createdGuard = createdGuard;
        this.releaseObserver = releaseObserver;
    }

    static LocalCleanupIdentity capture(
            Path path,
            BasicFileAttributes attributes,
            Path deterministicGuard,
            LocalStorageAccessPolicy accessPolicy,
            GuardReleaseObserver releaseObserver) throws IOException {
        if (releaseObserver == null) {
            throw new IllegalArgumentException("cleanup guard observer is required");
        }
        Object fileKey = attributes.fileKey();
        boolean created = false;
        try {
            try {
                Files.createLink(deterministicGuard, path);
                created = true;
            } catch (java.nio.file.FileAlreadyExistsException existing) {
                // A prior interrupted cleanup may have left the exact deterministic guard.
            }
            accessPolicy.verifyFile(deterministicGuard);
            if (!Files.isSameFile(path, deterministicGuard)) {
                throw unsafePath();
            }
            return new LocalCleanupIdentity(
                    accessPolicy,
                    fileKey,
                    deterministicGuard,
                    path,
                    created,
                    releaseObserver);
        } catch (IOException | RuntimeException failure) {
            if (created) {
                cleanupFailedCapture(deterministicGuard, accessPolicy);
            }
            throw failure;
        }
    }

    void require(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.isOther()) {
            throw unsafePath();
        }
        if (fileKey != null) {
            if (!fileKey.equals(attributes.fileKey())) {
                throw unsafePath();
            }
        }
        if (guard == null
                || !Files.exists(guard, NOFOLLOW_LINKS)
                || !Files.isSameFile(path, guard)) {
            throw unsafePath();
        }
    }

    @Override
    public void close() throws IOException {
        if (guard != null && createdGuard) {
            releaseObserver.beforeRelease(guard, target);
            accessPolicy.verifyRoot();
            try {
                accessPolicy.verifyFile(guard);
            } catch (java.nio.file.NoSuchFileException concurrentRelease) {
                return;
            }
            if (Files.deleteIfExists(guard)) {
                accessPolicy.syncDirectory(guard.getParent());
            }
        }
    }

    private static void cleanupFailedCapture(
            Path guard, LocalStorageAccessPolicy accessPolicy) {
        try {
            accessPolicy.verifyRoot();
            accessPolicy.verifyFile(guard);
            if (Files.deleteIfExists(guard)) {
                accessPolicy.syncDirectory(guard.getParent());
            }
        } catch (IOException | RuntimeException ignored) {
            // A unique guard in a private root is removed only while still owner-only.
        }
    }

    private static StorageException unsafePath() {
        return new StorageException(UNSAFE_PATH);
    }

    @FunctionalInterface
    interface GuardReleaseObserver {
        GuardReleaseObserver NOOP = (guard, target) -> {};

        void beforeRelease(Path guard, Path target) throws IOException;
    }
}
