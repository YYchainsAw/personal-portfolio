package xyz.yychainsaw.portfolio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AnalyticsAggregationJobHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-18T08:30:00Z");
    private static final UUID RUN_ID =
            UUID.fromString("95000000-0000-4000-8000-000000000008");

    private final AnalyticsAggregationService service =
            mock(AnalyticsAggregationService.class);
    private final AnalyticsMaintenanceRunRepository maintenance =
            mock(AnalyticsMaintenanceRunRepository.class);
    private final AnalyticsAggregationJobHandler handler =
            new AnalyticsAggregationJobHandler(
                    service,
                    maintenance,
                    new AnalyticsRules(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    () -> RUN_ID);

    @Test
    void exposesExactTypeAndRecordsOnlyStableCounts() {
        LocalDate date = LocalDate.parse("2026-07-17");
        when(service.rebuild(date))
                .thenReturn(new AnalyticsAggregationService.AggregationResult(8, 56));

        handler.handle(payload(date));

        assertThat(handler.jobType()).isEqualTo("ANALYTICS_AGGREGATE");
        InOrder ordered = inOrder(maintenance, service);
        ordered.verify(maintenance).startAggregation(RUN_ID, NOW);
        ordered.verify(service).rebuild(date);
        ordered.verify(maintenance).succeedAggregation(RUN_ID, 8, 56, NOW);
    }

    @Test
    void exactPayloadCannotSelectAParameterOrUnboundedDate() {
        JsonNode extra = JSON.valueToTree(Map.of(
                "siteDate", "2026-07-18",
                "aggregationVersion", "attacker-version"));

        for (JsonNode invalid : java.util.List.of(
                JSON.nullNode(),
                JSON.createArrayNode(),
                JSON.createObjectNode(),
                JSON.valueToTree(Map.of("siteDate", "2026-07-19")),
                JSON.valueToTree(Map.of("siteDate", "2026-06-16")),
                JSON.valueToTree(Map.of("siteDate", 123)),
                extra)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> handler.handle(invalid))
                    .withMessage("ANALYTICS_AGGREGATION_PAYLOAD_INVALID")
                    .withNoCause();
        }
        verify(maintenance, never()).startAggregation(RUN_ID, NOW);
        verify(service, never()).rebuild(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void privateDependencyFailureIsRedactedAndRecorded() {
        LocalDate date = LocalDate.parse("2026-07-17");
        when(service.rebuild(date))
                .thenThrow(new IllegalStateException("private visitor key and SQL"));

        assertThatThrownBy(() -> handler.handle(payload(date)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("ANALYTICS_AGGREGATION_FAILED")
                .hasMessageNotContaining("private")
                .hasNoCause();

        verify(maintenance).failAggregation(
                RUN_ID, 0, 0, NOW, "ANALYTICS_AGGREGATION_FAILED");
    }

    @Test
    void startFailureDoesNotRunOrAttemptATerminalTransition() {
        doThrow(new IllegalStateException("private maintenance database"))
                .when(maintenance)
                .startAggregation(RUN_ID, NOW);

        assertThatThrownBy(() -> handler.handle(payload(LocalDate.parse("2026-07-17"))))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("ANALYTICS_AGGREGATION_FAILED")
                .hasNoCause();

        verify(service, never()).rebuild(org.mockito.ArgumentMatchers.any());
        verify(maintenance, never()).failAggregation(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static JsonNode payload(LocalDate siteDate) {
        return JSON.valueToTree(Map.of(
                "siteDate", siteDate.toString(),
                "aggregationVersion", "analytics-rules-v1"));
    }
}
