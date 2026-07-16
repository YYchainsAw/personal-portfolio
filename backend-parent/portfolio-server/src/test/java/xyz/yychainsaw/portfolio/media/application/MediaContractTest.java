package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.api.admin.media.MediaPageView;
import xyz.yychainsaw.portfolio.api.admin.media.MediaTranslationInput;
import xyz.yychainsaw.portfolio.api.admin.media.MediaTranslationView;
import xyz.yychainsaw.portfolio.api.admin.media.MediaVariantView;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

class MediaContractTest {
    private static final UUID ASSET_ID = UUID.fromString(
            "20000000-0000-4000-8000-000000000001");
    private static final String SHA256 = "a".repeat(64);
    private static final jakarta.validation.ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void crossPlanRecordsExposeTheSpecifiedComponentsAndMethods() throws Exception {
        assertThat(componentNames(MediaReference.class))
                .containsExactly("referenceType", "referenceId");
        assertThat(componentNames(ImportMediaCommand.class))
                .containsExactly(
                        "assetRoot", "publicPath", "usage", "objectPosition",
                        "credit", "sourceUrl", "altByLocale");
        assertThat(componentNames(ImportedMedia.class))
                .containsExactly("assetId", "originalSha256", "readyVariants");
        assertThat(componentNames(MediaCopyDescriptor.class))
                .containsExactly("alt", "caption", "credit", "sourceUrl");
        assertThat(componentNames(MediaVariantDescriptor.class))
                .containsExactly(
                        "assetId", "variantName", "status", "provider", "bucket",
                        "region", "objectKey", "mimeType", "byteSize", "sha256",
                        "width", "height");
        assertThat(componentNames(MediaAssetDescriptor.class))
                .containsExactly(
                        "assetId", "status", "mimeType", "byteSize", "sha256",
                        "copyByLocale", "variants");

        assertThat(MediaReferenceChecker.class.getMethod("findReferences", UUID.class)
                .getReturnType()).isEqualTo(List.class);
        assertThat(MediaChangeListener.class.getMethod(
                        "onMediaChanged", UUID.class, MediaChangeType.class)
                .getReturnType()).isEqualTo(void.class);
        assertThat(MediaImportService.class.getMethod(
                        "importLocal", ImportMediaCommand.class)
                .getReturnType()).isEqualTo(ImportedMedia.class);
        assertThat(MediaQueryService.class.getMethod("requireReadyAsset", UUID.class)
                .getReturnType()).isEqualTo(MediaAssetDescriptor.class);
        assertThat(MediaQueryService.class.getMethod(
                        "requireReadyVariant", UUID.class, String.class)
                .getReturnType()).isEqualTo(MediaVariantDescriptor.class);
        assertThat(MediaLifecycleBarrier.class.getMethod("acquireExclusiveDeletionLease")
                .getReturnType()).isEqualTo(AutoCloseable.class);

        assertThat(MediaChangeType.values())
                .containsExactly(
                        MediaChangeType.TRANSLATION_UPDATED,
                        MediaChangeType.METADATA_UPDATED);
        assertThat(MediaLifecycleBarrier.NAMESPACE_KEY).isEqualTo(1_347_375_700);
        assertThat(MediaLifecycleBarrier.MEDIA_KEY).isEqualTo(1_296_385_097);
    }

    @Test
    void collectionBearingContractsTakeImmutableSnapshots() {
        Map<String, String> altByLocale = new HashMap<>();
        altByLocale.put("zh-CN", "作品截图");
        ImportMediaCommand command = new ImportMediaCommand(
                Path.of("assets"), "/work.png", "PROJECT", "50% 50%", "易嘉轩",
                URI.create("https://example.com/source"), altByLocale);
        altByLocale.put("en", "Changed after construction");

        List<String> readyVariants = new ArrayList<>(List.of("w640"));
        ImportedMedia imported = new ImportedMedia(ASSET_ID, SHA256, readyVariants);
        readyVariants.add("w1280");

        Map<String, MediaCopyDescriptor> copyByLocale = new HashMap<>();
        copyByLocale.put("zh-CN", new MediaCopyDescriptor(
                "作品截图", "", "易嘉轩", "https://example.com/source"));
        List<MediaVariantDescriptor> variants = new ArrayList<>();
        variants.add(variant());
        MediaAssetDescriptor descriptor = new MediaAssetDescriptor(
                ASSET_ID, "READY", "image/png", 12, SHA256, copyByLocale, variants);
        copyByLocale.clear();
        variants.clear();

        assertThat(command.altByLocale()).containsOnlyKeys("zh-CN");
        assertThat(imported.readyVariants()).containsExactly("w640");
        assertThat(descriptor.copyByLocale()).containsOnlyKeys("zh-CN");
        assertThat(descriptor.variants()).containsExactly(variant());
        assertThatThrownBy(() -> command.altByLocale().put("en", "English alt"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> imported.readyVariants().add("w1280"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> descriptor.variants().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void adminMediaDtoShapesAndPageItemsAreFrozenAndImmutable() {
        assertThat(componentNames(MediaTranslationInput.class))
                .containsExactly("locale", "altText", "caption", "credit", "sourceUrl");
        assertThat(componentNames(MediaTranslationView.class))
                .containsExactly("locale", "altText", "caption", "credit", "sourceUrl");
        assertThat(componentNames(MediaVariantView.class))
                .containsExactly("name", "width", "height", "status");
        assertThat(componentNames(MediaPageView.class))
                .containsExactly("items", "page", "size", "totalItems", "totalPages");

        List<MediaAssetView> source = new ArrayList<>(List.of(assetView()));
        MediaPageView page = new MediaPageView(source, 0, 24, 1, 1);
        source.clear();

        assertThat(page.items()).containsExactly(assetView());
        assertThatThrownBy(() -> page.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void translationInputReportsFieldSpecificBeanValidationErrors() {
        MediaTranslationInput valid = new MediaTranslationInput(
                "zh-CN", "作品截图", "项目说明", "易嘉轩",
                "https://example.com/source");
        MediaTranslationInput invalid = new MediaTranslationInput(
                "fr", "a".repeat(501), "c".repeat(1001), "x".repeat(301),
                "http://example.com/source");

        assertThat(VALIDATOR.validate(valid)).isEmpty();
        assertThat(VALIDATOR.validate(invalid))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder(
                        "locale", "altText", "caption", "credit", "sourceUrl");
    }

    @Test
    void adminViewRecordsRejectImpossibleShapes() {
        assertThatThrownBy(() -> new MediaTranslationView(
                        "en", "Alt", null, null, "http://example.com/source"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaVariantView("document", 1, null, "READY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaVariantView("w640", null, 360, "READY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaPageView(List.of(), -1, 24, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaPageView(List.of(), 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaPageView(List.of(), 0, 24, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> componentNames(Class<?> type) {
        return java.util.Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName())
                .toList();
    }

    private static MediaVariantDescriptor variant() {
        return new MediaVariantDescriptor(
                ASSET_ID,
                "w640",
                "READY",
                StorageProvider.LOCAL,
                null,
                null,
                "variants/asset/w640/hash.png",
                "image/png",
                12,
                SHA256,
                640,
                360);
    }

    private static MediaAssetView assetView() {
        return new MediaAssetView(
                ASSET_ID,
                "work.png",
                "image/png",
                12,
                640,
                360,
                SHA256,
                "READY",
                1,
                java.time.Instant.parse("2026-07-17T00:00:00Z"),
                java.time.Instant.parse("2026-07-17T00:00:01Z"));
    }
}
