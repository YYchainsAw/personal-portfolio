package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;

@ExtendWith(MockitoExtension.class)
class DefaultMediaQueryServiceTest {
    private static final UUID ASSET_ID = UUID.fromString(
            "21000000-0000-4000-8000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-07-17T00:00:00Z");
    private static final String ASSET_SHA = "a".repeat(64);
    private static final String SMALL_SHA = "b".repeat(64);
    private static final String LARGE_SHA = "c".repeat(64);

    @Mock
    private MediaAssetRepository assets;

    @Mock
    private MediaVariantRepository variants;

    @Mock
    private MediaTranslationRepository translations;

    private DefaultMediaQueryService service;

    @BeforeEach
    void setUp() {
        service = new DefaultMediaQueryService(assets, variants, translations);
    }

    @Test
    void readyAssetMapsCopyAndSortsVariantsByWidthThenName() {
        MediaAssetRecord asset = imageAsset(MediaStatus.READY);
        MediaVariantRecord large = imageVariant("w1280", 1280, 720, LARGE_SHA);
        MediaVariantRecord small = imageVariant("w640", 640, 360, SMALL_SHA);
        when(assets.findByIdForShare(ASSET_ID)).thenReturn(Optional.of(asset));
        when(translations.findByAssetId(ASSET_ID)).thenReturn(List.of(
                new MediaTranslationRecord(
                        ASSET_ID, "en", "Gameplay screenshot", "Combat arena",
                        "Yi Jiaxuan", "https://example.com/en"),
                new MediaTranslationRecord(
                        ASSET_ID, "zh-CN", "游戏截图", null,
                        "易嘉轩", "https://example.com/zh")));
        when(variants.findByAssetId(ASSET_ID)).thenReturn(List.of(large, small));

        MediaAssetDescriptor result = service.requireReadyAsset(ASSET_ID);

        assertThat(result.assetId()).isEqualTo(ASSET_ID);
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.copyByLocale())
                .containsEntry("en", new MediaCopyDescriptor(
                        "Gameplay screenshot", "Combat arena", "Yi Jiaxuan",
                        "https://example.com/en"))
                .containsEntry("zh-CN", new MediaCopyDescriptor(
                        "游戏截图", null, "易嘉轩", "https://example.com/zh"));
        assertThat(result.variants())
                .extracting(MediaVariantDescriptor::variantName)
                .containsExactly("w640", "w1280");
        assertThat(result.variants().get(0))
                .extracting(
                        MediaVariantDescriptor::provider,
                        MediaVariantDescriptor::bucket,
                        MediaVariantDescriptor::region,
                        MediaVariantDescriptor::width,
                        MediaVariantDescriptor::height)
                .containsExactly(StorageProvider.LOCAL, null, null, 640, 360);
    }

    @Test
    void pdfDescriptorUsesZeroDimensions() {
        MediaAssetRecord asset = pdfAsset(MediaStatus.READY);
        MediaVariantRecord document = new MediaVariantRecord(
                UUID.fromString("22000000-0000-4000-8000-000000000001"),
                ASSET_ID,
                "document",
                "PDF",
                MediaObjectKeys.originalKey(ASSET_ID, ASSET_SHA, "application/pdf"),
                "application/pdf",
                42,
                null,
                null,
                ASSET_SHA,
                "READY",
                CREATED);
        when(assets.findByIdForShare(ASSET_ID)).thenReturn(Optional.of(asset));
        when(translations.findByAssetId(ASSET_ID)).thenReturn(List.of());
        when(variants.findByAssetId(ASSET_ID)).thenReturn(List.of(document));

        MediaAssetDescriptor result = service.requireReadyAsset(ASSET_ID);

        assertThat(result.variants()).singleElement().satisfies(variant -> {
            assertThat(variant.variantName()).isEqualTo("document");
            assertThat(variant.width()).isZero();
            assertThat(variant.height()).isZero();
        });
    }

    @Test
    void missingAndNonReadyAssetsHaveStableErrors() {
        when(assets.findByIdForShare(ASSET_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(imageAsset(MediaStatus.PROCESSING)));

        assertDomainFailure(
                () -> service.requireReadyAsset(ASSET_ID),
                "MEDIA_NOT_FOUND",
                HttpStatus.NOT_FOUND);
        assertDomainFailure(
                () -> service.requireReadyAsset(ASSET_ID),
                "MEDIA_NOT_READY",
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void readyVariantRequiresAnExistingReadyAssetAndKnownReadyName() {
        MediaAssetRecord asset = imageAsset(MediaStatus.READY);
        MediaVariantRecord small = imageVariant("w640", 640, 360, SMALL_SHA);
        when(assets.findByIdForShare(ASSET_ID)).thenReturn(Optional.of(asset));
        when(variants.findByAssetAndName(ASSET_ID, "w640"))
                .thenReturn(Optional.of(small));
        when(variants.findByAssetAndName(ASSET_ID, "w960"))
                .thenReturn(Optional.empty());

        MediaVariantDescriptor result = service.requireReadyVariant(ASSET_ID, "w640");

        assertThat(result.variantName()).isEqualTo("w640");
        assertThat(result.objectKey()).isEqualTo(small.objectKey());
        assertDomainFailure(
                () -> service.requireReadyVariant(ASSET_ID, "w960"),
                "MEDIA_NOT_FOUND",
                HttpStatus.NOT_FOUND);
    }

    @Test
    void publicationGuardRejectsUnplannedQueriesBeforeAnyRepositoryRead() {
        UUID outsidePlan = UUID.fromString(
                "f0000000-0000-4000-8000-000000000001");
        MediaQueryAccessGuard guard = new MediaQueryAccessGuard();
        DefaultMediaQueryService guarded = new DefaultMediaQueryService(
                assets, variants, translations, guard);

        try (MediaQueryAccessGuard.Scope ignored =
                guard.openScope(Set.of(ASSET_ID), Set.of())) {
            assertThatThrownBy(() -> guarded.requireReadyAsset(outsidePlan))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("media query is outside the active publication plan");
            assertThatThrownBy(() -> guarded.requireReadyVariant(ASSET_ID, "w640"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("media query is outside the active publication plan");
        }

        verifyNoInteractions(assets, variants, translations);
    }

    private static MediaAssetRecord imageAsset(MediaStatus status) {
        return new MediaAssetRecord(
                ASSET_ID,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(ASSET_ID, ASSET_SHA, "image/png"),
                "work.png",
                "image/png",
                100,
                1920,
                1080,
                ASSET_SHA,
                status,
                null,
                status == MediaStatus.READY ? 1 : 0,
                CREATED,
                CREATED);
    }

    private static MediaAssetRecord pdfAsset(MediaStatus status) {
        return new MediaAssetRecord(
                ASSET_ID,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(ASSET_ID, ASSET_SHA, "application/pdf"),
                "design.pdf",
                "application/pdf",
                42,
                null,
                null,
                ASSET_SHA,
                status,
                null,
                1,
                CREATED,
                CREATED);
    }

    private static MediaVariantRecord imageVariant(
            String name, int width, int height, String sha256) {
        return new MediaVariantRecord(
                UUID.randomUUID(),
                ASSET_ID,
                name,
                "PNG",
                MediaObjectKeys.variantKey(ASSET_ID, name, sha256, "image/png"),
                "image/png",
                50,
                width,
                height,
                sha256,
                "READY",
                CREATED);
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
}
