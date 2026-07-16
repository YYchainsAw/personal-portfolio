package xyz.yychainsaw.portfolio.system.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobPayloadCodecTest {
    private final JobPayloadCodec codec = new JobPayloadCodec(new ObjectMapper());

    @Test
    void serializesCleanupPayloadWithoutObjectKeyAndPreservesCanonicalFieldOrder() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assetId", "119d81a8-20d8-4ee0-a8dc-5a82e88ab232");
        payload.put("generation", 0L);
        payload.put("mimeType", "image/jpeg");
        payload.put("sha256", "a".repeat(64));

        assertThat(codec.serialize(payload)).isEqualTo(
                "{\"assetId\":\"119d81a8-20d8-4ee0-a8dc-5a82e88ab232\","
                        + "\"generation\":0,\"mimeType\":\"image/jpeg\","
                        + "\"sha256\":\"" + "a".repeat(64) + "\"}");
    }

    @Test
    void rejectsPayloadsAndQueueMetadataOutsideExistingLimits() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.serialize(null))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.serialize(Map.of("fraction", new BigDecimal("1e-20000"))))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.serialize(Map.of("value", "x".repeat(16 * 1024))))
                .withMessage("job payload is invalid")
                .withNoCause();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> JobPayloadCodec.requireJobType("private-type"))
                .withMessage("job type is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> JobPayloadCodec.requireIdempotencyKey("x".repeat(161)))
                .withMessage("job idempotency key is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> JobPayloadCodec.requireScheduledAt(
                        OffsetDateTime.of(2026, 7, 17, 0, 0, 0, 0, ZoneOffset.ofHours(8))))
                .withMessage("job schedule is invalid")
                .withNoCause();
    }

    @Test
    void parsesStoredPayloadThroughTheSameNumericAndByteLimits() {
        assertThat(codec.parseStored("{\"generation\":0}"))
                .isEqualTo(new ObjectMapper().valueToTree(Map.of("generation", 0)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.parseStored(null))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.parseStored(
                        "{\"value\":\"" + "x".repeat(16 * 1024) + "\"}"))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.parseStored(
                        "{\"value\":\"" + "x".repeat(64 * 1024) + "\"}"))
                .withMessage("job payload is invalid")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec.parseStored("[1,2,3]"))
                .withMessage("job payload is invalid")
                .withNoCause();
    }

    @Test
    void acceptsJsonbTextWhitespaceWhenTheCanonicalPayloadIsExactlyAtTheLimit() {
        int limit = 16 * 1024;
        String envelope = "{\"data\":\"\"}";
        String canonical = "{\"data\":\""
                + "a".repeat(limit - envelope.getBytes(StandardCharsets.UTF_8).length)
                + "\"}";
        String postgresJsonbText = canonical.replace(":", ": ");

        assertThat(canonical.getBytes(StandardCharsets.UTF_8)).hasSize(limit);
        assertThat(postgresJsonbText.getBytes(StandardCharsets.UTF_8)).hasSize(limit + 1);
        assertThat(codec.parseStored(postgresJsonbText).path("data").textValue())
                .hasSize(limit - envelope.length());
    }
}
