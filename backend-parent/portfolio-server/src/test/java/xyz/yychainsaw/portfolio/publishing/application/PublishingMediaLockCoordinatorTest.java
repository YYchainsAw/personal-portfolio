package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaQueryAccessGuard;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.publishing.application.PublishingMediaLockCoordinator.LockedMediaPlan;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.PublishedMediaV1;

class PublishingMediaLockCoordinatorTest {
    private static final UUID ASSET_LOW =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ASSET_HIGH =
            UUID.fromString("f0000000-0000-4000-8000-000000000001");

    private final MediaQueryService media = org.mockito.Mockito.mock(MediaQueryService.class);
    private PublishingMediaLockCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new PublishingMediaLockCoordinator(media);
    }

    @Test
    void locksAssetsByUuidTextThenEveryDescriptorVariantByAssetAndName() {
        PublishedMediaV1 lowSnapshot = publishedMedia(ASSET_LOW, "zeta");
        PublishedMediaV1 highSnapshot = publishedMedia(ASSET_HIGH, "desktop");
        when(media.requireReadyAsset(ASSET_LOW))
                .thenReturn(readyAsset(ASSET_LOW, "zeta", "alpha"));
        when(media.requireReadyAsset(ASSET_HIGH))
                .thenReturn(readyAsset(ASSET_HIGH, "desktop", "card"));
        when(media.requireReadyVariant(any(), any())).thenAnswer(invocation ->
                readyVariant(invocation.getArgument(0), invocation.getArgument(1)));

        LockedMediaPlan plan = coordinator.lockRestoreMedia(
                Set.of(ASSET_HIGH, ASSET_LOW),
                List.of(highSnapshot, lowSnapshot));

        assertThat(plan.assetIds()).containsExactlyInAnyOrder(ASSET_LOW, ASSET_HIGH);
        assertThat(plan.variants()).containsExactlyInAnyOrder(
                variant(ASSET_LOW, "alpha"),
                variant(ASSET_LOW, "zeta"),
                variant(ASSET_HIGH, "card"),
                variant(ASSET_HIGH, "desktop"));
        InOrder order = inOrder(media);
        order.verify(media).requireReadyAsset(ASSET_LOW);
        order.verify(media).requireReadyAsset(ASSET_HIGH);
        order.verify(media).requireReadyVariant(ASSET_LOW, "alpha");
        order.verify(media).requireReadyVariant(ASSET_LOW, "zeta");
        order.verify(media).requireReadyVariant(ASSET_HIGH, "card");
        order.verify(media).requireReadyVariant(ASSET_HIGH, "desktop");
    }

    @Test
    void rejectsSnapshotAssetSetThatDoesNotExactlyMatchRestoredReferences() {
        List<PublishedMediaV1> snapshotMedia = List.of(
                publishedMedia(ASSET_LOW, "webp"),
                publishedMedia(ASSET_HIGH, "webp"));

        assertThatThrownBy(() -> coordinator.lockRestoreMedia(
                        Set.of(ASSET_LOW), snapshotMedia))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(media);
    }

    @Test
    void rejectsHistoricalVariantAbsentFromTheFullyLockedReadyDescriptorSet() {
        when(media.requireReadyAsset(ASSET_LOW))
                .thenReturn(readyAsset(ASSET_LOW, "current"));
        when(media.requireReadyVariant(ASSET_LOW, "current"))
                .thenReturn(readyVariant(ASSET_LOW, "current"));

        assertMediaNotReady(() -> coordinator.lockRestoreMedia(
                Set.of(ASSET_LOW),
                List.of(publishedMedia(ASSET_LOW, "historical"))));

        verify(media).requireReadyVariant(ASSET_LOW, "current");
        verify(media, never()).requireReadyVariant(ASSET_LOW, "historical");
    }

    @Test
    void normalizesReadyAssetFailureWithoutLeakingStorageCoordinates() {
        when(media.requireReadyAsset(ASSET_LOW)).thenThrow(new DomainException(
                "MEDIA_STORAGE_FAILURE",
                HttpStatus.SERVICE_UNAVAILABLE,
                Map.of(
                        "provider", "COS",
                        "bucket", "private-bucket",
                        "objectKey", "private/object/key")));

        assertMediaNotReady(() -> coordinator.lockRestoreMedia(
                Set.of(ASSET_LOW),
                List.of(publishedMedia(ASSET_LOW, "webp"))));

        verify(media, never()).requireReadyVariant(any(), any());
    }

    @Test
    void normalizesReadyVariantFailureWithoutLeakingStorageCoordinates() {
        when(media.requireReadyAsset(ASSET_LOW))
                .thenReturn(readyAsset(ASSET_LOW, "webp"));
        when(media.requireReadyVariant(ASSET_LOW, "webp"))
                .thenThrow(new DomainException(
                        "MEDIA_STORAGE_FAILURE",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        Map.of(
                                "provider", "COS",
                                "bucket", "private-bucket",
                                "objectKey", "private/object/key")));

        assertMediaNotReady(() -> coordinator.lockRestoreMedia(
                Set.of(ASSET_LOW),
                List.of(publishedMedia(ASSET_LOW, "webp"))));
    }

    @Test
    void lockedMediaPlanDefensivelyCopiesBothSets() {
        Set<UUID> assetIds = new HashSet<>(Set.of(ASSET_LOW));
        Set<MediaQueryAccessGuard.VariantKey> variants =
                new HashSet<>(Set.of(variant(ASSET_LOW, "webp")));

        LockedMediaPlan plan = new LockedMediaPlan(assetIds, variants);
        assetIds.clear();
        variants.clear();

        assertThat(plan.assetIds()).containsExactly(ASSET_LOW);
        assertThat(plan.variants()).containsExactly(variant(ASSET_LOW, "webp"));
        assertThatThrownBy(() -> plan.assetIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.variants().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertMediaNotReady(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("MEDIA_NOT_READY");
                    assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(failure.fieldErrors()).containsOnlyKeys(
                            "media[" + ASSET_LOW + "]");
                    String rendered = failure.fieldErrors().toString();
                    assertThat(rendered)
                            .doesNotContain("COS")
                            .doesNotContain("private-bucket")
                            .doesNotContain("private/object/key");
                });
    }

    private static MediaAssetDescriptor readyAsset(
            UUID assetId, String... variantNames) {
        List<MediaVariantDescriptor> variants = new ArrayList<>();
        for (String variantName : variantNames) {
            variants.add(readyVariant(assetId, variantName));
        }
        return new MediaAssetDescriptor(
                assetId,
                "READY",
                "image/webp",
                1_024,
                "a".repeat(64),
                Map.of(),
                variants);
    }

    private static MediaVariantDescriptor readyVariant(
            UUID assetId, String variantName) {
        return new MediaVariantDescriptor(
                assetId,
                variantName,
                "READY",
                StorageProvider.LOCAL,
                "portfolio-test",
                "local",
                "media/" + assetId + "/" + variantName,
                "image/webp",
                512,
                "b".repeat(64),
                1280,
                720);
    }

    private static PublishedMediaV1 publishedMedia(
            UUID assetId, String... variantNames) {
        return new PublishedMediaV1(
                assetId,
                "image/webp",
                1_024,
                "c".repeat(64),
                Map.of(),
                List.of(variantNames).stream()
                        .map(name -> new PublishedMediaV1.Variant(
                                name, 1280, 720, 512, "d".repeat(64)))
                        .toList());
    }

    private static MediaQueryAccessGuard.VariantKey variant(
            UUID assetId, String name) {
        return new MediaQueryAccessGuard.VariantKey(assetId, name);
    }
}
