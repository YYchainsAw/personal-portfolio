package xyz.yychainsaw.portfolio.media.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReconciliationService;
import xyz.yychainsaw.portfolio.system.job.JobHandler;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class StagingCleanupJobHandler implements JobHandler {
    private static final String INVALID_PAYLOAD =
            "MEDIA_STAGING_CLEANUP_PAYLOAD_INVALID";
    private static final String CLEANUP_FAILED = "MEDIA_STAGING_CLEANUP_FAILED";

    private final LocalStagingReconciliationService reconciliation;
    private final Clock clock;

    public StagingCleanupJobHandler(
            LocalStagingReconciliationService reconciliation, Clock clock) {
        this.reconciliation = Objects.requireNonNull(
                reconciliation, "local staging reconciliation is required");
        this.clock = Objects.requireNonNull(clock, "cleanup clock is required");
    }

    @Override
    public String jobType() {
        return "CLEAN_MEDIA_STAGING";
    }

    @Override
    public void handle(JsonNode payload) {
        Instant cutoff = parseCutoff(payload);
        Instant maximumCutoff = StagingCleanupBoundary.current(clock).cutoff();
        if (cutoff.isAfter(maximumCutoff)) {
            throw invalidPayload();
        }
        try {
            reconciliation.auditDaily();
        } catch (RuntimeException failure) {
            throw cleanupFailed();
        }
    }

    private static Instant parseCutoff(JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.size() != 1) {
            throw invalidPayload();
        }
        Iterator<Map.Entry<String, JsonNode>> fields = payload.fields();
        Map.Entry<String, JsonNode> field = fields.next();
        JsonNode value = field.getValue();
        if (!"cutoffEpochSecond".equals(field.getKey())
                || value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()) {
            throw invalidPayload();
        }
        try {
            return Instant.ofEpochSecond(value.longValue());
        } catch (DateTimeException | ArithmeticException exception) {
            throw invalidPayload();
        }
    }

    private static IllegalArgumentException invalidPayload() {
        return new IllegalArgumentException(INVALID_PAYLOAD);
    }

    private static IllegalStateException cleanupFailed() {
        return new IllegalStateException(CLEANUP_FAILED);
    }
}
