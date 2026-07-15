package xyz.yychainsaw.portfolio.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import xyz.yychainsaw.portfolio.common.trace.TraceIds;

class AuditCommandTest {
    @AfterEach
    void clearTraceContext() {
        MDC.clear();
    }

    @Test
    void snapshotsMetadataDefensivelyAndExposesAnImmutableMap() {
        Map<String, String> source = new HashMap<>();
        source.put("stage", "PASSWORD");

        AuditCommand command = new AuditCommand(
                null,
                "AUTH_REJECTED",
                "ADMIN",
                null,
                AuditOutcome.FAILURE,
                "trace-metadata",
                source);
        source.put("stage", "TOTP");
        source.put("later", "mutation");

        assertThat(command.metadata()).containsExactly(Map.entry("stage", "PASSWORD"));
        assertThatThrownBy(() -> command.metadata().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullRequiredComponentsWithNamedFailures() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AuditCommand(
                        null, null, "ADMIN", null, AuditOutcome.SUCCESS, "trace", Map.of()))
                .withMessage("action");
        assertThatNullPointerException()
                .isThrownBy(() -> new AuditCommand(
                        null, "ACTION", null, null, AuditOutcome.SUCCESS, "trace", Map.of()))
                .withMessage("targetType");
        assertThatNullPointerException()
                .isThrownBy(() -> new AuditCommand(
                        null, "ACTION", "ADMIN", null, null, "trace", Map.of()))
                .withMessage("outcome");
        assertThatNullPointerException()
                .isThrownBy(() -> new AuditCommand(
                        null, "ACTION", "ADMIN", null, AuditOutcome.SUCCESS, "trace", null))
                .withMessage("metadata");
    }

    @Test
    void rejectsNullMetadataKeysAndValues() {
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "value");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("key", null);

        assertThatNullPointerException().isThrownBy(() -> new AuditCommand(
                null, "ACTION", "ADMIN", null, AuditOutcome.SUCCESS, "trace", nullKey));
        assertThatNullPointerException().isThrownBy(() -> new AuditCommand(
                null, "ACTION", "ADMIN", null, AuditOutcome.SUCCESS, "trace", nullValue));
    }

    @Test
    void publishesOnlyTheExactDatabaseOutcomes() {
        assertThat(AuditOutcome.values())
                .containsExactly(AuditOutcome.SUCCESS, AuditOutcome.FAILURE);
    }

    @Test
    void resolvesANullTraceFromTheCurrentTraceContext() {
        MDC.put(TraceIds.MDC_KEY, "current-null-fallback");

        AuditCommand command = new AuditCommand(
                null, "ACTION", "ADMIN", null, AuditOutcome.SUCCESS, null, Map.of());

        assertThat(command.traceId()).isEqualTo(TraceIds.current());
    }

    @Test
    void resolvesABlankTraceFromTheCurrentTraceContext() {
        MDC.put(TraceIds.MDC_KEY, "current-blank-fallback");

        AuditCommand command = new AuditCommand(
                null, "ACTION", "ADMIN", null, AuditOutcome.SUCCESS, " \t", Map.of());

        assertThat(command.traceId()).isEqualTo(TraceIds.current());
    }

    @Test
    void permitsAnonymousUntargetedMixedCaseCallsWithEmptyMetadata() {
        AuditCommand command = new AuditCommand(
                null,
                "backgroundJob",
                "mediaAsset",
                null,
                AuditOutcome.SUCCESS,
                null,
                Map.of());

        assertThat(command.actorAdminId()).isNull();
        assertThat(command.targetId()).isNull();
        assertThat(command.targetType()).isEqualTo("mediaAsset");
        assertThat(command.metadata()).isEmpty();
        assertThat(command.traceId()).matches("[0-9a-f]{32}");
    }

    @Test
    void rejectsBlankRequiredTextAndBlankPresentTargetId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AuditCommand(
                        null, " \t", "ADMIN", null, AuditOutcome.SUCCESS, "trace", Map.of()))
                .withMessage("action must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AuditCommand(
                        null, "ACTION", " \t", null, AuditOutcome.SUCCESS, "trace", Map.of()))
                .withMessage("targetType must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AuditCommand(
                        null, "ACTION", "ADMIN", " \t", AuditOutcome.SUCCESS, "trace", Map.of()))
                .withMessage("targetId must not be blank");
    }

    @Test
    void acceptsValuesAtEveryAuditLogColumnLimit() {
        AuditCommand command = new AuditCommand(
                null,
                "a".repeat(96),
                "t".repeat(64),
                "i".repeat(128),
                AuditOutcome.SUCCESS,
                "r".repeat(64),
                Map.of());

        assertThat(command.action()).hasSize(96);
        assertThat(command.targetType()).hasSize(64);
        assertThat(command.targetId()).hasSize(128);
        assertThat(command.traceId()).hasSize(64);
    }

    @Test
    void rejectsValuesBeyondEveryAuditLogColumnLimit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AuditCommand(
                        null,
                        "a".repeat(97),
                        "TARGET",
                        null,
                        AuditOutcome.SUCCESS,
                        "trace",
                        Map.of()))
                .withMessage("action must be at most 96 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AuditCommand(
                        null,
                        "ACTION",
                        "t".repeat(65),
                        null,
                        AuditOutcome.SUCCESS,
                        "trace",
                        Map.of()))
                .withMessage("targetType must be at most 64 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AuditCommand(
                        null,
                        "ACTION",
                        "TARGET",
                        "i".repeat(129),
                        AuditOutcome.SUCCESS,
                        "trace",
                        Map.of()))
                .withMessage("targetId must be at most 128 characters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AuditCommand(
                        null,
                        "ACTION",
                        "TARGET",
                        null,
                        AuditOutcome.SUCCESS,
                        "r".repeat(65),
                        Map.of()))
                .withMessage("traceId must be at most 64 characters");
    }

    @Test
    void validatesTheResolvedTraceAgainstTheAuditLogLimit() {
        MDC.put(TraceIds.MDC_KEY, "r".repeat(65));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AuditCommand(
                        null, "ACTION", "TARGET", null, AuditOutcome.SUCCESS, null, Map.of()))
                .withMessage("traceId must be at most 64 characters");
    }
}
