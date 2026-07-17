package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

class LocalStorageExactReservedCleanupTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_ASSET_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SHA256 = "1".repeat(64);
    private static final String OTHER_SHA256 = "2".repeat(64);
    private static final Instant CUTOFF = Instant.parse("2099-01-01T00:00:00Z");

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
    void exactCleanupDeletesOnlyTheDerivedReservationAndPrunesItsDirectory()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
        Path other = stage(service, root, OTHER_ASSET_ID, OTHER_SHA256, CUTOFF);

        ReservedStagingCleanupResult result = service.cleanupReservedStaging(
                ASSET_ID, SHA256, "image/jpeg", CUTOFF);

        assertThat(result).isEqualTo(ReservedStagingCleanupResult.CLEANED);
        assertThat(target).doesNotExist();
        assertThat(target.getParent()).doesNotExist();
        assertThat(other).exists();
    }

    @Test
    void authorizationFromAnotherRootCannotDeleteReservedStaging() throws Exception {
        Path firstRoot = storageRoot().resolve("first-volume");
        Path secondRoot = storageRoot().resolve("second-volume");
        LocalStorageService first = service(firstRoot);
        LocalStorageService second = service(secondRoot);
        Path target = stage(second, secondRoot, ASSET_ID, SHA256, CUTOFF);
        LocalStagingPublication publication = new LocalStagingPublication(
                ASSET_ID,
                "staging/" + ASSET_ID + '/' + SHA256 + ".jpg",
                SHA256,
                "image/jpeg",
                new StorageLocation(StorageProvider.LOCAL, null, null),
                0,
                UUID.randomUUID());
        LocalPublicationAuthorization authorization = new LocalPublicationAuthorization(
                publication,
                first.volumeId(),
                Long.MAX_VALUE,
                System::nanoTime,
                new LocalPublicationAuthorization.FenceLease() {
                    @Override
                    public boolean isHeld() {
                        return true;
                    }

                    @Override
                    public void close() {}
                });

        assertThat(first.volumeId()).isNotEqualTo(second.volumeId());
        assertThatThrownBy(() -> second.cleanupReservedStaging(
                        authorization,
                        ASSET_ID,
                        SHA256,
                        "image/jpeg",
                        CUTOFF))
                .isInstanceOfSatisfying(
                        StorageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("LOCAL_STAGING_AUTHORIZATION_INVALID"))
                .hasMessage("LOCAL_STAGING_AUTHORIZATION_INVALID")
                .hasNoCause();
        assertThat(target).exists();
    }

    @Test
    void youngCanonicalAndYoungEmptyDirectoryAreDeferredWithoutMutation()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF.plusSeconds(1));

        assertThat(service.cleanupReservedStaging(
                        ASSET_ID, SHA256, "image/jpeg", CUTOFF))
                .isEqualTo(ReservedStagingCleanupResult.DEFERRED);
        assertThat(target).exists();

        Files.delete(target);
        Files.setLastModifiedTime(target.getParent(), FileTime.from(CUTOFF.plusSeconds(1)));
        assertThat(service.cleanupReservedStaging(
                        ASSET_ID, SHA256, "image/jpeg", CUTOFF))
                .isEqualTo(ReservedStagingCleanupResult.DEFERRED);
        assertThat(target.getParent()).isDirectory().isEmptyDirectory();
    }

    @Test
    void missingStagingTreeAndOldEmptyAssetDirectoryAreSafeAbsence()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);

        assertThat(service.cleanupReservedStaging(
                        ASSET_ID, SHA256, "image/jpeg", CUTOFF))
                .isEqualTo(ReservedStagingCleanupResult.CLEANED);

        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
        Files.delete(target);
        Files.setLastModifiedTime(target.getParent(), FileTime.from(CUTOFF));
        assertThat(service.cleanupReservedStaging(
                        ASSET_ID, SHA256, "image/jpeg", CUTOFF))
                .isEqualTo(ReservedStagingCleanupResult.CLEANED);
        assertThat(target.getParent()).doesNotExist();
    }

    @Test
    void unknownNameFailsClosedBeforeDeletingTheCanonicalObject() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
        Path unknown = alias(target, "unknown", CUTOFF);

        assertExactCleanupFailure(() -> service.cleanupReservedStaging(
                ASSET_ID, SHA256, "image/jpeg", CUTOFF));

        assertThat(target).exists();
        assertThat(unknown).exists();
    }

    @Test
    void aSecondCanonicalFilenameFailsClosedBeforeDeletingEitherFile()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
        Path secondCanonical = alias(target, OTHER_SHA256 + ".jpg", CUTOFF);
        Files.setLastModifiedTime(target.getParent(), FileTime.from(CUTOFF));

        assertExactCleanupFailure(() -> service.cleanupReservedStaging(
                ASSET_ID, SHA256, "image/jpeg", CUTOFF));

        assertThat(target).exists();
        assertThat(secondCanonical).exists();
    }

    @Test
    void aYoungOwnedPartOrCleanupGuardDefersTheWholeIdentityWithoutDeletion()
            throws Exception {
        for (String ownedName : List.of(
                "@part-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "@cleanup-identity-" + SHA256 + ".jpg")) {
            Path root = storageRoot().resolve("young-" + ownedName.substring(1, 5));
            LocalStorageService service = service(root);
            Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
            Path owned = alias(target, ownedName, CUTOFF.plusSeconds(1));
            Files.setLastModifiedTime(target.getParent(), FileTime.from(CUTOFF));

            assertThat(service.cleanupReservedStaging(
                            ASSET_ID, SHA256, "image/jpeg", CUTOFF))
                    .isEqualTo(ReservedStagingCleanupResult.DEFERRED);
            assertThat(target).exists();
            assertThat(owned).exists();
        }
    }

    @Test
    void anAllowedNameThatIsNotARegularFileFailsBeforeCanonicalMutation()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
        Path nonRegular = target.getParent().resolve(
                "@part-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        Files.createDirectory(nonRegular);
        Files.setLastModifiedTime(target.getParent(), FileTime.from(CUTOFF));

        assertExactCleanupFailure(() -> service.cleanupReservedStaging(
                ASSET_ID, SHA256, "image/jpeg", CUTOFF));

        assertThat(target).exists();
        assertThat(nonRegular).isDirectory();
    }

    @Test
    void anAllowedNameThatIsASymbolicLinkIsNeverFollowedOrDeleted()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
        Path outside = root.resolve("outside-do-not-delete");
        Files.createLink(outside, target);
        Path link = target.getParent().resolve(
                "@part-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            return;
        }
        Files.setLastModifiedTime(target.getParent(), FileTime.from(CUTOFF));

        assertExactCleanupFailure(() -> service.cleanupReservedStaging(
                ASSET_ID, SHA256, "image/jpeg", CUTOFF));

        assertThat(target).exists();
        assertThat(Files.exists(link, NOFOLLOW_LINKS)).isTrue();
        assertThat(outside).exists();
    }

    @Test
    void multiplePartOrIdentityNamesFailClosedBeforeAnyMutation() throws Exception {
        for (String prefix : List.of("@part-", "@identity-")) {
            Path root = storageRoot().resolve(prefix.substring(1, prefix.length() - 1));
            LocalStorageService service = service(root);
            Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
            Path first = alias(
                    target,
                    prefix + "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    CUTOFF);
            Path second = alias(
                    target,
                    prefix + "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                    CUTOFF);
            Files.setLastModifiedTime(target.getParent(), FileTime.from(CUTOFF));

            assertExactCleanupFailure(() -> service.cleanupReservedStaging(
                    ASSET_ID, SHA256, "image/jpeg", CUTOFF));
            assertThat(target).exists();
            assertThat(first).exists();
            assertThat(second).exists();
        }
    }

    @Test
    void exactMaximumFiveOwnedFileEntriesAreReclaimedTogether() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
        String canonical = target.getFileName().toString();
        alias(target, "@part-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", CUTOFF);
        alias(target, "@identity-bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", CUTOFF);
        alias(target, "@cleanup-identity-" + canonical, CUTOFF);
        alias(target, "@cleanup-verification-" + canonical, CUTOFF);
        Files.setLastModifiedTime(target.getParent(), FileTime.from(CUTOFF));

        assertThat(service.cleanupReservedStaging(
                        ASSET_ID, SHA256, "image/jpeg", CUTOFF))
                .isEqualTo(ReservedStagingCleanupResult.CLEANED);

        assertThat(target.getParent()).doesNotExist();
    }

    @Test
    void everySingleCrashResidueCanBeRecoveredWithoutAnotherObject()
            throws Exception {
        List<String> names = List.of(
                "@part-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "@identity-bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "@cleanup-identity-" + SHA256 + ".jpg",
                "@cleanup-verification-" + SHA256 + ".jpg");
        for (int index = 0; index < names.size(); index++) {
            Path root = storageRoot().resolve("residue-" + index);
            LocalStorageService service = service(root);
            Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
            alias(target, names.get(index), CUTOFF);
            Files.delete(target);
            Files.setLastModifiedTime(target.getParent(), FileTime.from(CUTOFF));

            assertThat(service.cleanupReservedStaging(
                            ASSET_ID, SHA256, "image/jpeg", CUTOFF))
                    .isEqualTo(ReservedStagingCleanupResult.CLEANED);
            assertThat(target.getParent()).doesNotExist();
        }
    }

    @Test
    void differentCleanupGuardIdentitiesFailClosed() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
        Path other = stage(service, root, OTHER_ASSET_ID, OTHER_SHA256, CUTOFF);
        String canonical = target.getFileName().toString();
        Path primary = alias(target, "@cleanup-identity-" + canonical, CUTOFF);
        Path verification = target.getParent().resolve(
                "@cleanup-verification-" + canonical);
        Files.createLink(verification, other);
        Files.setLastModifiedTime(verification, FileTime.from(CUTOFF));
        Files.delete(target);
        Files.setLastModifiedTime(target.getParent(), FileTime.from(CUTOFF));

        assertExactCleanupFailure(() -> service.cleanupReservedStaging(
                ASSET_ID, SHA256, "image/jpeg", CUTOFF));

        assertThat(primary).exists();
        assertThat(verification).exists();
        assertThat(other).exists();
    }

    @Test
    void nullDirectoryIdentityDefersAndPreservesTheWholeDirectory() throws Exception {
        Path root = storageRoot();
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public Object stagingCleanupDirectoryIdentity(
                            Path directory, BasicFileAttributes attributes) {
                        return null;
                    }
                });
        openServices.add(service);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);

        assertThat(service.cleanupReservedStaging(
                        ASSET_ID, SHA256, "image/jpeg", CUTOFF))
                .isEqualTo(ReservedStagingCleanupResult.DEFERRED);
        assertThat(target).exists();
    }

    @Test
    void nullStagingDirectoryIdentityDefersBeforeInspectingOrMutatingTheAsset()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public Object stagingCleanupDirectoryIdentity(
                            Path directory, BasicFileAttributes attributes) {
                        if ("staging".equals(directory.getFileName().toString())) {
                            return null;
                        }
                        return strongDirectoryIdentity(directory, attributes);
                    }
                });
        openServices.add(service);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);

        assertThat(service.cleanupReservedStaging(
                        ASSET_ID, SHA256, "image/jpeg", CUTOFF))
                .isEqualTo(ReservedStagingCleanupResult.DEFERRED);
        assertThat(target).exists();
    }

    @Test
    void stagingDirectoryIdentityChangeFailsClosedBeforeTheFirstMutation()
            throws Exception {
        Path root = storageRoot();
        Object initial = new Object();
        Object exchanged = new Object();
        java.util.concurrent.atomic.AtomicInteger stagingReads =
                new java.util.concurrent.atomic.AtomicInteger();
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public Object stagingCleanupDirectoryIdentity(
                            Path directory, BasicFileAttributes attributes) {
                        if ("staging".equals(directory.getFileName().toString())) {
                            return stagingReads.incrementAndGet() == 1
                                    ? initial
                                    : exchanged;
                        }
                        return strongDirectoryIdentity(directory, attributes);
                    }
                });
        openServices.add(service);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);

        assertExactCleanupFailure(() -> service.cleanupReservedStaging(
                ASSET_ID, SHA256, "image/jpeg", CUTOFF));

        assertThat(stagingReads.get()).isGreaterThanOrEqualTo(2);
        assertThat(target).exists();
    }

    @Test
    void identityExchangeImmediatelyBeforeMutationFailsClosed() throws Exception {
        Path root = storageRoot();
        AtomicBoolean swapped = new AtomicBoolean();
        Path[] replacement = new Path[1];
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public Object stagingCleanupDirectoryIdentity(
                            Path directory, BasicFileAttributes attributes) {
                        return strongDirectoryIdentity(directory, attributes);
                    }

                    @Override
                    public void beforeExactStagingCleanupMutation(Path target)
                            throws IOException {
                        if (swapped.compareAndSet(false, true)) {
                            Path original = target.getParent().getParent()
                                    .resolve("held-original");
                            Files.move(target, original);
                            Files.createLink(target, replacement[0]);
                        }
                    }
                });
        openServices.add(service);
        Path target = stage(service, root, ASSET_ID, SHA256, CUTOFF);
        replacement[0] = stage(service, root, OTHER_ASSET_ID, OTHER_SHA256, CUTOFF);

        assertExactCleanupFailure(() -> service.cleanupReservedStaging(
                ASSET_ID, SHA256, "image/jpeg", CUTOFF));

        assertThat(swapped).isTrue();
        assertThat(target).exists();
        assertThat(replacement[0]).exists();
    }

    @Test
    void fsyncFailureIsReportedAfterDeletionAndNeverClaimsSuccess() throws Exception {
        Path root = storageRoot();
        java.util.concurrent.atomic.AtomicInteger syncs =
                new java.util.concurrent.atomic.AtomicInteger();
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public Object stagingCleanupDirectoryIdentity(
                            Path directory, BasicFileAttributes attributes) {
                        return strongDirectoryIdentity(directory, attributes);
                    }

                    @Override
                    public void beforeExactStagingCleanupSync(Path directory)
                            throws IOException {
                        if (syncs.incrementAndGet() == 2) {
                            throw new IOException("simulated fsync refusal");
                        }
                    }
                });
        openServices.add(service);
        stage(service, root, ASSET_ID, SHA256, CUTOFF);

        assertExactCleanupFailure(() -> service.cleanupReservedStaging(
                ASSET_ID, SHA256, "image/jpeg", CUTOFF));
        assertThat(syncs).hasValue(2);
    }

    @Test
    void exactCleanupRejectsInvalidIdentityBeforeFilesystemAccess() {
        LocalStorageService service = serviceUnchecked(storageRootUnchecked());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.cleanupReservedStaging(
                        null, SHA256, "image/jpeg", CUTOFF))
                .withMessage("Local reserved staging cleanup identity is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.cleanupReservedStaging(
                        ASSET_ID, "A".repeat(64), "image/jpeg", CUTOFF))
                .withMessage("Local reserved staging cleanup identity is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.cleanupReservedStaging(
                        ASSET_ID, SHA256, "text/plain", CUTOFF))
                .withMessage("Local reserved staging cleanup identity is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.cleanupReservedStaging(
                        ASSET_ID, SHA256, "image/jpeg", null))
                .withMessage("Local reserved staging cleanup cutoff is required")
                .withNoCause();
    }

    private Path stage(
            LocalStorageService service,
            Path root,
            UUID assetId,
            String sha256,
            Instant modified) throws IOException {
        String key = "staging/" + assetId + '/' + sha256 + ".jpg";
        LocalStagingPublication publication = new LocalStagingPublication(
                assetId,
                key,
                sha256,
                "image/jpeg",
                new StorageLocation(StorageProvider.LOCAL, null, null),
                0,
                UUID.randomUUID());
        LocalPublicationAuthorization authorization = new LocalPublicationAuthorization(
                publication,
                service.volumeId(),
                Long.MAX_VALUE,
                System::nanoTime,
                new LocalPublicationAuthorization.FenceLease() {
                    @Override
                    public boolean isHeld() {
                        return true;
                    }

                    @Override
                    public void close() {}
                });
        service.putReservedStaging(
                authorization, publication, new ByteArrayInputStream(new byte[] {1}), 1);
        Path target = root.resolve(key);
        Files.setLastModifiedTime(target, FileTime.from(modified));
        Files.setLastModifiedTime(target.getParent(), FileTime.from(modified));
        Files.setLastModifiedTime(target.getParent().getParent(), FileTime.from(modified));
        return target;
    }

    private static Path alias(Path target, String name, Instant modified) throws IOException {
        Path alias = target.getParent().resolve(name);
        Files.createLink(alias, target);
        Files.setLastModifiedTime(alias, FileTime.from(modified));
        return alias;
    }

    private LocalStorageService service(Path root) {
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public Object stagingCleanupDirectoryIdentity(
                            Path directory, BasicFileAttributes attributes) {
                        return strongDirectoryIdentity(directory, attributes);
                    }
                });
        openServices.add(service);
        return service;
    }

    private static Object strongDirectoryIdentity(
            Path directory, BasicFileAttributes attributes) {
        return attributes.fileKey() == null
                ? directory.toAbsolutePath().normalize()
                : attributes.fileKey();
    }

    private LocalStorageService serviceUnchecked(Path root) {
        return service(root);
    }

    private Path storageRootUnchecked() {
        try {
            return storageRoot();
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private Path storageRoot() throws IOException {
        FileStore store = Files.getFileStore(temporaryDirectory);
        if (store.supportsFileAttributeView(AclFileAttributeView.class)
                && !store.supportsFileAttributeView("posix")) {
            if (storageBoundary == null) {
                storageBoundary = Files.createTempDirectory(
                        Path.of(System.getProperty("user.home")),
                        ".portfolio-media-test-");
                externalCleanup.add(storageBoundary);
            }
            return storageBoundary.resolve("store");
        }
        return temporaryDirectory.resolve("store");
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

    private static void assertExactCleanupFailure(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        StorageException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("LOCAL_STAGING_EXACT_CLEANUP_FAILED"))
                .hasMessage("LOCAL_STAGING_EXACT_CLEANUP_FAILED");
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
