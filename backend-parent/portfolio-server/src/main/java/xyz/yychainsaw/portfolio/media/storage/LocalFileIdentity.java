package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

final class LocalFileIdentity {
    private static final String UNSAFE_PATH = "LOCAL_UNSAFE_PATH";

    private final Object fileKey;
    private final Path guard;

    private LocalFileIdentity(Object fileKey, Path guard) {
        this.fileKey = fileKey;
        this.guard = guard;
    }

    static LocalFileIdentity capture(Path path, BasicFileAttributes attributes) throws IOException {
        return capture(path, attributes.fileKey(), (guard, source) -> Files.createLink(guard, source));
    }

    static LocalFileIdentity capture(Path path, Object fileKey, GuardLinker guardLinker)
            throws IOException {
        if (fileKey != null) {
            return new LocalFileIdentity(fileKey, null);
        }
        Path guard = LocalReservedNames.newIdentity(path.getParent());
        try {
            guardLinker.create(guard, path);
            if (!Files.isSameFile(path, guard)) {
                throw unsafePath();
            }
            return new LocalFileIdentity(null, guard);
        } catch (IOException | RuntimeException exception) {
            cleanupFailedGuard(path, guard);
            throw exception;
        }
    }

    void require(Path path) throws IOException {
        if (!matches(path)) {
            throw unsafePath();
        }
    }

    boolean matches(Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            return false;
        }
        if (!isRealRegularFile(attributes)) {
            return false;
        }
        if (fileKey != null) {
            return fileKey.equals(attributes.fileKey());
        }
        if (guard == null) {
            return false;
        }
        try {
            BasicFileAttributes guardAttributes = Files.readAttributes(
                    guard, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!isRealRegularFile(guardAttributes)) {
                return false;
            }
            return Files.isSameFile(path, guard);
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    void cleanupCreatedName(Path path) {
        try {
            boolean matches = matches(path);
            releaseGuard(path);
            if (matches) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Preserve the primary failure and never delete a name with a changed identity.
        }
    }

    void releaseGuard(Path stableAlias) {
        if (guard == null) {
            return;
        }
        try {
            if (Files.exists(stableAlias, NOFOLLOW_LINKS)
                    && Files.isSameFile(guard, stableAlias)) {
                Files.deleteIfExists(guard);
            }
        } catch (IOException ignored) {
            // A private alias may remain, but an unrelated node is never removed.
        }
    }

    private static void cleanupFailedGuard(Path path, Path guard) {
        try {
            if (Files.exists(path, NOFOLLOW_LINKS)
                    && Files.exists(guard, NOFOLLOW_LINKS)
                    && Files.isSameFile(path, guard)) {
                Files.deleteIfExists(guard);
            }
        } catch (IOException ignored) {
            // Preserve the capture failure and never delete an unrelated guard name.
        }
    }

    private static StorageException unsafePath() {
        return new StorageException(UNSAFE_PATH);
    }

    private static boolean isRealRegularFile(BasicFileAttributes attributes) {
        return attributes.isRegularFile()
                && !attributes.isSymbolicLink()
                && !attributes.isOther();
    }

    @FunctionalInterface
    interface GuardLinker {
        void create(Path guard, Path source) throws IOException;
    }
}
