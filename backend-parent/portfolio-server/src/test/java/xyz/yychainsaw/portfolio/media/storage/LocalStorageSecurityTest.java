package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LocalStorageSecurityTest {
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final long MAX_CONTENT_LENGTH = 5L * 1024 * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    private final List<LocalStorageService> openServices = new ArrayList<>();
    private final List<Path> externalCleanup = new ArrayList<>();
    private Path storageBoundary;

    @AfterEach
    void closeServicesAndRemoveExternalTestDirectories() throws Exception {
        for (int index = openServices.size() - 1; index >= 0; index--) {
            openServices.get(index).close();
        }
        for (int index = externalCleanup.size() - 1; index >= 0; index--) {
            deleteTree(externalCleanup.get(index));
        }
    }

    @Test
    void putClosesTheOwnedInputBeforePublishingTheTarget() {
        Path root = storageRoot();
        Path target = root.resolve("asset.bin");
        CloseObservingInputStream input = new CloseObservingInputStream(
                "owned".getBytes(UTF_8), target);

        service(root).put("asset.bin", input, 5, CONTENT_TYPE);

        assertThat(input.targetExistedWhenClosed()).isFalse();
        assertThat(Files.exists(target, NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void maximumLengthWindowsFinalSegmentCanBePutOpenedAndDeleted() throws Exception {
        assumeWindowsNtfsSupport();
        Path root = storageRoot();
        LocalStorageService service = service(root);
        String key = "a".repeat(251) + ".bin";
        CloseTrackingInputStream input = tracking(new byte[] {7});
        AtomicReference<StoredObject> stored = new AtomicReference<>();

        assertThatCode(() -> stored.set(service.put(key, input, 1, CONTENT_TYPE)))
                .doesNotThrowAnyException();

        assertThat(stored.get().objectKey()).isEqualTo(key);
        assertThat(input.closeCount()).isOne();
        try (StorageRead read = service.open(key, Optional.empty())) {
            assertThat(read.inputStream().readAllBytes()).containsExactly(7);
        }
        service.delete(key);
        assertThat(service.exists(key)).isFalse();
        assertNoReservedAliases(root);
    }

    @Test
    void overMaximumWindowsFinalSegmentIsRejectedAndClosesItsCaller() throws Exception {
        assumeWindowsNtfsSupport();
        Path root = storageRoot();
        String key = "a".repeat(252) + ".bin";
        CloseTrackingInputStream input = tracking(new byte[] {7});

        assertThatThrownBy(() -> service(root).put(key, input, 1, CONTENT_TYPE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid storage object key");

        assertThat(input.closeCount()).isOne();
        assertNoReservedAliases(root);
    }

    @Test
    void maximumLengthWindowsFinalSegmentUsesShortIndependentNullIdentityAliases()
            throws Exception {
        assumeWindowsNtfsSupport();
        Path root = storageRoot();
        String key = "a".repeat(251) + ".bin";
        AtomicReference<Path> part = new AtomicReference<>();
        AtomicReference<Path> guard = new AtomicReference<>();
        AtomicReference<StoredObject> stored = new AtomicReference<>();
        LocalStorageService.OperationObserver observer = new LocalStorageService.OperationObserver() {
            @Override
            public Object publicationFileKey(Path path, BasicFileAttributes attributes) {
                part.set(path);
                return null;
            }

            @Override
            public void createPublicationIdentityGuard(Path guardPath, Path source)
                    throws IOException {
                guard.set(guardPath);
                Files.createLink(guardPath, source);
            }
        };
        LocalStorageService service = service(root, observer);

        assertThatCode(() -> stored.set(
                service.put(key, tracking(new byte[] {9}), 1, CONTENT_TYPE)))
                .doesNotThrowAnyException();

        assertAll(
                () -> assertThat(stored.get().objectKey()).isEqualTo(key),
                () -> assertReservedAlias(part.get(), root, "@part-", 42),
                () -> assertReservedAlias(guard.get(), root, "@identity-", 46),
                () -> assertThat(Files.exists(part.get(), NOFOLLOW_LINKS)).isFalse(),
                () -> assertThat(Files.exists(guard.get(), NOFOLLOW_LINKS)).isFalse());
        try (StorageRead read = service.open(key, Optional.empty())) {
            assertThat(read.inputStream().readAllBytes()).containsExactly(9);
        }
        service.delete(key);
        assertNoReservedAliases(root);
    }

    @Test
    void existsRejectsInvalidLengthWindowsFilesButDeleteCanRemoveThem() throws Exception {
        assumeWindowsNtfsSupport();
        Path root = storageRoot();
        LocalStorageService service = service(root);
        service.exists("initialize.bin");
        Path empty = Files.write(root.resolve("empty.bin"), new byte[0], CREATE_NEW);
        installExactWindowsAcl(empty, false);
        Path oversized = createOversizedWindowsFile(root.resolve("oversized.bin"));

        assertStorageFailure(() -> service.exists("empty.bin"), "LOCAL_UNSAFE_PATH");
        assertStorageFailure(() -> service.exists("oversized.bin"), "LOCAL_UNSAFE_PATH");

        service.delete("empty.bin");
        service.delete("oversized.bin");
        assertAll(
                () -> assertThat(Files.exists(empty, NOFOLLOW_LINKS)).isFalse(),
                () -> assertThat(Files.exists(oversized, NOFOLLOW_LINKS)).isFalse());
    }

    @Test
    void openAndCopyRejectAnOversizedWindowsFileBeforeReadingOrTargetMutation()
            throws Exception {
        assumeWindowsNtfsSupport();
        Path root = storageRoot();
        LocalStorageService service = service(root);
        service.exists("initialize.bin");
        Path oversized = createOversizedWindowsFile(root.resolve("oversized.bin"));

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertStorageFailure(
                () -> service.open("oversized.bin", Optional.empty()), "LOCAL_UNSAFE_PATH"));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertStorageFailure(
                () -> service.copy("oversized.bin", "copy.bin"), "LOCAL_UNSAFE_PATH"));

        assertAll(
                () -> assertThat(Files.exists(root.resolve("copy.bin"), NOFOLLOW_LINKS)).isFalse(),
                () -> assertNoReservedAliases(root));
        service.delete("oversized.bin");
        assertThat(Files.exists(oversized, NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void closeFailureHappensBeforePublicationAndLeavesNoTarget() throws Exception {
        Path root = storageRoot();
        CloseFailingInputStream input = new CloseFailingInputStream("owned".getBytes(UTF_8));

        assertStorageFailure(
                () -> service(root).put("asset.bin", input, 5, CONTENT_TYPE),
                "LOCAL_WRITE_FAILED");

        assertThat(input.closeCount()).isOne();
        assertThat(Files.exists(root.resolve("asset.bin"), NOFOLLOW_LINKS)).isFalse();
        assertNoTemporaryFiles(root);
    }

    @Test
    void copyClosesItsSourceChannelBeforeHardLinkPublication() throws Exception {
        Path root = storageRoot();
        service(root).put("source.bin", tracking("source".getBytes(UTF_8)), 6, CONTENT_TYPE);
        AtomicBoolean observed = new AtomicBoolean();
        LocalStorageService.OperationObserver observer = new LocalStorageService.OperationObserver() {
            @Override
            public void copySourceClosed(SeekableByteChannel source, Path target) {
                assertThat(source.isOpen()).isFalse();
                assertThat(Files.exists(target, NOFOLLOW_LINKS)).isFalse();
                observed.set(true);
            }
        };

        service(root, observer).copy("source.bin", "target.bin");

        assertThat(observed).isTrue();
        assertThat(Files.readAllBytes(root.resolve("target.bin")))
                .isEqualTo("source".getBytes(UTF_8));
    }

    @Test
    void failedCleanupDoesNotDeleteAReplacementAtTheTemporaryName() throws Exception {
        Path root = storageRoot();
        AtomicReference<Path> temporaryName = new AtomicReference<>();
        AtomicReference<Path> originalAlias = new AtomicReference<>();
        byte[] foreign = "foreign".getBytes(UTF_8);
        LocalStorageService.OperationObserver observer = new LocalStorageService.OperationObserver() {
            @Override
            public void temporaryReady(Path temporary, Path target) throws IOException {
                temporaryName.set(temporary);
                originalAlias.set(temporary.resolveSibling(temporary.getFileName() + ".original"));
                Files.move(temporary, originalAlias.get());
                Files.write(temporary, foreign, CREATE_NEW);
            }
        };

        assertStorageFailure(
                () -> service(root, observer).put(
                        "asset.bin", tracking("owned".getBytes(UTF_8)), 5, CONTENT_TYPE),
                "LOCAL_UNSAFE_PATH");

        assertThat(Files.readAllBytes(temporaryName.get())).isEqualTo(foreign);
        assertThat(Files.exists(root.resolve("asset.bin"), NOFOLLOW_LINKS)).isFalse();
        Files.delete(temporaryName.get());
        Files.delete(originalAlias.get());
    }

    @Test
    void failedPublishedTargetValidationDoesNotDeleteAReplacementNode() throws Exception {
        Path root = storageRoot();
        AtomicReference<Path> originalAlias = new AtomicReference<>();
        byte[] foreign = "foreign".getBytes(UTF_8);
        LocalStorageService.OperationObserver observer = new LocalStorageService.OperationObserver() {
            @Override
            public void targetLinked(Path temporary, Path target) throws IOException {
                originalAlias.set(target.resolveSibling(target.getFileName() + ".original"));
                Files.move(target, originalAlias.get());
                Files.write(target, foreign, CREATE_NEW);
            }
        };

        assertStorageFailure(
                () -> service(root, observer).put(
                        "asset.bin", tracking("owned".getBytes(UTF_8)), 5, CONTENT_TYPE),
                "LOCAL_UNSAFE_PATH");

        assertThat(Files.readAllBytes(root.resolve("asset.bin"))).isEqualTo(foreign);
        Files.delete(root.resolve("asset.bin"));
        Files.delete(originalAlias.get());
    }

    @Test
    void temporaryCleanupPreservesThePrimaryFailureAndForeignDirectory() throws Exception {
        Path root = storageRoot();
        AtomicReference<Path> temporaryName = new AtomicReference<>();
        LocalStorageService.OperationObserver observer = new LocalStorageService.OperationObserver() {
            @Override
            public void temporaryReady(Path temporary, Path target) throws IOException {
                temporaryName.set(temporary);
                Files.move(temporary, temporary.resolveSibling(temporary.getFileName() + ".original"));
                Files.createDirectory(temporary);
                throw new IOException("synthetic observer failure");
            }
        };

        assertStorageFailure(
                () -> service(root, observer).put(
                        "asset.bin", tracking("owned".getBytes(UTF_8)), 5, CONTENT_TYPE),
                "LOCAL_WRITE_FAILED");

        assertThat(Files.isDirectory(temporaryName.get(), NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.exists(root.resolve("asset.bin"), NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void targetCleanupPreservesThePrimaryFailureAndForeignDirectory() throws Exception {
        Path root = storageRoot();
        Path target = root.resolve("asset.bin");
        LocalStorageService.OperationObserver observer = new LocalStorageService.OperationObserver() {
            @Override
            public void targetLinked(Path temporary, Path publishedTarget) throws IOException {
                Files.move(
                        publishedTarget,
                        publishedTarget.resolveSibling(publishedTarget.getFileName() + ".original"));
                Files.createDirectory(publishedTarget);
                throw new IOException("synthetic observer failure");
            }
        };

        assertStorageFailure(
                () -> service(root, observer).put(
                        "asset.bin", tracking("owned".getBytes(UTF_8)), 5, CONTENT_TYPE),
                "LOCAL_WRITE_FAILED");

        assertThat(Files.isDirectory(target, NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void postLinkFailureRollsBackOnlyTheTargetCreatedByThisPublication() throws Exception {
        Path root = storageRoot();
        LocalStorageService.OperationObserver observer = new LocalStorageService.OperationObserver() {
            @Override
            public void targetLinked(Path temporary, Path target) throws IOException {
                throw new IOException("synthetic post-link failure");
            }
        };

        assertStorageFailure(
                () -> service(root, observer).put(
                        "asset.bin", tracking("owned".getBytes(UTF_8)), 5, CONTENT_TYPE),
                "LOCAL_WRITE_FAILED");

        assertThat(Files.exists(root.resolve("asset.bin"), NOFOLLOW_LINKS)).isFalse();
        assertNoTemporaryFiles(root);
    }

    @Test
    void completedPublicationSurvivesTemporaryAliasCleanupFailure() throws Exception {
        assumeWindowsAclSupport();
        Path root = storageRoot();
        AtomicReference<Path> temporaryName = new AtomicReference<>();
        AtomicReference<FileChannel> deletionBlocker = new AtomicReference<>();
        LocalStorageService.OperationObserver observer = new LocalStorageService.OperationObserver() {
            @Override
            public void targetLinked(Path temporary, Path target) throws IOException {
                temporaryName.set(temporary);
                deletionBlocker.set(FileChannel.open(temporary, Set.of(
                        READ, NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE)));
            }
        };

        try {
            StoredObject stored = service(root, observer).put(
                    "asset.bin", tracking("owned".getBytes(UTF_8)), 5, CONTENT_TYPE);

            assertThat(stored.objectKey()).isEqualTo("asset.bin");
            assertThat(Files.readString(root.resolve("asset.bin"))).isEqualTo("owned");
            assertThat(Files.exists(temporaryName.get(), NOFOLLOW_LINKS)).isTrue();
        } finally {
            if (deletionBlocker.get() != null) {
                deletionBlocker.get().close();
            }
            if (temporaryName.get() != null) {
                Files.deleteIfExists(temporaryName.get());
            }
        }
    }

    @Test
    void forcedNullIdentityRollsBackAfterTemporaryDeletionAndBeforeCommitSync() throws Exception {
        Path root = storageRoot();
        AtomicReference<Path> temporaryName = new AtomicReference<>();
        LocalStorageService.OperationObserver observer = new LocalStorageService.OperationObserver() {
            @Override
            public Object publicationFileKey(Path path, BasicFileAttributes attributes) {
                return null;
            }

            @Override
            public void temporaryReady(Path temporary, Path target) {
                temporaryName.set(temporary);
            }

            @Override
            public void beforePublicationCommit(Path target) throws IOException {
                assertThat(Files.exists(temporaryName.get(), NOFOLLOW_LINKS)).isFalse();
                throw new IOException("synthetic directory sync failure");
            }
        };

        assertStorageFailure(
                () -> service(root, observer).put(
                        "asset.bin", tracking("owned".getBytes(UTF_8)), 5, CONTENT_TYPE),
                "LOCAL_WRITE_FAILED");

        assertThat(Files.exists(root.resolve("asset.bin"), NOFOLLOW_LINKS)).isFalse();
        assertNoPublicationArtifacts(root);
    }

    @Test
    void unavailableIdentityHardLinkDoesNotLeakTheCreatedTemporaryFile() throws Exception {
        Path root = storageRoot();
        LocalStorageService.OperationObserver observer = new LocalStorageService.OperationObserver() {
            @Override
            public Object publicationFileKey(Path path, BasicFileAttributes attributes) {
                return null;
            }

            @Override
            public void createPublicationIdentityGuard(Path guard, Path source) throws IOException {
                throw new IOException("synthetic hard-link unavailable");
            }
        };

        assertStorageFailure(
                () -> service(root, observer).put(
                        "asset.bin", tracking("owned".getBytes(UTF_8)), 5, CONTENT_TYPE),
                "LOCAL_WRITE_FAILED");

        assertThat(Files.exists(root.resolve("asset.bin"), NOFOLLOW_LINKS)).isFalse();
        assertNoPublicationArtifacts(root);
    }

    @Test
    void failedIdentityGuardValidationDoesNotLeakTheGuardOrTemporaryFile() throws Exception {
        Path root = storageRoot();
        LocalStorageService.OperationObserver observer = new LocalStorageService.OperationObserver() {
            @Override
            public Object publicationFileKey(Path path, BasicFileAttributes attributes) {
                return null;
            }

            @Override
            public void createPublicationIdentityGuard(Path guard, Path source) throws IOException {
                Files.createLink(guard, source);
                throw new IOException("synthetic guard validation failure");
            }
        };

        assertStorageFailure(
                () -> service(root, observer).put(
                        "asset.bin", tracking("owned".getBytes(UTF_8)), 5, CONTENT_TYPE),
                "LOCAL_WRITE_FAILED");

        assertThat(Files.exists(root.resolve("asset.bin"), NOFOLLOW_LINKS)).isFalse();
        assertNoPublicationArtifacts(root);
    }

    @Test
    void rejectsIntermediateSymbolicLinksForEveryFilesystemOperation() throws Exception {
        Path root = storageRoot();
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(outside);
        LocalStorageService service = service(root);
        createSymbolicLinkOrSkip(root.resolve("linked"), outside);

        assertStorageFailure(() -> service.put(
                "linked/write.bin", tracking(new byte[] {1}), 1, CONTENT_TYPE), "LOCAL_UNSAFE_PATH");
        assertStorageFailure(
                () -> service.open("linked/read.bin", Optional.empty()), "LOCAL_UNSAFE_PATH");
        assertStorageFailure(() -> service.exists("linked/read.bin"), "LOCAL_UNSAFE_PATH");
        assertStorageFailure(() -> service.delete("linked/read.bin"), "LOCAL_UNSAFE_PATH");
        assertStorageFailure(() -> service.copy("linked/read.bin", "copy.bin"), "LOCAL_UNSAFE_PATH");
        assertThat(Files.list(outside)).isEmpty();
    }

    @Test
    void rejectsFinalSymbolicLinksWithoutDeletingOrFollowingTheirTarget() throws Exception {
        Path root = storageRoot();
        Path outside = temporaryDirectory.resolve("outside.bin");
        Files.write(outside, "outside".getBytes(UTF_8));
        LocalStorageService service = service(root);
        Path link = root.resolve("link.bin");
        createSymbolicLinkOrSkip(link, outside);

        assertStorageFailure(() -> service.open("link.bin", Optional.empty()), "LOCAL_UNSAFE_PATH");
        assertStorageFailure(() -> service.exists("link.bin"), "LOCAL_UNSAFE_PATH");
        assertStorageFailure(() -> service.delete("link.bin"), "LOCAL_UNSAFE_PATH");
        assertStorageFailure(() -> service.copy("link.bin", "copy.bin"), "LOCAL_UNSAFE_PATH");
        assertThat(Files.readString(outside)).isEqualTo("outside");
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }

    @Test
    void missingRootMarkerMapsToUnsafeAtThePolicyIdentityBoundary() throws Exception {
        Path root = storageRoot();
        try (LocalStorageAccessPolicy policy = LocalStorageAccessPolicy.initialize(
                root,
                (path, attributes) -> "stable-root-identity",
                (marker, windowsAcl) -> {
                    throw new AssertionError("A stable root identity must not open a marker guard");
                })) {
            policy.verifyRoot();
            Files.delete(root.resolve(".portfolio-storage-root@guard"));

            assertStorageFailure(policy::verifyRoot, "LOCAL_UNSAFE_PATH");
        }
    }

    @ParameterizedTest
    @EnumSource(MissingMarkerOperation.class)
    void missingRootMarkerIsUnsafeForEveryFilesystemOperation(
            MissingMarkerOperation operation) throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        byte[] original = "original".getBytes(UTF_8);
        service.put("source.bin", tracking(original), original.length, CONTENT_TYPE);
        Path source = root.resolve("source.bin");
        Path created = root.resolve("created.bin");
        Path copied = root.resolve("copy.bin");
        CloseTrackingInputStream input = tracking(new byte[] {1});
        service.close();
        Files.delete(root.resolve(".portfolio-storage-root@guard"));

        assertAll(
                () -> assertStorageFailure(() -> {
                    switch (operation) {
                        case EXISTS -> service.exists("source.bin");
                        case PUT -> service.put("created.bin", input, 1, CONTENT_TYPE);
                        case OPEN -> {
                            try (StorageRead ignored = service.open(
                                    "source.bin", Optional.empty())) {
                                // A vulnerable implementation reaches this block; close avoids a leak.
                            }
                        }
                        case COPY -> service.copy("source.bin", "copy.bin");
                        case DELETE -> service.delete("source.bin");
                    }
                }, "LOCAL_UNSAFE_PATH"),
                () -> assertThat(input.closeCount())
                        .isEqualTo(operation == MissingMarkerOperation.PUT ? 1 : 0),
                () -> assertThat(Files.readAllBytes(source)).isEqualTo(original),
                () -> assertThat(Files.exists(created, NOFOLLOW_LINKS)).isFalse(),
                () -> assertThat(Files.exists(copied, NOFOLLOW_LINKS)).isFalse(),
                () -> assertNoReservedAliases(root));
    }

    @Test
    void retainedMarkerGuardPreventsRootReplacementOnWindows() throws Exception {
        assumeWindowsAclSupport();
        Path root = storageRoot();
        service(root);

        assertThatThrownBy(() -> Files.move(root, root.resolveSibling("original-store")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(Files.isDirectory(root, NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void markerFallbackRejectsReplacementEvenWhenCreationTimeIsRestored() throws Exception {
        assumeWindowsAclSupport();
        Path root = storageRoot();
        Path sentinel = Files.write(
                root.getParent().resolve("test-marker-guard.bin"), new byte[] {1}, CREATE_NEW);
        LocalStorageAccessPolicy policy = LocalStorageAccessPolicy.initialize(
                root,
                (path, attributes) -> null,
                (marker, windowsAcl) -> FileChannel.open(sentinel, Set.of(READ, NOFOLLOW_LINKS)));
        try {
            FileTime creationTime = Files.readAttributes(
                    root, BasicFileAttributes.class, NOFOLLOW_LINKS).creationTime();
            List<AclEntry> secureAcl = requireAclView(root).getAcl();
            Path marker = root.resolve(".portfolio-storage-root@guard");
            List<AclEntry> secureMarkerAcl = requireAclView(marker).getAcl();
            Path original = root.resolveSibling("original-store");
            Files.move(root, original);
            Files.createDirectory(root);
            requireAclView(root).setAcl(secureAcl);
            Path replacementMarker = Files.write(
                    root.resolve(".portfolio-storage-root@guard"), new byte[32], CREATE_NEW);
            requireAclView(replacementMarker).setAcl(secureMarkerAcl);
            Files.setAttribute(root, "basic:creationTime", creationTime, NOFOLLOW_LINKS);

            assertStorageFailure(policy::verifyRoot, "LOCAL_UNSAFE_PATH");
        } finally {
            policy.close();
        }
    }

    @Test
    void rejectsASymbolicLinkAsConfiguredRoot() throws Exception {
        Path boundary = storageRoot().getParent();
        Path real = Files.createDirectory(boundary.resolve("real-store"));
        Path linkedRoot = boundary.resolve("linked-store");
        createSymbolicLinkOrSkip(linkedRoot, real);

        assertStorageFailure(() -> service(linkedRoot), "LOCAL_UNSAFE_PATH");
    }

    @Test
    void rejectsAWindowsJunctionAsConfiguredRootWithoutChangingItsTarget() throws Exception {
        assumeWindowsAclSupport();
        Path boundary = createWindowsTestBoundary();
        Path outside = Files.createDirectory(boundary.resolve("outside"));
        Path sentinel = Files.write(
                outside.resolve("sentinel.bin"), "outside".getBytes(UTF_8), CREATE_NEW);
        List<AclEntry> outsideAcl = List.copyOf(requireAclView(outside).getAcl());
        Path junction = boundary.resolve("store");
        createWindowsJunction(junction, outside);

        assertAll(
                () -> assertStorageFailure(() -> service(junction), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(listNames(outside)).containsExactly("sentinel.bin"),
                () -> assertThat(Files.readString(sentinel)).isEqualTo("outside"),
                () -> assertThat(requireAclView(outside).getAcl())
                        .containsExactlyElementsOf(outsideAcl));
    }

    @Test
    void rejectsAnIntermediateWindowsJunctionWithoutWritingIntoItsTarget() throws Exception {
        assumeWindowsAclSupport();
        Path boundary = createWindowsTestBoundary();
        Path root = Files.createDirectory(boundary.resolve("store"));
        installExactWindowsAcl(root, true);
        Path outside = Files.createDirectory(boundary.resolve("outside"));
        Path sentinel = Files.write(
                outside.resolve("sentinel.bin"), "outside".getBytes(UTF_8), CREATE_NEW);
        List<AclEntry> outsideAcl = List.copyOf(requireAclView(outside).getAcl());
        createWindowsJunction(root.resolve("linked"), outside);
        LocalStorageService service = service(root);
        CloseTrackingInputStream input = tracking(new byte[] {1});

        assertAll(
                () -> assertStorageFailure(() -> service.put(
                        "linked/created.bin", input, 1, CONTENT_TYPE), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(input.closeCount()).isOne(),
                () -> assertThat(listNames(outside)).containsExactly("sentinel.bin"),
                () -> assertThat(Files.readString(sentinel)).isEqualTo("outside"),
                () -> assertThat(requireAclView(outside).getAcl())
                        .containsExactlyElementsOf(outsideAcl));
    }

    @Test
    void revalidatesAWindowsCreationParentSwappedForAJunctionBeforeCreatingAnything()
            throws Exception {
        assumeWindowsAclSupport();
        Path container = createWindowsTestBoundary();
        Path boundary = Files.createDirectory(container.resolve("boundary"));
        installExactWindowsAcl(boundary, true);
        Path parkedBoundary = container.resolve("parked-boundary");
        Path outside = Files.createDirectory(container.resolve("outside"));
        Path sentinel = Files.write(
                outside.resolve("sentinel.bin"), "outside".getBytes(UTF_8), CREATE_NEW);
        Path root = boundary.resolve("first/second");
        AtomicBoolean swapped = new AtomicBoolean();

        assertAll(
                () -> assertStorageFailure(() -> initializeWithCreationObserver(
                        root,
                        (parent, directory) -> {
                            if (swapped.compareAndSet(false, true)) {
                                Files.move(boundary, parkedBoundary);
                                createWindowsJunction(boundary, outside);
                            }
                        }), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(swapped).isTrue(),
                () -> assertThat(listNames(parkedBoundary)).isEmpty(),
                () -> assertThat(listNames(outside)).containsExactly("sentinel.bin"),
                () -> assertThat(Files.readString(sentinel)).isEqualTo("outside"));
    }

    @Test
    void continuesAfterAnExactWindowsCreationCollision() throws Exception {
        assumeWindowsAclSupport();
        Path boundary = createWindowsTestBoundary();
        Path root = boundary.resolve("first/second");
        AtomicBoolean collided = new AtomicBoolean();

        try (LocalStorageAccessPolicy ignored = initializeWithCreationObserver(
                root,
                (parent, directory) -> {
                    if (collided.compareAndSet(false, true)) {
                        Files.createDirectory(directory);
                        installExactWindowsAcl(directory, true);
                    }
                })) {
            assertAll(
                    () -> assertThat(collided).isTrue(),
                    () -> assertThat(Files.isDirectory(root, NOFOLLOW_LINKS)).isTrue(),
                    () -> assertThat(Files.exists(
                            root.resolve(".portfolio-storage-root@guard"), NOFOLLOW_LINKS))
                            .isTrue());
        }
    }

    @Test
    void stopsAfterAWindowsJunctionCreationCollisionWithoutWritingIntoItsTarget()
            throws Exception {
        assumeWindowsAclSupport();
        Path boundary = createWindowsTestBoundary();
        Path outside = Files.createDirectory(boundary.resolve("outside"));
        Path sentinel = Files.write(
                outside.resolve("sentinel.bin"), "outside".getBytes(UTF_8), CREATE_NEW);
        Path root = boundary.resolve("first/second");
        AtomicBoolean collided = new AtomicBoolean();

        assertAll(
                () -> assertStorageFailure(() -> initializeWithCreationObserver(
                        root,
                        (parent, directory) -> {
                            if (collided.compareAndSet(false, true)) {
                                createWindowsJunction(directory, outside);
                            }
                        }), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(collided).isTrue(),
                () -> assertThat(listNames(outside)).containsExactly("sentinel.bin"),
                () -> assertThat(Files.readString(sentinel)).isEqualTo("outside"));
    }

    @Test
    void usesOwnerOnlyDirectoryAndFileModesWhenPosixAttributesAreSupported() throws Exception {
        Path root = storageRoot();
        FileStore fileStore = Files.getFileStore(temporaryDirectory);
        assumeTrue(fileStore.supportsFileAttributeView("posix"), "POSIX attributes unavailable");
        LocalStorageService service = service(root);

        service.put("nested/asset.bin", tracking(new byte[] {1}), 1, CONTENT_TYPE);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root)))
                .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(
                Files.getPosixFilePermissions(root.resolve("nested"))))
                .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(
                Files.getPosixFilePermissions(root.resolve("nested/asset.bin"))))
                .isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(
                root.resolve(".portfolio-storage-root@guard"))))
                .isEqualTo("rw-------");
    }

    @Test
    void rejectsPreexistingBroadPosixRootWithoutChangingItsMode() throws Exception {
        assumePosixSupport();
        Path root = Files.createDirectory(temporaryDirectory.resolve("broad-root"));
        Set<java.nio.file.attribute.PosixFilePermission> broadPermissions =
                PosixFilePermissions.fromString("rwxrwxrwx");
        Files.setPosixFilePermissions(root, broadPermissions);

        assertAll(
                () -> assertStorageFailure(() -> service(root), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(Files.getPosixFilePermissions(root, NOFOLLOW_LINKS))
                        .isEqualTo(broadPermissions));
    }

    @Test
    void rejectsPreexistingBroadPosixMarkerWithoutChangingItsMode() throws Exception {
        assumePosixSupport();
        Path root = Files.createDirectory(temporaryDirectory.resolve("marker-root"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path marker = Files.write(
                root.resolve(".portfolio-storage-root@guard"), new byte[32], CREATE_NEW);
        Set<java.nio.file.attribute.PosixFilePermission> broadPermissions =
                PosixFilePermissions.fromString("rw-rw-rw-");
        Files.setPosixFilePermissions(marker, broadPermissions);

        assertAll(
                () -> assertStorageFailure(() -> service(root), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(Files.getPosixFilePermissions(marker, NOFOLLOW_LINKS))
                        .isEqualTo(broadPermissions));
    }

    @Test
    void rejectsPreexistingBroadPosixDescendantWithoutChangingItsMode() throws Exception {
        assumePosixSupport();
        Path root = Files.createDirectory(temporaryDirectory.resolve("descendant-root"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path descendant = Files.createDirectory(root.resolve("nested"));
        Set<java.nio.file.attribute.PosixFilePermission> broadPermissions =
                PosixFilePermissions.fromString("rwxrwxrwx");
        Files.setPosixFilePermissions(descendant, broadPermissions);
        LocalStorageService service = service(root);

        assertAll(
                () -> assertStorageFailure(
                        () -> service.exists("nested/missing.bin"), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(Files.getPosixFilePermissions(descendant, NOFOLLOW_LINKS))
                        .isEqualTo(broadPermissions));
    }

    @Test
    void rejectsAWorldWritablePosixParentWithoutStickyProtection() throws Exception {
        assumePosixSupport();
        Path boundary = Files.createDirectory(temporaryDirectory.resolve("unsafe-parent"));
        setUnixModeOrSkip(boundary, 0777);
        Path root = boundary.resolve("store");

        assertAll(
                () -> assertStorageFailure(() -> service(root), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(Files.notExists(root, NOFOLLOW_LINKS)).isTrue());
    }

    @Test
    void acceptsAWorldWritablePosixParentWithStickyProtection() throws Exception {
        assumePosixSupport();
        Path boundary = Files.createDirectory(temporaryDirectory.resolve("sticky-parent"));
        setUnixModeOrSkip(boundary, 01777);

        LocalStorageService service = service(boundary.resolve("store"));
        service.put("asset.bin", tracking(new byte[] {1}), 1, CONTENT_TYPE);

        assertThat(Files.readAllBytes(boundary.resolve("store/asset.bin"))).containsExactly(1);
    }

    @Test
    void rejectsPreexistingBroadWindowsRootWithoutRewritingItsAcl() throws Exception {
        assumeWindowsAclSupport();
        Path boundary = createWindowsTestBoundary();
        Path root = Files.createDirectory(boundary.resolve("store"));
        installBroadWindowsAcl(root, true);
        List<AclEntry> broadAcl = List.copyOf(requireAclView(root).getAcl());

        assertAll(
                () -> assertStorageFailure(() -> service(root), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(requireAclView(root).getAcl()).containsExactlyElementsOf(broadAcl));
    }

    @Test
    void createsExactOwnerOnlyWindowsAclsForEveryCreatedNode() throws Exception {
        assumeWindowsAclSupport();
        Path boundary = createWindowsTestBoundary();
        Path root = boundary.resolve("store");

        LocalStorageService service = service(root);
        service.put("nested/asset.bin", tracking(new byte[] {1}), 1, CONTENT_TYPE);

        assertExactOwnerOnlyWindowsAcl(root, true);
        assertExactOwnerOnlyWindowsAcl(root.resolve("nested"), true);
        assertExactOwnerOnlyWindowsAcl(root.resolve("nested/asset.bin"), false);
        assertExactOwnerOnlyWindowsAcl(root.resolve(".portfolio-storage-root@guard"), false);
    }

    @Test
    void rejectsPreexistingBroadWindowsMarkerWithoutRewritingItsAcl() throws Exception {
        assumeWindowsAclSupport();
        Path boundary = createWindowsTestBoundary();
        Path root = Files.createDirectory(boundary.resolve("store"));
        installExactWindowsAcl(root, true);
        Path marker = Files.write(
                root.resolve(".portfolio-storage-root@guard"), new byte[32], CREATE_NEW);
        installBroadWindowsAcl(marker, false);
        List<AclEntry> broadAcl = List.copyOf(requireAclView(marker).getAcl());

        assertAll(
                () -> assertStorageFailure(() -> service(root), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(requireAclView(marker).getAcl())
                        .containsExactlyElementsOf(broadAcl));
    }

    @Test
    void rejectsPreexistingBroadWindowsDescendantWithoutRewritingItsAcl() throws Exception {
        assumeWindowsAclSupport();
        Path boundary = createWindowsTestBoundary();
        Path root = Files.createDirectory(boundary.resolve("store"));
        installExactWindowsAcl(root, true);
        Path descendant = Files.createDirectory(root.resolve("nested"));
        installBroadWindowsAcl(descendant, true);
        List<AclEntry> broadAcl = List.copyOf(requireAclView(descendant).getAcl());
        LocalStorageService service = service(root);

        assertAll(
                () -> assertStorageFailure(
                        () -> service.exists("nested/missing.bin"), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(requireAclView(descendant).getAcl())
                        .containsExactlyElementsOf(broadAcl));
    }

    @Test
    void rejectsAWindowsParentThatLetsAnUntrustedPrincipalReplaceTheRoot() throws Exception {
        assumeWindowsAclSupport();
        Path boundary = createWindowsTestBoundary();
        installBroadWindowsAcl(boundary, true);
        Path root = boundary.resolve("store");

        assertAll(
                () -> assertStorageFailure(() -> service(root), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(Files.notExists(root, NOFOLLOW_LINKS)).isTrue());
    }

    @Test
    void rejectsPreexistingBroadPosixHardLinkForEveryFinalFileOperation() throws Exception {
        assumePosixSupport();
        Path root = Files.createDirectory(temporaryDirectory.resolve("store"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path foreign = Files.write(
                temporaryDirectory.resolve("foreign.bin"), "foreign".getBytes(UTF_8), CREATE_NEW);
        Set<java.nio.file.attribute.PosixFilePermission> broadPermissions =
                PosixFilePermissions.fromString("rw-rw-rw-");
        Files.setPosixFilePermissions(foreign, broadPermissions);
        createHardLinkOrSkip(root.resolve("foreign.bin"), foreign);
        LocalStorageService service = service(root);

        assertUnsafeFinalFileOperationsPreserveForeignHardLink(service, root, foreign);
        assertThat(Files.getPosixFilePermissions(foreign, NOFOLLOW_LINKS))
                .isEqualTo(broadPermissions);
    }

    @Test
    void rejectsPreexistingBroadWindowsHardLinkForEveryFinalFileOperation() throws Exception {
        assumeWindowsAclSupport();
        Path boundary = createWindowsTestBoundary();
        Path root = Files.createDirectory(boundary.resolve("store"));
        installExactWindowsAcl(root, true);
        Path foreign = Files.write(
                boundary.resolve("foreign.bin"), "foreign".getBytes(UTF_8), CREATE_NEW);
        installBroadWindowsAcl(foreign, false);
        List<AclEntry> broadAcl = List.copyOf(requireAclView(foreign).getAcl());
        createHardLinkOrSkip(root.resolve("foreign.bin"), foreign);
        LocalStorageService service = service(root);

        assertUnsafeFinalFileOperationsPreserveForeignHardLink(service, root, foreign);
        assertThat(requireAclView(foreign).getAcl()).containsExactlyElementsOf(broadAcl);
    }

    private LocalStorageService service(Path root) {
        LocalStorageService service = new LocalStorageService(new LocalStorageProperties(root));
        openServices.add(service);
        return service;
    }

    private static LocalStorageAccessPolicy initializeWithCreationObserver(
            Path root,
            LocalStorageAccessPolicy.DirectoryCreationObserver observer) {
        return LocalStorageAccessPolicy.initialize(
                root,
                (path, attributes) -> attributes.fileKey(),
                (marker, windowsAcl) -> FileChannel.open(
                        marker,
                        windowsAcl
                                ? Set.of(READ, NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE)
                                : Set.of(READ, NOFOLLOW_LINKS)),
                observer);
    }

    private LocalStorageService service(
            Path root, LocalStorageService.OperationObserver observer) {
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root), observer);
        openServices.add(service);
        return service;
    }

    private Path storageRoot() {
        try {
            FileStore store = Files.getFileStore(temporaryDirectory);
            if (store.supportsFileAttributeView(AclFileAttributeView.class)
                    && !store.supportsFileAttributeView("posix")) {
                if (storageBoundary == null) {
                    storageBoundary = createWindowsTestBoundary();
                }
                return storageBoundary.resolve("store");
            }
            return temporaryDirectory.resolve("store");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static CloseTrackingInputStream tracking(byte[] bytes) {
        return new CloseTrackingInputStream(bytes);
    }

    private static void assertStorageFailure(ThrowingOperation operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(StorageException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code))
                .hasMessage(code);
    }

    private static void assertNoTemporaryFiles(Path root) throws IOException {
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            assertThat(paths.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".part") || name.startsWith("@part-");
            }))
                    .isEmpty();
        }
    }

    private static void assertNoReservedAliases(Path root) throws IOException {
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.list(root)) {
            assertThat(paths.map(path -> path.getFileName().toString())
                            .filter(name -> name.startsWith("@part-")
                                    || name.startsWith("@identity-")))
                    .isEmpty();
        }
    }

    private static void assertReservedAlias(
            Path alias, Path expectedParent, String prefix, int expectedLength) {
        assertThat(alias).isNotNull();
        assertThat(alias.getParent()).isEqualTo(expectedParent);
        assertThat(alias.getFileName().toString())
                .startsWith(prefix)
                .hasSize(expectedLength);
    }

    private static void assertNoPublicationArtifacts(Path root) throws IOException {
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            assertThat(paths.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".part")
                        || name.startsWith("@part-")
                        || name.contains(".identity@guard")
                        || name.startsWith("@identity-");
            })).isEmpty();
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException exception) {
            assumeTrue(false, "symbolic links are unsupported by this provider");
        } catch (FileSystemException exception) {
            String reason = exception.getReason();
            boolean windowsPrivilegeMissing = "\\".equals(link.getFileSystem().getSeparator())
                    && reason != null
                    && reason.equalsIgnoreCase(
                            "A required privilege is not held by the client");
            if (windowsPrivilegeMissing) {
                assumeTrue(false, "Windows symbolic-link privilege is unavailable");
            }
            throw exception;
        }
    }

    private void assumeWindowsAclSupport() throws IOException {
        FileStore store = Files.getFileStore(temporaryDirectory);
        assumeTrue(!store.supportsFileAttributeView("posix"), "POSIX filesystem in use");
        assumeTrue(store.supportsFileAttributeView(AclFileAttributeView.class),
                "Windows ACL view unavailable");
    }

    private void assumeWindowsNtfsSupport() throws IOException {
        assumeWindowsAclSupport();
        assumeTrue("NTFS".equalsIgnoreCase(Files.getFileStore(temporaryDirectory).type()),
                "NTFS is unavailable on this platform");
    }

    private void assumePosixSupport() throws IOException {
        assumeTrue(Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"),
                "POSIX attributes unavailable");
    }

    private static void setUnixModeOrSkip(Path path, int mode) throws IOException {
        try {
            Files.setAttribute(path, "unix:mode", mode, NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException exception) {
            assumeTrue(false, "unix:mode is unsupported by this POSIX provider");
        }
    }

    private static void createHardLinkOrSkip(Path link, Path existing) throws IOException {
        try {
            Files.createLink(link, existing);
        } catch (UnsupportedOperationException exception) {
            assumeTrue(false, "hard links are unsupported by this provider");
        }
    }

    private static void createWindowsJunction(Path junction, Path target) throws IOException {
        assumeTrue("\\".equals(junction.getFileSystem().getSeparator()),
                "Windows junctions are unavailable on this platform");
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished;
        try {
            finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while creating Windows junction", exception);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("mklink /J did not finish");
        }
        String output = new String(process.getInputStream().readAllBytes(), UTF_8).trim();
        if (process.exitValue() != 0) {
            boolean privilegeUnavailable = output.contains(
                    "You do not have sufficient privilege to perform this operation.");
            assumeTrue(!privilegeUnavailable,
                    "Windows junction creation privilege is unavailable");
            throw new AssertionError(
                    "mklink /J failed with exit " + process.exitValue() + ": " + output);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                junction, BasicFileAttributes.class, NOFOLLOW_LINKS);
        assertAll(
                () -> assertThat(attributes.isDirectory()).isTrue(),
                () -> assertThat(attributes.isSymbolicLink()).isFalse(),
                () -> assertThat(attributes.isOther()).isTrue());
    }

    private static List<String> listNames(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static void assertUnsafeFinalFileOperationsPreserveForeignHardLink(
            LocalStorageService service, Path root, Path foreign) {
        Path linked = root.resolve("foreign.bin");
        Path copied = root.resolve("copy.bin");
        byte[] expected = "foreign".getBytes(UTF_8);

        assertAll(
                () -> assertStorageFailure(() -> {
                    try (StorageRead ignored = service.open("foreign.bin", Optional.empty())) {
                        // A vulnerable implementation reaches this block; closing avoids a leak.
                    }
                }, "LOCAL_UNSAFE_PATH"),
                () -> assertStorageFailure(
                        () -> service.exists("foreign.bin"), "LOCAL_UNSAFE_PATH"),
                () -> assertStorageFailure(
                        () -> service.copy("foreign.bin", "copy.bin"), "LOCAL_UNSAFE_PATH"),
                () -> assertStorageFailure(
                        () -> service.delete("foreign.bin"), "LOCAL_UNSAFE_PATH"),
                () -> assertThat(Files.exists(linked, NOFOLLOW_LINKS)).isTrue(),
                () -> assertThat(Files.isSameFile(linked, foreign)).isTrue(),
                () -> assertThat(Files.readAllBytes(foreign)).isEqualTo(expected),
                () -> assertThat(Files.exists(copied, NOFOLLOW_LINKS)).isFalse());
    }

    private Path createWindowsTestBoundary() throws IOException {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path boundary = Files.createTempDirectory(home, ".portfolio-media-test-");
        externalCleanup.add(boundary);
        return boundary;
    }

    private static void installBroadWindowsAcl(Path path, boolean directory) throws IOException {
        AclFileAttributeView view = requireAclView(path);
        UserPrincipal owner = view.getOwner();
        UserPrincipal everyone;
        try {
            everyone = path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName("Everyone");
        } catch (UserPrincipalNotFoundException exception) {
            assumeTrue(false, "Windows Everyone principal unavailable");
            return;
        }
        view.setAcl(List.of(fullControlEntry(owner, directory), fullControlEntry(everyone, directory)));
    }

    private static void installExactWindowsAcl(Path path, boolean directory) throws IOException {
        AclFileAttributeView view = requireAclView(path);
        view.setAcl(List.of(fullControlEntry(view.getOwner(), directory)));
    }

    private static Path createOversizedWindowsFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, Set.of(CREATE_NEW, WRITE))) {
            channel.position(MAX_CONTENT_LENGTH);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }
        installExactWindowsAcl(path, false);
        assertThat(Files.size(path)).isEqualTo(MAX_CONTENT_LENGTH + 1);
        return path;
    }

    private static void assertExactOwnerOnlyWindowsAcl(Path path, boolean directory)
            throws IOException {
        AclFileAttributeView view = requireAclView(path);
        assertThat(view.getAcl()).containsExactly(fullControlEntry(view.getOwner(), directory));
    }

    private static AclEntry fullControlEntry(UserPrincipal principal, boolean directory) {
        AclEntry.Builder builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            builder.setFlags(Set.of(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT));
        }
        return builder.build();
    }

    private static AclFileAttributeView requireAclView(Path path) {
        AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class, NOFOLLOW_LINKS);
        if (view == null) {
            throw new AssertionError("Windows ACL view disappeared");
        }
        return view;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private enum MissingMarkerOperation {
        EXISTS,
        PUT,
        OPEN,
        COPY,
        DELETE
    }

    private static class CloseTrackingInputStream extends ByteArrayInputStream {
        private final AtomicInteger closes = new AtomicInteger();

        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
            super.close();
        }

        int closeCount() {
            return closes.get();
        }
    }

    private static final class CloseObservingInputStream extends CloseTrackingInputStream {
        private final Path target;
        private boolean targetExistedWhenClosed;

        private CloseObservingInputStream(byte[] bytes, Path target) {
            super(bytes);
            this.target = target;
        }

        @Override
        public void close() throws IOException {
            targetExistedWhenClosed = Files.exists(target, NOFOLLOW_LINKS);
            super.close();
        }

        boolean targetExistedWhenClosed() {
            return targetExistedWhenClosed;
        }
    }

    private static final class CloseFailingInputStream extends CloseTrackingInputStream {
        private CloseFailingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            super.close();
            throw new IOException("synthetic close failure");
        }
    }
}
