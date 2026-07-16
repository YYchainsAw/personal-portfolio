package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

class LocalStorageStagingJournalAuditTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_ASSET_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SHA256 = "1".repeat(64);
    private static final String OTHER_SHA256 = "2".repeat(64);

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
    void exactReservedTreeIncludingEveryBoundedCrashArtifactPassesWithoutDeletion()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root, 64);
        Path canonical = stage(service, root, ASSET_ID, SHA256);
        String canonicalName = canonical.getFileName().toString();
        List<Path> aliases = List.of(
                alias(canonical, "@part-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                alias(canonical, "@identity-bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                alias(canonical, "@cleanup-identity-" + canonicalName),
                alias(canonical, "@cleanup-verification-" + canonicalName));

        assertThatCode(() -> service.auditReservedStaging(Map.of(
                        ASSET_ID,
                        new LocalStagingAuditExpectation(
                                ASSET_ID, SHA256, "image/jpeg"))))
                .doesNotThrowAnyException();

        assertThat(canonical).exists();
        assertThat(aliases).allMatch(Files::exists);
    }

    @Test
    void reservationsWithoutFilesystemArtifactsAndAMissingStagingTreeAreAllowed()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root, 64);

        assertThatCode(() -> service.auditReservedStaging(Map.of(
                        ASSET_ID,
                        new LocalStagingAuditExpectation(
                                ASSET_ID, SHA256, "image/jpeg"))))
                .doesNotThrowAnyException();
    }

    @Test
    void unreservedCanonicalAndCrashResidueBothRequireMigrationWithoutDeletion()
            throws Exception {
        for (boolean residueOnly : List.of(false, true)) {
            Path root = storageRoot().resolve("unreserved-" + residueOnly);
            LocalStorageService service = service(root, 64);
            Path canonical = stage(service, root, ASSET_ID, SHA256);
            Path residue = alias(
                    canonical, "@part-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
            if (residueOnly) {
                Files.delete(canonical);
            }

            assertMigrationRequired(() -> service.auditReservedStaging(Map.of()));

            assertThat(residue).exists();
            assertThat(Files.exists(canonical)).isEqualTo(!residueOnly);
        }
    }

    @Test
    void unknownSecondCanonicalAndCardinalityOverflowFailBeforeMutation()
            throws Exception {
        List<AuditMutation> corruptions = List.of(
                canonical -> alias(canonical, "unknown"),
                canonical -> alias(canonical, OTHER_SHA256 + ".jpg"),
                canonical -> {
                    alias(canonical, "@part-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
                    return alias(
                            canonical,
                            "@part-bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
                });
        for (int index = 0; index < corruptions.size(); index++) {
            Path root = storageRoot().resolve("corruption-" + index);
            LocalStorageService service = service(root, 64);
            Path canonical = stage(service, root, ASSET_ID, SHA256);
            Path corrupt = corruptions.get(index).apply(canonical);

            assertMigrationRequired(() -> service.auditReservedStaging(expectation()));

            assertThat(canonical).exists();
            assertThat(corrupt).exists();
        }
    }

    @Test
    void nonRegularOrSymbolicOwnedNamesFailWithoutFollowingOrDeletingThem()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root, 64);
        Path canonical = stage(service, root, ASSET_ID, SHA256);
        Path directory = canonical.getParent().resolve(
                "@part-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        Files.createDirectory(directory);

        assertMigrationRequired(() -> service.auditReservedStaging(expectation()));
        assertThat(canonical).exists();
        assertThat(directory).isDirectory();

        Files.delete(directory);
        Path outside = root.resolve("outside-do-not-follow");
        Files.createLink(outside, canonical);
        try {
            Files.createSymbolicLink(directory, outside);
        } catch (IOException | UnsupportedOperationException unsupported) {
            return;
        }
        assertMigrationRequired(() -> service.auditReservedStaging(expectation()));
        assertThat(Files.exists(directory, NOFOLLOW_LINKS)).isTrue();
        assertThat(outside).exists();
    }

    @Test
    void hardScanCeilingFailsClosedWithoutMaterializingOrDeletingLaterEntries()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root, 1);
        Path canonical = stage(service, root, ASSET_ID, SHA256);

        assertMigrationRequired(() -> service.auditReservedStaging(expectation()));

        assertThat(canonical).exists();
    }

    @Test
    void missingOrChangingSecureDirectoryIdentityFailsClosedWithoutDeletion()
            throws Exception {
        for (boolean changes : List.of(false, true)) {
            Path root = storageRoot().resolve("identity-" + changes);
            Object initial = new Object();
            java.util.concurrent.atomic.AtomicInteger stagingReads =
                    new java.util.concurrent.atomic.AtomicInteger();
            LocalStorageService service = new LocalStorageService(
                    new LocalStorageProperties(root),
                    new LocalStorageService.OperationObserver() {
                        @Override
                        public Object stagingCleanupDirectoryIdentity(
                                Path directory, BasicFileAttributes attributes) {
                            if ("staging".equals(directory.getFileName().toString())) {
                                if (!changes) {
                                    return null;
                                }
                                return stagingReads.incrementAndGet() == 1
                                        ? initial
                                        : new Object();
                            }
                            return strongDirectoryIdentity(directory, attributes);
                        }
                    },
                    64);
            openServices.add(service);
            Path canonical = stage(service, root, ASSET_ID, SHA256);

            assertMigrationRequired(() -> service.auditReservedStaging(expectation()));
            assertThat(canonical).exists();
        }
    }

    @Test
    void expectationMismatchNeverTreatsAnotherCanonicalIdentityAsReserved()
            throws Exception {
        Path root = storageRoot();
        LocalStorageService service = service(root, 64);
        Path canonical = stage(service, root, ASSET_ID, SHA256);

        assertMigrationRequired(() -> service.auditReservedStaging(Map.of(
                ASSET_ID,
                new LocalStagingAuditExpectation(
                        ASSET_ID, OTHER_SHA256, "image/jpeg"))));

        assertThat(canonical).exists();
    }

    private static Map<UUID, LocalStagingAuditExpectation> expectation() {
        Map<UUID, LocalStagingAuditExpectation> expected = new LinkedHashMap<>();
        expected.put(
                ASSET_ID,
                new LocalStagingAuditExpectation(ASSET_ID, SHA256, "image/jpeg"));
        return expected;
    }

    private Path stage(
            LocalStorageService service,
            Path root,
            UUID assetId,
            String sha256) {
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
                authorization,
                publication,
                new ByteArrayInputStream(new byte[] {1}),
                1);
        return root.resolve(key);
    }

    private static Path alias(Path target, String name) throws IOException {
        Path alias = target.getParent().resolve(name);
        Files.createLink(alias, target);
        return alias;
    }

    private LocalStorageService service(Path root, int ceiling) {
        LocalStorageService service = new LocalStorageService(
                new LocalStorageProperties(root),
                new LocalStorageService.OperationObserver() {
                    @Override
                    public Object stagingCleanupDirectoryIdentity(
                            Path directory, BasicFileAttributes attributes) {
                        return strongDirectoryIdentity(directory, attributes);
                    }
                },
                ceiling);
        openServices.add(service);
        return service;
    }

    private static Object strongDirectoryIdentity(
            Path directory, BasicFileAttributes attributes) {
        return attributes.fileKey() == null
                ? directory.toAbsolutePath().normalize()
                : attributes.fileKey();
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

    private static void assertMigrationRequired(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        StorageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("LOCAL_STAGING_MIGRATION_REQUIRED"))
                .hasMessage("LOCAL_STAGING_MIGRATION_REQUIRED")
                .hasNoCause();
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

    @FunctionalInterface
    private interface AuditMutation {
        Path apply(Path canonical) throws IOException;
    }
}
