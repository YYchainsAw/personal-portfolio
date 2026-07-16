package xyz.yychainsaw.portfolio.media.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class MediaTemporaryFiles {
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private MediaTemporaryFiles() {}

    static Path defaultDirectory() {
        String configured = System.getProperty("java.io.tmpdir");
        if (configured == null || configured.isBlank()) {
            throw failure();
        }
        return requireDirectory(Path.of(configured).toAbsolutePath().normalize());
    }

    static Path requireDirectory(Path directory) {
        if (directory == null
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("media temporary directory is invalid");
        }
        return directory.toAbsolutePath().normalize();
    }

    static Path create(Path directory, String suffix) throws IOException {
        Path required = requireDirectory(directory);
        if (suffix == null || !suffix.matches("\\.[a-z]{2,8}")) {
            throw new IOException("media temporary suffix is invalid");
        }
        FileStore store = Files.getFileStore(required);
        boolean posix = store.supportsFileAttributeView(PosixFileAttributeView.class);
        boolean acl = !posix && store.supportsFileAttributeView(AclFileAttributeView.class);
        if (!posix && !acl) {
            throw new IOException("owner-only media temporaries are unsupported");
        }
        UserPrincipal owner = currentProcessOwner(required);
        List<AclEntry> ownerAcl = acl
                ? List.of(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build())
                : List.of();
        FileAttribute<?> attribute = posix
                ? PosixFilePermissions.asFileAttribute(OWNER_ONLY)
                : aclAttribute(ownerAcl);

        for (int attempt = 0; attempt < 32; attempt++) {
            Path created = required.resolve(
                    "portfolio-media-variant-" + UUID.randomUUID() + suffix);
            try {
                try (FileChannel ignored = FileChannel.open(
                        created,
                        Set.of(
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS),
                        attribute)) {
                    // Creation attributes are applied atomically with CREATE_NEW.
                }
                verifyOwnerOnly(created, owner, posix, ownerAcl);
                return created;
            } catch (FileAlreadyExistsException collision) {
                continue;
            } catch (IOException | RuntimeException failure) {
                deleteBestEffort(created);
                if (failure instanceof IOException io) {
                    throw io;
                }
                throw new IOException("media temporary file is invalid");
            }
        }
        throw new IOException("media temporary file allocation failed");
    }

    private static void verifyOwnerOnly(
            Path path,
            UserPrincipal owner,
            boolean posix,
            List<AclEntry> ownerAcl) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !owner.equals(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("media temporary file is invalid");
        }
        if (posix) {
            PosixFileAttributes attributes = Files.readAttributes(
                    path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.permissions().equals(OWNER_ONLY)) {
                throw new IOException("media temporary file is not owner-only");
            }
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null
                || !owner.equals(view.getOwner())
                || !ownerAcl.equals(view.getAcl())) {
            throw new IOException("media temporary file is not owner-only");
        }
    }

    private static FileAttribute<List<AclEntry>> aclAttribute(List<AclEntry> acl) {
        List<AclEntry> immutable = List.copyOf(acl);
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return immutable;
            }
        };
    }

    private static UserPrincipal currentProcessOwner(Path targetDirectory)
            throws IOException {
        Path probe = Files.createTempFile("portfolio-media-owner-", ".probe");
        try {
            if (!probe.getFileSystem().equals(targetDirectory.getFileSystem())) {
                throw new IOException("media temporary filesystem is unsupported");
            }
            return Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    static InputStream open(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("media temporary file is invalid");
        }
        return Files.newInputStream(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    static void delete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException exception) {
            throw failure();
        }
    }

    static void deleteBestEffort(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // A fixed outer failure owns the error surface.
        }
    }

    static IllegalStateException failure() {
        return new IllegalStateException("MEDIA_FINALIZATION_FAILED");
    }
}
