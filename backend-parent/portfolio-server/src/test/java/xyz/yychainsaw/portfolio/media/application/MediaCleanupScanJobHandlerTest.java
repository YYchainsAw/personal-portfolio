package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;

class MediaCleanupScanJobHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");
    private static final Instant CUTOFF = NOW.minus(java.time.Duration.ofDays(30));

    private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
    private final MediaCleanupCoordinator coordinator = mock(MediaCleanupCoordinator.class);
    private final MediaCleanupScanJobHandler handler = new MediaCleanupScanJobHandler(
            assets,
            coordinator,
            Clock.fixed(NOW, ZoneOffset.UTC),
            resolverWithChecker());

    @Test
    void exposesExactTypeAndStagesOnlyRepositorySelectedCandidates() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(assets.findArchivedIdsAtOrBefore(
                        eq(CUTOFF), isNull(), eq(MediaCleanupScanJobHandler.SCAN_BATCH_SIZE)))
                .thenReturn(List.of(first, second));

        handler.handle(scanPayload(CUTOFF));

        assertThat(handler.jobType()).isEqualTo("MEDIA_CLEANUP_SCAN");
        verify(coordinator).stageForDeletion(first, CUTOFF);
        verify(coordinator).stageForDeletion(second, CUTOFF);
    }

    @Test
    void scansInBoundedKeysetPages() {
        List<UUID> firstPage = new ArrayList<>();
        for (int index = 1; index <= MediaCleanupScanJobHandler.SCAN_BATCH_SIZE; index++) {
            firstPage.add(new UUID(0L, index));
        }
        UUID cursor = firstPage.get(firstPage.size() - 1);
        UUID tail = new UUID(0L, MediaCleanupScanJobHandler.SCAN_BATCH_SIZE + 1L);
        when(assets.findArchivedIdsAtOrBefore(
                        CUTOFF, null, MediaCleanupScanJobHandler.SCAN_BATCH_SIZE))
                .thenReturn(firstPage);
        when(assets.findArchivedIdsAtOrBefore(
                        CUTOFF, cursor, MediaCleanupScanJobHandler.SCAN_BATCH_SIZE))
                .thenReturn(List.of(tail));

        handler.handle(scanPayload(CUTOFF));

        verify(assets).findArchivedIdsAtOrBefore(
                CUTOFF, null, MediaCleanupScanJobHandler.SCAN_BATCH_SIZE);
        verify(assets).findArchivedIdsAtOrBefore(
                CUTOFF, cursor, MediaCleanupScanJobHandler.SCAN_BATCH_SIZE);
        verify(coordinator).stageForDeletion(firstPage.get(0), CUTOFF);
        verify(coordinator).stageForDeletion(tail, CUTOFF);
    }

    @Test
    void isolatesCandidateFailuresBeforeReportingTheScanFailure() {
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(assets.findArchivedIdsAtOrBefore(
                        CUTOFF, null, MediaCleanupScanJobHandler.SCAN_BATCH_SIZE))
                .thenReturn(List.of(broken, healthy));
        doThrow(new IllegalStateException("private checker detail"))
                .when(coordinator)
                .stageForDeletion(broken, CUTOFF);

        assertThatThrownBy(() -> handler.handle(scanPayload(CUTOFF)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_CLEANUP_SCAN_FAILED")
                .hasNoCause();

        verify(coordinator).stageForDeletion(healthy, CUTOFF);
    }

    @Test
    void rejectsFutureOrMalformedCutoffsBeforeReadingAssets() {
        List<JsonNode> invalid = List.of(
                JSON.nullNode(),
                JSON.createArrayNode(),
                JSON.createObjectNode(),
                JSON.valueToTree(Map.of("cutoffEpochSecond", "1")),
                JSON.valueToTree(Map.of("cutoffEpochSecond", 1.5)),
                JSON.getNodeFactory().objectNode().put(
                        "cutoffEpochSecond", BigInteger.ONE.shiftLeft(80)),
                JSON.valueToTree(Map.of(
                        "cutoffEpochSecond", CUTOFF.getEpochSecond(),
                        "extra", true)),
                scanPayload(CUTOFF.plusSeconds(1)));

        for (JsonNode payload : invalid) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> handler.handle(payload))
                    .withMessage("MEDIA_CLEANUP_SCAN_PAYLOAD_INVALID")
                    .withNoCause();
        }

        verify(assets, never()).findArchivedIdsAtOrBefore(
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private static JsonNode scanPayload(Instant cutoff) {
        return JSON.valueToTree(Map.of("cutoffEpochSecond", cutoff.getEpochSecond()));
    }

    private static MediaReferenceResolver resolverWithChecker() {
        return new MediaReferenceResolver(List.of(assetId -> List.of()));
    }
}
