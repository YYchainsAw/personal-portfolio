package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class LocalStorageAccessPolicy implements AutoCloseable {
    private static final String UNSAFE_PATH = "LOCAL_UNSAFE_PATH";
    private static final String ROOT_MARKER_NAME = ".portfolio-storage-root@guard";
    private static final int ROOT_MARKER_BYTES = 32;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path root;
    private final FileKeyReader fileKeyReader;
    private final Object rootIdentity;
    private final boolean posixAttributes;
    private final boolean aclAttributes;
    private final UserPrincipal serviceOwner;
    private final List<AclEntry> directoryAcl;
    private final List<AclEntry> fileAcl;
    private final Path rootMarker;
    private final byte[] rootMarkerToken;
    private final String volumeId;
    private final FileChannel rootMarkerGuard;

    private LocalStorageAccessPolicy(
            Path root,
            FileKeyReader fileKeyReader,
            Object rootIdentity,
            boolean posixAttributes,
            boolean aclAttributes,
            UserPrincipal serviceOwner,
            List<AclEntry> directoryAcl,
            List<AclEntry> fileAcl,
            Path rootMarker,
            byte[] rootMarkerToken,
            FileChannel rootMarkerGuard) {
        this.root = root;
        this.fileKeyReader = fileKeyReader;
        this.rootIdentity = rootIdentity;
        this.posixAttributes = posixAttributes;
        this.aclAttributes = aclAttributes;
        this.serviceOwner = serviceOwner;
        this.directoryAcl = List.copyOf(directoryAcl);
        this.fileAcl = List.copyOf(fileAcl);
        this.rootMarker = rootMarker;
        this.rootMarkerToken = rootMarkerToken.clone();
        this.volumeId = deriveVolumeId(this.rootMarkerToken);
        this.rootMarkerGuard = rootMarkerGuard;
    }

    static LocalStorageAccessPolicy initialize(Path root) {
        return initialize(root, (path, attributes) -> attributes.fileKey(),
                LocalStorageAccessPolicy::openRootMarkerGuard,
                DirectoryCreationObserver.NOOP);
    }

    static LocalStorageAccessPolicy initialize(
            Path root, FileKeyReader fileKeyReader, MarkerGuardOpener markerGuardOpener) {
        return initialize(root, fileKeyReader, markerGuardOpener, DirectoryCreationObserver.NOOP);
    }

    static LocalStorageAccessPolicy initialize(
            Path root,
            FileKeyReader fileKeyReader,
            MarkerGuardOpener markerGuardOpener,
            DirectoryCreationObserver creationObserver) {
        if (fileKeyReader == null || markerGuardOpener == null || creationObserver == null) {
            throw new IllegalArgumentException("Local storage identity strategy is required");
        }
        FileChannel markerGuard = null;
        try {
            Path existingAncestor = nearestExistingAncestor(root);
            BasicFileAttributes ancestorAttributes = readAttributes(existingAncestor);
            if (!isRealDirectory(ancestorAttributes)) {
                throw unsafePath();
            }
            boolean supportsPosix = Files.getFileStore(existingAncestor)
                    .supportsFileAttributeView(PosixFileAttributeView.class);
            boolean supportsAcl = !supportsPosix && Files.getFileStore(existingAncestor)
                    .supportsFileAttributeView(AclFileAttributeView.class);
            if (!supportsPosix && !supportsAcl) {
                throw unsafePath();
            }
            UserPrincipal owner = currentProcessOwner(root);
            List<AclEntry> secureDirectoryAcl = supportsAcl
                    ? List.of(fullControlEntry(owner, true))
                    : List.of();
            List<AclEntry> secureFileAcl = supportsAcl
                    ? List.of(fullControlEntry(owner, false))
                    : List.of();

            verifyCreationBoundary(existingAncestor, supportsPosix, supportsAcl, owner);
            createRootDirectories(
                    root,
                    supportsPosix,
                    supportsAcl,
                    owner,
                    secureDirectoryAcl,
                    creationObserver);
            verifySecureDirectory(
                    root, supportsPosix, supportsAcl, owner, secureDirectoryAcl);
            verifyParentChain(root, supportsPosix, supportsAcl, owner);

            BasicFileAttributes attributes = readAttributes(root);
            Path marker = root.resolve(ROOT_MARKER_NAME);
            byte[] markerToken = initializeRootMarker(
                    marker, supportsPosix, supportsAcl, owner, secureFileAcl);
            Object identity = fileKeyReader.read(root, attributes);
            if (identity == null) {
                markerGuard = markerGuardOpener.open(marker, supportsAcl);
                if (markerGuard == null || !markerGuard.isOpen()) {
                    throw unsafePath();
                }
            }
            return new LocalStorageAccessPolicy(
                    root,
                    fileKeyReader,
                    identity,
                    supportsPosix,
                    supportsAcl,
                    owner,
                    secureDirectoryAcl,
                    secureFileAcl,
                    marker,
                    markerToken,
                    markerGuard);
        } catch (StorageException exception) {
            closeQuietly(markerGuard);
            throw exception;
        } catch (IOException exception) {
            closeQuietly(markerGuard);
            throw new StorageException("LOCAL_INITIALIZATION_FAILED", exception);
        }
    }

    Path root() {
        return root;
    }

    String verifiedVolumeId() throws IOException {
        verifyRoot();
        return volumeId;
    }

    void verifyRoot() throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = readAttributes(root);
        } catch (NoSuchFileException exception) {
            throw unsafePath();
        }
        if (!isRealDirectory(attributes)) {
            throw unsafePath();
        }
        verifySecureDirectory(
                root, posixAttributes, aclAttributes, serviceOwner, directoryAcl);
        try {
            verifySecureFile(
                    rootMarker, posixAttributes, aclAttributes, serviceOwner, fileAcl);
            Object currentIdentity = fileKeyReader.read(root, attributes);
            if (rootIdentity != null) {
                if (!rootIdentity.equals(currentIdentity)) {
                    throw unsafePath();
                }
            } else if (currentIdentity != null
                    || rootMarkerGuard == null
                    || !rootMarkerGuard.isOpen()) {
                throw unsafePath();
            }
            if (!Arrays.equals(rootMarkerToken, readRootMarker(rootMarker))) {
                throw unsafePath();
            }
        } catch (NoSuchFileException exception) {
            throw unsafePath();
        }
    }

    void verifyDirectory(Path directory) throws IOException {
        verifySecureDirectory(
                directory, posixAttributes, aclAttributes, serviceOwner, directoryAcl);
    }

    void createDirectory(Path directory) throws IOException {
        try {
            createSecureDirectory(directory, posixAttributes, aclAttributes, directoryAcl);
            verifyDirectory(directory);
            syncDirectory(directory.getParent());
        } catch (FileAlreadyExistsException exception) {
            verifyDirectory(directory);
        }
    }

    FileChannel createFile(Path path) throws IOException {
        Set<OpenOption> options = Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS);
        if (posixAttributes) {
            return FileChannel.open(
                    path, options, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        }
        if (aclAttributes) {
            return FileChannel.open(path, options, aclAttribute(fileAcl));
        }
        throw unsafePath();
    }

    void verifyFile(Path path) throws IOException {
        verifySecureFile(path, posixAttributes, aclAttributes, serviceOwner, fileAcl);
    }

    void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, READ)) {
            channel.force(true);
        } catch (AccessDeniedException exception) {
            if (!isWindowsNtfs(directory)) {
                throw exception;
            }
        } catch (UnsupportedOperationException exception) {
            // The provider explicitly reports that directory channels are unsupported.
        }
    }

    @Override
    public void close() throws IOException {
        if (rootMarkerGuard != null) {
            rootMarkerGuard.close();
        }
    }

    private static void createRootDirectories(
            Path root,
            boolean supportsPosix,
            boolean supportsAcl,
            UserPrincipal owner,
            List<AclEntry> secureDirectoryAcl,
            DirectoryCreationObserver creationObserver) throws IOException {
        List<Path> missing = new ArrayList<>();
        Path current = root;
        while (true) {
            try {
                BasicFileAttributes attributes = readAttributes(current);
                if (!isRealDirectory(attributes)) {
                    throw unsafePath();
                }
                break;
            } catch (NoSuchFileException exception) {
                missing.add(current);
                current = current.getParent();
                if (current == null) {
                    throw unsafePath();
                }
            }
        }
        Collections.reverse(missing);
        for (Path directory : missing) {
            creationObserver.beforeCreate(directory.getParent(), directory);
            verifyCreationBoundary(
                    directory.getParent(), supportsPosix, supportsAcl, owner);
            try {
                createSecureDirectory(
                        directory, supportsPosix, supportsAcl, secureDirectoryAcl);
            } catch (FileAlreadyExistsException exception) {
                // A concurrent creator never gains permission-repair treatment.
            }
            verifySecureDirectory(
                    directory, supportsPosix, supportsAcl, owner, secureDirectoryAcl);
        }
    }

    private static Path nearestExistingAncestor(Path path) throws IOException {
        Path current = path;
        while (current != null) {
            try {
                readAttributes(current);
                return current;
            } catch (NoSuchFileException exception) {
                current = current.getParent();
            }
        }
        throw unsafePath();
    }

    private static UserPrincipal currentProcessOwner(Path storageRoot) throws IOException {
        Path probe = Files.createTempFile("portfolio-storage-owner-", ".probe");
        try {
            if (!probe.getFileSystem().equals(storageRoot.getFileSystem())) {
                throw unsafePath();
            }
            return Files.getOwner(probe, NOFOLLOW_LINKS);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private static void createSecureDirectory(
            Path directory,
            boolean supportsPosix,
            boolean supportsAcl,
            List<AclEntry> secureDirectoryAcl) throws IOException {
        if (supportsPosix) {
            Files.createDirectory(
                    directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        } else if (supportsAcl) {
            Files.createDirectory(directory, aclAttribute(secureDirectoryAcl));
        } else {
            throw unsafePath();
        }
    }

    private static void verifySecureDirectory(
            Path directory,
            boolean supportsPosix,
            boolean supportsAcl,
            UserPrincipal owner,
            List<AclEntry> secureDirectoryAcl) throws IOException {
        BasicFileAttributes attributes = readAttributes(directory);
        if (!isRealDirectory(attributes)) {
            throw unsafePath();
        }
        requireOwner(directory, owner);
        if (supportsPosix) {
            if (!Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS)
                    .equals(DIRECTORY_PERMISSIONS)) {
                throw unsafePath();
            }
        } else if (supportsAcl) {
            verifyAcl(directory, secureDirectoryAcl, owner);
        } else {
            throw unsafePath();
        }
    }

    private static void verifySecureFile(
            Path path,
            boolean supportsPosix,
            boolean supportsAcl,
            UserPrincipal owner,
            List<AclEntry> secureFileAcl) throws IOException {
        requireRegularFile(path);
        requireOwner(path, owner);
        if (supportsPosix) {
            if (!Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).equals(FILE_PERMISSIONS)) {
                throw unsafePath();
            }
        } else if (supportsAcl) {
            verifyAcl(path, secureFileAcl, owner);
        } else {
            throw unsafePath();
        }
    }

    private static void requireOwner(Path path, UserPrincipal expected) throws IOException {
        if (!expected.equals(Files.getOwner(path, NOFOLLOW_LINKS))) {
            throw unsafePath();
        }
    }

    private static void verifyAcl(
            Path path, List<AclEntry> expected, UserPrincipal owner) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class, NOFOLLOW_LINKS);
        if (view == null || !owner.equals(view.getOwner()) || !view.getAcl().equals(expected)) {
            throw unsafePath();
        }
    }

    private static FileAttribute<List<AclEntry>> aclAttribute(List<AclEntry> acl) {
        List<AclEntry> immutableAcl = List.copyOf(acl);
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return immutableAcl;
            }
        };
    }

    private static AclEntry fullControlEntry(UserPrincipal principal, boolean directory) {
        AclEntry.Builder builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            builder.setFlags(Set.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT));
        }
        return builder.build();
    }

    private static void verifyParentChain(
            Path configuredRoot,
            boolean supportsPosix,
            boolean supportsAcl,
            UserPrincipal serviceOwner) throws IOException {
        Path child = configuredRoot;
        Path parent = child.getParent();
        while (parent != null) {
            BasicFileAttributes parentAttributes = readAttributes(parent);
            BasicFileAttributes childAttributes = readAttributes(child);
            if (!isRealDirectory(parentAttributes) || !isRealDirectory(childAttributes)) {
                throw unsafePath();
            }
            if (supportsPosix) {
                verifyPosixParent(parent, child, serviceOwner);
            } else if (supportsAcl) {
                verifyAclParent(parent, child, serviceOwner);
            } else {
                throw unsafePath();
            }
            child = parent;
            parent = parent.getParent();
        }
    }

    private static void verifyCreationBoundary(
            Path existingAncestor,
            boolean supportsPosix,
            boolean supportsAcl,
            UserPrincipal serviceOwner) throws IOException {
        BasicFileAttributes attributes = readAttributes(existingAncestor);
        if (!isRealDirectory(attributes)) {
            throw unsafePath();
        }
        verifyParentChain(existingAncestor, supportsPosix, supportsAcl, serviceOwner);
        if (supportsPosix) {
            verifyPosixCreationParent(existingAncestor, serviceOwner);
        } else if (supportsAcl) {
            verifyAclCreationParent(existingAncestor, serviceOwner);
        } else {
            throw unsafePath();
        }
    }

    private static void verifyPosixCreationParent(
            Path parent, UserPrincipal serviceOwner) throws IOException {
        PosixFileAttributes attributes = Files.readAttributes(
                parent, PosixFileAttributes.class, NOFOLLOW_LINKS);
        if (!isTrustedPosixOwner(attributes.owner(), serviceOwner)) {
            throw unsafePath();
        }
        Set<PosixFilePermission> permissions = attributes.permissions();
        boolean groupCanReplace = permissions.contains(PosixFilePermission.GROUP_WRITE)
                && permissions.contains(PosixFilePermission.GROUP_EXECUTE);
        boolean othersCanReplace = permissions.contains(PosixFilePermission.OTHERS_WRITE)
                && permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
        if ((groupCanReplace || othersCanReplace)
                && !stickyDirectoryProtects(parent, serviceOwner, serviceOwner)) {
            throw unsafePath();
        }
    }

    private static void verifyAclCreationParent(
            Path parent, UserPrincipal serviceOwner) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(
                parent, AclFileAttributeView.class, NOFOLLOW_LINKS);
        Set<AclEntryPermission> dangerous = EnumSet.of(
                AclEntryPermission.DELETE_CHILD,
                AclEntryPermission.WRITE_ACL,
                AclEntryPermission.WRITE_OWNER);
        if (view == null
                || !isTrustedWindowsPrincipal(view.getOwner(), serviceOwner)
                || hasUntrustedAllow(view.getAcl(), dangerous, serviceOwner)) {
            throw unsafePath();
        }
    }

    private static void verifyPosixParent(
            Path parent, Path child, UserPrincipal serviceOwner) throws IOException {
        PosixFileAttributes parentAttributes = Files.readAttributes(
                parent, PosixFileAttributes.class, NOFOLLOW_LINKS);
        PosixFileAttributes childAttributes = Files.readAttributes(
                child, PosixFileAttributes.class, NOFOLLOW_LINKS);
        if (!isTrustedPosixOwner(parentAttributes.owner(), serviceOwner)
                || !isTrustedPosixOwner(childAttributes.owner(), serviceOwner)) {
            throw unsafePath();
        }
        Set<PosixFilePermission> permissions = parentAttributes.permissions();
        boolean groupCanReplace = permissions.contains(PosixFilePermission.GROUP_WRITE)
                && permissions.contains(PosixFilePermission.GROUP_EXECUTE);
        boolean othersCanReplace = permissions.contains(PosixFilePermission.OTHERS_WRITE)
                && permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
        if ((groupCanReplace || othersCanReplace)
                && !stickyDirectoryProtects(parent, childAttributes.owner(), serviceOwner)) {
            throw unsafePath();
        }
    }

    private static boolean stickyDirectoryProtects(
            Path directory, UserPrincipal childOwner, UserPrincipal serviceOwner) throws IOException {
        if (!isTrustedPosixOwner(childOwner, serviceOwner)) {
            return false;
        }
        try {
            Object value = Files.getAttribute(directory, "unix:mode", NOFOLLOW_LINKS);
            return value instanceof Number number && (number.intValue() & 01000) != 0;
        } catch (UnsupportedOperationException exception) {
            return false;
        }
    }

    private static boolean isTrustedPosixOwner(
            UserPrincipal principal, UserPrincipal serviceOwner) {
        return serviceOwner.equals(principal) || "root".equals(principal.getName());
    }

    private static void verifyAclParent(
            Path parent, Path child, UserPrincipal serviceOwner) throws IOException {
        AclFileAttributeView parentView = Files.getFileAttributeView(
                parent, AclFileAttributeView.class, NOFOLLOW_LINKS);
        AclFileAttributeView childView = Files.getFileAttributeView(
                child, AclFileAttributeView.class, NOFOLLOW_LINKS);
        if (parentView == null
                || childView == null
                || !isTrustedWindowsPrincipal(parentView.getOwner(), serviceOwner)
                || !isTrustedWindowsPrincipal(childView.getOwner(), serviceOwner)) {
            throw unsafePath();
        }
        Set<AclEntryPermission> parentReplacement = EnumSet.of(
                AclEntryPermission.DELETE_CHILD,
                AclEntryPermission.WRITE_ACL,
                AclEntryPermission.WRITE_OWNER);
        Set<AclEntryPermission> childReplacement = EnumSet.of(
                AclEntryPermission.DELETE,
                AclEntryPermission.WRITE_ACL,
                AclEntryPermission.WRITE_OWNER);
        if (hasUntrustedAllow(parentView.getAcl(), parentReplacement, serviceOwner)
                || hasUntrustedAllow(childView.getAcl(), childReplacement, serviceOwner)) {
            throw unsafePath();
        }
    }

    private static boolean hasUntrustedAllow(
            List<AclEntry> acl,
            Set<AclEntryPermission> dangerous,
            UserPrincipal serviceOwner) {
        for (AclEntry entry : acl) {
            if (entry.type() == AclEntryType.ALLOW
                    && !isTrustedWindowsPrincipal(entry.principal(), serviceOwner)
                    && !Collections.disjoint(entry.permissions(), dangerous)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTrustedWindowsPrincipal(
            UserPrincipal principal, UserPrincipal serviceOwner) {
        if (serviceOwner.equals(principal)) {
            return true;
        }
        String name = principal.getName().replace('/', '\\').toLowerCase(Locale.ROOT);
        return name.equals("nt authority\\system")
                || name.equals("builtin\\administrators")
                || name.equals("nt service\\trustedinstaller");
    }

    private static byte[] initializeRootMarker(
            Path marker,
            boolean supportsPosix,
            boolean supportsAcl,
            UserPrincipal owner,
            List<AclEntry> secureFileAcl) throws IOException {
        try {
            verifySecureFile(marker, supportsPosix, supportsAcl, owner, secureFileAcl);
            return readRootMarker(marker);
        } catch (NoSuchFileException exception) {
            byte[] token = new byte[ROOT_MARKER_BYTES];
            new SecureRandom().nextBytes(token);
            Set<OpenOption> options = Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS);
            FileAttribute<?> attribute = supportsPosix
                    ? PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)
                    : aclAttribute(secureFileAcl);
            try {
                try (FileChannel channel = FileChannel.open(marker, options, attribute)) {
                    ByteBuffer buffer = ByteBuffer.wrap(token);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                verifySecureFile(marker, supportsPosix, supportsAcl, owner, secureFileAcl);
                syncDirectoryStatic(marker.getParent());
                return token;
            } catch (FileAlreadyExistsException race) {
                verifySecureFile(marker, supportsPosix, supportsAcl, owner, secureFileAcl);
                return readRootMarker(marker);
            }
        }
    }

    private static byte[] readRootMarker(Path marker) throws IOException {
        try (FileChannel channel = FileChannel.open(marker, Set.of(READ, NOFOLLOW_LINKS))) {
            if (channel.size() != ROOT_MARKER_BYTES) {
                throw unsafePath();
            }
            byte[] token = new byte[ROOT_MARKER_BYTES];
            ByteBuffer buffer = ByteBuffer.wrap(token);
            while (buffer.hasRemaining()) {
                int count = channel.read(buffer);
                if (count < 0) {
                    throw unsafePath();
                }
            }
            return token;
        }
    }

    private static String deriveVolumeId(byte[] markerToken) {
        if (markerToken == null || markerToken.length != ROOT_MARKER_BYTES) {
            throw unsafePath();
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(markerToken));
        } catch (NoSuchAlgorithmException exception) {
            throw new StorageException("LOCAL_INITIALIZATION_FAILED", exception);
        }
    }

    private static FileChannel openRootMarkerGuard(Path marker, boolean windowsAcl)
            throws IOException {
        Set<OpenOption> options = windowsAcl
                ? Set.of(READ, NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE)
                : Set.of(READ, NOFOLLOW_LINKS);
        return FileChannel.open(marker, options);
    }

    private static void syncDirectoryStatic(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, READ)) {
            channel.force(true);
        } catch (AccessDeniedException exception) {
            if (!isWindowsNtfs(directory)) {
                throw exception;
            }
        } catch (UnsupportedOperationException exception) {
            // The provider explicitly reports that directory channels are unsupported.
        }
    }

    private static boolean isWindowsNtfs(Path directory) throws IOException {
        return "\\".equals(directory.getFileSystem().getSeparator())
                && "NTFS".equalsIgnoreCase(Files.getFileStore(directory).type());
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

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Preserve the fixed primary initialization failure.
        }
    }

    private static StorageException unsafePath() {
        return new StorageException(UNSAFE_PATH);
    }

    @FunctionalInterface
    interface FileKeyReader {
        Object read(Path path, BasicFileAttributes attributes) throws IOException;
    }

    @FunctionalInterface
    interface MarkerGuardOpener {
        FileChannel open(Path marker, boolean windowsAcl) throws IOException;
    }

    @FunctionalInterface
    interface DirectoryCreationObserver {
        DirectoryCreationObserver NOOP = (parent, directory) -> {};

        void beforeCreate(Path parent, Path directory) throws IOException;
    }
}
