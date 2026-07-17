package xyz.yychainsaw.portfolio.media.application;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingKnownRollbackCleanup;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservationService;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationFence;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "portfolio.cli.command=import")
@Isolated
class MediaImportCliConfigurationIntegrationTest extends PostgresIntegrationTestBase {
    private static final byte[] PDF = (
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n")
            .getBytes(StandardCharsets.US_ASCII);
    private static final String SHA256 = sha256(PDF);

    @TempDir Path temporaryDirectory;

    @Autowired ApplicationContext context;
    @Autowired MediaImportService imports;
    @Autowired MediaAssetRepository assets;
    @Autowired MediaVariantRepository variants;
    @Autowired MediaTranslationRepository translations;
    @Autowired TransactionTemplate transactions;

    @MockitoBean StorageRouter storageRouter;

    private Path assetRoot;

    @BeforeEach
    void setUp() throws Exception {
        clearFixtures();
        assetRoot = Files.createDirectory(temporaryDirectory.resolve("assets"));
        Files.write(assetRoot.resolve("work.pdf"), PDF);
    }

    @AfterEach
    void tearDown() {
        clearFixtures();
    }

    @Test
    void importCommandWiresTheProviderNeutralImporterInANonWebContext() {
        assertThat(context.getBeansOfType(MediaImportService.class)).hasSize(1);
        assertThat(context.getBeansOfType(LocalMediaIngestCoordinator.class)).hasSize(1);
        assertThat(context.getBeansOfType(LocalPublicationFence.class)).hasSize(1);
        assertThat(context.getBeansOfType(LocalStagingReservationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(LocalStagingKnownRollbackCleanup.class)).hasSize(1);
    }

    @Test
    void remoteProviderStagingSurvivesAnOuterDatabaseRollbackForLifecycleCleanup()
            throws Exception {
        StorageService storage = org.mockito.Mockito.mock(StorageService.class);
        StorageLocation location = new StorageLocation(
                StorageProvider.TENCENT_COS, "portfolio-test", "ap-guangzhou");
        AtomicReference<String> stagingKey = new AtomicReference<>();
        when(storageRouter.defaultWriter()).thenReturn(storage);
        when(storage.provider()).thenReturn(StorageProvider.TENCENT_COS);
        when(storage.location()).thenReturn(location);
        when(storage.put(
                        any(String.class),
                        any(InputStream.class),
                        eq((long) PDF.length),
                        eq("application/pdf")))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    stagingKey.set(key);
                    try (InputStream input = invocation.getArgument(1)) {
                        assertThat(input.readAllBytes()).isEqualTo(PDF);
                    }
                    return new StoredObject(
                            StorageProvider.TENCENT_COS,
                            "portfolio-test",
                            "ap-guangzhou",
                            key,
                            PDF.length,
                            "application/pdf",
                            "provider-etag");
                });
        AtomicReference<UUID> importedId = new AtomicReference<>();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    importedId.set(imports.importLocal(command()).assetId());
                    throw new ForcedRollback();
                }))
                .isInstanceOf(ForcedRollback.class);

        assertThat(importedId.get()).isNotNull();
        assertThat(stagingKey.get())
                .startsWith("staging/")
                .contains(importedId.get().toString());
        assertThat(assets.findById(importedId.get())).isEmpty();
        assertThat(translations.findByAssetId(importedId.get())).isEmpty();
        assertThat(variants.findByAssetId(importedId.get())).isEmpty();
        assertThat(migratorJdbc().sql("""
                        select count(*)
                        from portfolio.background_job
                        where idempotency_key=:key
                        """)
                .param("key", "media-finalize:" + importedId.get())
                .query(Long.class)
                .single()).isZero();
        verify(storage, never()).delete(any(String.class));
    }

    @Test
    void reusableAssetShareLockIsHeldUntilTheCallersOuterTransactionCommits()
            throws Exception {
        UUID assetId = UUID.randomUUID();
        seedReusableAsset(assetId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch importReturned = new CountDownLatch(1);
        CountDownLatch releaseImport = new CountDownLatch(1);
        try {
            Future<ImportedMedia> importing = executor.submit(() ->
                    transactions.execute(status -> {
                        ImportedMedia imported = imports.importLocal(command());
                        importReturned.countDown();
                        await(releaseImport);
                        return imported;
                    }));
            assertThat(importReturned.await(10, SECONDS)).isTrue();

            Future<MediaAssetRecord> archiving = executor.submit(() ->
                    transactions.execute(status -> assets.archive(assetId, 1L)
                            .orElseThrow()));
            assertThatThrownBy(() -> archiving.get(300, MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseImport.countDown();
            assertThat(importing.get(10, SECONDS).assetId()).isEqualTo(assetId);
            assertThat(archiving.get(10, SECONDS).status())
                    .isEqualTo(MediaStatus.ARCHIVED);
        } finally {
            releaseImport.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, SECONDS)).isTrue();
        }
    }

    private void seedReusableAsset(UUID assetId) {
        assets.insertProcessing(new MediaAssetRecord.Insert(
                assetId,
                StorageProvider.TENCENT_COS,
                "portfolio-test",
                "ap-guangzhou",
                MediaObjectKeys.originalKey(assetId, SHA256, "application/pdf"),
                "work.pdf",
                "application/pdf",
                PDF.length,
                null,
                null,
                SHA256));
        translations.replaceAll(assetId, expectedTranslations(assetId));
        assertThat(variants.insertReadyIfAbsent(new MediaVariantRecord.Insert(
                        UUID.randomUUID(),
                        assetId,
                        "document",
                        "PDF",
                        MediaObjectKeys.originalKey(
                                assetId, SHA256, "application/pdf"),
                        "application/pdf",
                        PDF.length,
                        null,
                        null,
                        SHA256)))
                .isTrue();
        assertThat(assets.markReadyIfProcessing(assetId)).isOne();
    }

    private ImportMediaCommand command() {
        return new ImportMediaCommand(
                assetRoot,
                "work.pdf",
                "project-gallery",
                "center",
                "Yi Jiaxuan",
                URI.create("https://example.com/source"),
                Map.of(
                        "zh-CN", "Gameplay screenshot",
                        "en", "Gameplay screenshot"));
    }

    private static List<MediaTranslationRecord> expectedTranslations(UUID assetId) {
        return List.of(
                new MediaTranslationRecord(
                        assetId,
                        "zh-CN",
                        "Gameplay screenshot",
                        null,
                        "Yi Jiaxuan",
                        "https://example.com/source"),
                new MediaTranslationRecord(
                        assetId,
                        "en",
                        "Gameplay screenshot",
                        null,
                        "Yi Jiaxuan",
                        "https://example.com/source"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, SECONDS)) {
                throw new IllegalStateException("test import lock gate timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test import lock gate was interrupted", failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void clearFixtures() {
        JdbcClient owner = migratorJdbc();
        owner.sql("delete from portfolio.media_translation").update();
        owner.sql("delete from portfolio.media_variant").update();
        owner.sql("delete from portfolio.local_staging_reservation").update();
        owner.sql("delete from portfolio.media_asset").update();
        owner.sql("delete from portfolio.background_job").update();
    }

    private static final class ForcedRollback extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
