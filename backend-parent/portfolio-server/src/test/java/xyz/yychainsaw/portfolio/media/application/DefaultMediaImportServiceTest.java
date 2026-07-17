package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

@ExtendWith(MockitoExtension.class)
class DefaultMediaImportServiceTest {
    private static final UUID EXISTING_ID = UUID.fromString(
            "24000000-0000-4000-8000-000000000001");
    private static final UUID NEW_ID = UUID.fromString(
            "24000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
    private static final byte[] PDF = (
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n")
            .getBytes(StandardCharsets.US_ASCII);
    private static final String SHA256 = sha256(PDF);

    @TempDir
    Path temporaryDirectory;

    @Mock
    private StorageRouter storageRouter;

    @Mock
    private StorageService storage;

    @Mock
    private MediaAssetRepository assets;

    @Mock
    private MediaVariantRepository variants;

    @Mock
    private MediaTranslationRepository translations;

    @Mock
    private BackgroundJobService jobs;

    @Mock
    private LocalMediaIngestCoordinator localIngest;

    @Mock
    private LocalMediaIngestSession localSession;

    private Path assetRoot;
    private Path source;
    private DefaultMediaImportService service;

    @BeforeEach
    void setUp() throws Exception {
        assetRoot = Files.createDirectory(temporaryDirectory.resolve("assets"));
        source = assetRoot.resolve("work.pdf");
        Files.write(source, PDF);
        Path inspectionDirectory = Files.createDirectory(
                temporaryDirectory.resolve("inspection"));
        service = new DefaultMediaImportService(
                new MediaFileInspector(inspectionDirectory),
                storageRouter,
                assets,
                variants,
                translations,
                jobs,
                localIngest,
                () -> NEW_ID);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void rejectsTraversalAndRequiresAnAmbientTransaction() {
        assertDomainFailure(
                () -> inTransaction(() -> service.importLocal(command("../work.pdf"))),
                "MEDIA_IMPORT_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThatThrownBy(() -> service.importLocal(command("work.pdf")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_IMPORT_TRANSACTION_REQUIRED")
                .hasNoCause();

        verifyNoInteractions(storageRouter, storage, assets, variants, translations, jobs);
    }

    @Test
    void rejectsIncompleteLocalesAndSymlinksResolvingOutsideTheAssetRoot()
            throws Exception {
        ImportMediaCommand incomplete = new ImportMediaCommand(
                assetRoot,
                "work.pdf",
                "project-gallery",
                "center",
                "Yi Jiaxuan",
                URI.create("https://example.com/source"),
                Map.of("en", "Gameplay screenshot"));
        assertDomainFailure(
                () -> inTransaction(() -> service.importLocal(incomplete)),
                "MEDIA_IMPORT_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY);

        Path outside = temporaryDirectory.resolve("outside.pdf");
        Files.write(outside, PDF);
        Path link = assetRoot.resolve("outside-link.pdf");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (java.io.IOException | UnsupportedOperationException unsupported) {
            return;
        }
        assertDomainFailure(
                () -> inTransaction(() -> service.importLocal(command("outside-link.pdf"))),
                "MEDIA_IMPORT_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(storageRouter, storage, assets, variants, translations, jobs);
    }

    @Test
    void rejectsUnsafeHttpsSourceAuthoritiesBeforeReadingOrPublishing() {
        for (String sourceUrl : List.of(
                "https:///missing-host",
                "https://user:secret@example.com/path#private",
                "https://example.com:0/path",
                "https://example.com:65536/path",
                "https://example.com/path#private")) {
            assertDomainFailure(
                    () -> inTransaction(() -> service.importLocal(
                            command("work.pdf", URI.create(sourceUrl)))),
                    "MEDIA_IMPORT_INVALID",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        verifyNoInteractions(storageRouter, storage, assets, variants, translations, jobs);
    }

    @Test
    void reusesOnlyAnExactReadyBilingualAssetAndReturnsReadyVariants() {
        MediaAssetRecord existing = asset(
                EXISTING_ID, StorageProvider.TENCENT_COS, MediaStatus.READY);
        when(assets.findReadyBySha256ForShare(SHA256)).thenReturn(List.of(existing));
        when(translations.findByAssetId(EXISTING_ID)).thenReturn(expectedTranslations(
                EXISTING_ID, "Gameplay screenshot"));
        when(variants.findByAssetId(EXISTING_ID)).thenReturn(List.of(
                documentVariant(EXISTING_ID, "READY")));

        ImportedMedia imported = inTransaction(
                () -> service.importLocal(command("/work.pdf")));

        assertThat(imported.assetId()).isEqualTo(EXISTING_ID);
        assertThat(imported.originalSha256()).isEqualTo(SHA256);
        assertThat(imported.readyVariants()).containsExactly("document");
        verifyNoInteractions(storageRouter, storage, jobs, localIngest);
        verify(assets, never()).insertProcessing(any());
    }

    @Test
    void differentAltPublishesNewRemoteStagingAndPersistsMetadataAndJob()
            throws Exception {
        MediaAssetRecord existing = asset(
                EXISTING_ID, StorageProvider.TENCENT_COS, MediaStatus.READY);
        when(assets.findReadyBySha256ForShare(SHA256)).thenReturn(List.of(existing));
        when(translations.findByAssetId(EXISTING_ID)).thenReturn(expectedTranslations(
                EXISTING_ID, "Different alt"));

        StorageLocation location = new StorageLocation(
                StorageProvider.TENCENT_COS, "portfolio-test", "ap-guangzhou");
        when(storageRouter.defaultWriter()).thenReturn(storage);
        when(storage.provider()).thenReturn(StorageProvider.TENCENT_COS);
        when(storage.location()).thenReturn(location);
        String stagingKey = MediaObjectKeys.stagingKey(
                NEW_ID, SHA256, "application/pdf");
        StoredObject staged = new StoredObject(
                StorageProvider.TENCENT_COS,
                "portfolio-test",
                "ap-guangzhou",
                stagingKey,
                PDF.length,
                "application/pdf",
                "provider-etag");
        when(storage.put(
                        eq(stagingKey), any(InputStream.class),
                        eq((long) PDF.length), eq("application/pdf")))
                .thenAnswer(invocation -> {
                    try (InputStream input = invocation.getArgument(1)) {
                        assertThat(input.readAllBytes()).isEqualTo(PDF);
                    }
                    return staged;
                });
        MediaAssetRecord processing = asset(
                NEW_ID, StorageProvider.TENCENT_COS, MediaStatus.PROCESSING);
        when(assets.insertProcessing(any())).thenReturn(processing);

        ImportedMedia imported = inTransaction(
                () -> service.importLocal(command("work.pdf")));

        assertThat(imported).isEqualTo(new ImportedMedia(NEW_ID, SHA256, List.of()));
        ArgumentCaptor<MediaAssetRecord.Insert> inserted =
                ArgumentCaptor.forClass(MediaAssetRecord.Insert.class);
        verify(assets).insertProcessing(inserted.capture());
        assertThat(inserted.getValue().objectKey()).isEqualTo(
                MediaObjectKeys.originalKey(NEW_ID, SHA256, "application/pdf"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MediaTranslationRecord>> copies =
                ArgumentCaptor.forClass(List.class);
        verify(translations).replaceAll(eq(NEW_ID), copies.capture());
        assertThat(copies.getValue()).containsExactlyInAnyOrderElementsOf(
                expectedTranslations(NEW_ID, "Gameplay screenshot"));
        verify(jobs).enqueue(
                "FINALIZE_MEDIA_UPLOAD",
                "media-finalize:" + NEW_ID,
                Map.of("assetId", NEW_ID.toString()));
    }

    @Test
    void localImportLeavesPublishedStagingForDurableRollbackCleanup()
            throws Exception {
        when(assets.findReadyBySha256ForShare(SHA256)).thenReturn(List.of());
        StorageLocation location = new StorageLocation(
                StorageProvider.LOCAL, null, null);
        when(storageRouter.defaultWriter()).thenReturn(storage);
        when(storage.provider()).thenReturn(StorageProvider.LOCAL);
        when(storage.location()).thenReturn(location);
        String stagingKey = MediaObjectKeys.stagingKey(
                NEW_ID, SHA256, "application/pdf");
        when(localIngest.open(
                        storage, location, NEW_ID, stagingKey, SHA256, "application/pdf"))
                .thenReturn(localSession);
        when(localSession.publish(any(InputStream.class), eq((long) PDF.length)))
                .thenAnswer(invocation -> {
                    try (InputStream input = invocation.getArgument(0)) {
                        assertThat(input.readAllBytes()).isEqualTo(PDF);
                    }
                    return new StoredObject(
                            StorageProvider.LOCAL,
                            null,
                            null,
                            stagingKey,
                            PDF.length,
                            "application/pdf",
                            SHA256);
                });
        when(assets.insertProcessing(any())).thenReturn(
                asset(NEW_ID, StorageProvider.LOCAL, MediaStatus.PROCESSING));

        ImportedMedia imported = inTransaction(
                () -> service.importLocal(command("work.pdf")));

        assertThat(imported.assetId()).isEqualTo(NEW_ID);
        verify(localSession).prepareOuterTransaction();
        verify(localSession).close();
        verify(localSession, never()).cleanupKnownRollback();
    }

    private ImportMediaCommand command(String publicPath) {
        return command(publicPath, URI.create("https://example.com/source"));
    }

    private ImportMediaCommand command(String publicPath, URI sourceUrl) {
        return new ImportMediaCommand(
                assetRoot,
                publicPath,
                "project-gallery",
                "center",
                "Yi Jiaxuan",
                sourceUrl,
                Map.of(
                        "zh-CN", "Gameplay screenshot",
                        "en", "Gameplay screenshot"));
    }

    private static MediaAssetRecord asset(
            UUID id, StorageProvider provider, MediaStatus status) {
        String bucket = provider == StorageProvider.LOCAL ? null : "portfolio-test";
        String region = provider == StorageProvider.LOCAL ? null : "ap-guangzhou";
        return new MediaAssetRecord(
                id,
                provider,
                bucket,
                region,
                MediaObjectKeys.originalKey(id, SHA256, "application/pdf"),
                "work.pdf",
                "application/pdf",
                PDF.length,
                null,
                null,
                SHA256,
                status,
                null,
                status == MediaStatus.READY ? 1 : 0,
                NOW,
                NOW);
    }

    private static MediaVariantRecord documentVariant(UUID assetId, String status) {
        return new MediaVariantRecord(
                UUID.randomUUID(),
                assetId,
                "document",
                "PDF",
                MediaObjectKeys.originalKey(assetId, SHA256, "application/pdf"),
                "application/pdf",
                PDF.length,
                null,
                null,
                SHA256,
                status,
                NOW);
    }

    private static List<MediaTranslationRecord> expectedTranslations(
            UUID assetId, String englishAlt) {
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
                        englishAlt,
                        null,
                        "Yi Jiaxuan",
                        "https://example.com/source"));
    }

    private static <T> T inTransaction(Supplier<T> callback) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            return callback.get();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static void assertDomainFailure(
            Runnable invocation, String code, HttpStatus status) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.status()).isEqualTo(status);
                    assertThat(failure.fieldErrors()).isEmpty();
                });
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
