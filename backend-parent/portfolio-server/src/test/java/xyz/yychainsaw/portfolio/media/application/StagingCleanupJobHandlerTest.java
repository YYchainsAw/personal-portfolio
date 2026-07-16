package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReconciliationService;

class StagingCleanupJobHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");
    private static final Instant SAFE_CUTOFF = Instant.parse("2026-07-15T20:00:00Z");

    private final LocalStagingReconciliationService reconciliation =
            mock(LocalStagingReconciliationService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final StagingCleanupJobHandler handler =
            new StagingCleanupJobHandler(reconciliation, clock);

    @Test
    void exposesTheExactDurableJobType() {
        assertThat(handler.jobType()).isEqualTo("CLEAN_MEDIA_STAGING");
    }

    @Test
    void acceptsOnlyTheExactIntegralCutoffPayload() throws Exception {
        JsonNode valid = JSON.valueToTree(
                Map.of("cutoffEpochSecond", SAFE_CUTOFF.getEpochSecond()));

        assertThatCode(() -> handler.handle(valid)).doesNotThrowAnyException();

        verify(reconciliation).auditDaily();
    }

    @Test
    void rejectsMalformedPayloadsBeforeScanning() {
        java.util.List<JsonNode> invalid = java.util.List.of(
                JSON.nullNode(),
                JSON.createArrayNode(),
                JSON.createObjectNode(),
                JSON.valueToTree(Map.of("cutoffEpochSecond", "1")),
                JSON.valueToTree(Map.of("cutoffEpochSecond", 1.5)),
                JSON.valueToTree(Map.of("cutoffEpochSecond", 1.0)),
                JSON.getNodeFactory().objectNode().put(
                        "cutoffEpochSecond", BigInteger.ONE.shiftLeft(80)),
                JSON.valueToTree(Map.of("cutoffEpochSecond", "C:/secret/path")),
                JSON.valueToTree(Map.of(
                        "cutoffEpochSecond", SAFE_CUTOFF.getEpochSecond(),
                        "extra", true)));

        for (JsonNode payload : invalid) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> handler.handle(payload))
                    .withMessage("MEDIA_STAGING_CLEANUP_PAYLOAD_INVALID")
                    .withNoCause();
        }

        verify(reconciliation, never()).auditDaily();
    }

    @Test
    void rejectsAnOutOfRangeEpochBeforeScanning() {
        JsonNode payload = JSON.getNodeFactory().objectNode()
                .put("cutoffEpochSecond", Long.MAX_VALUE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.handle(payload))
                .withMessage("MEDIA_STAGING_CLEANUP_PAYLOAD_INVALID")
                .withNoCause();
        verify(reconciliation, never()).auditDaily();
    }

    @Test
    void rejectsACutoffLaterThanTheLatestSafeBoundaryBeforeScanning() {
        Instant unsafe = SAFE_CUTOFF.plusSeconds(1);
        JsonNode payload = JSON.valueToTree(
                Map.of("cutoffEpochSecond", unsafe.getEpochSecond()));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.handle(payload))
                .withMessage("MEDIA_STAGING_CLEANUP_PAYLOAD_INVALID")
                .withNoCause();
        verify(reconciliation, never()).auditDaily();
    }

    @Test
    void convertsAnyLocalOrScratchFailureToOneCauseFreeRetrySurface() {
        org.mockito.Mockito.doThrow(new IllegalStateException(
                        "LOCAL_STAGING_MIGRATION_REQUIRED",
                        new IllegalStateException("C:/secret/path")))
                .when(reconciliation)
                .auditDaily();
        JsonNode payload = JSON.valueToTree(
                Map.of("cutoffEpochSecond", SAFE_CUTOFF.getEpochSecond()));

        assertThatThrownBy(() -> handler.handle(payload))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_STAGING_CLEANUP_FAILED")
                .hasNoCause();
        assertThatThrownBy(() -> handler.handle(payload))
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("path")
                .hasMessageNotContaining("LOCAL_PRIVATE_FAILURE");
    }

}
