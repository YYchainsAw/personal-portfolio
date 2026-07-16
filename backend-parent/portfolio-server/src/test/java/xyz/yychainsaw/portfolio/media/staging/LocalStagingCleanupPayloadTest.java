package xyz.yychainsaw.portfolio.media.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalStagingCleanupPayloadTest {
    private static final UUID ASSET_ID =
            UUID.fromString("6db0a6dc-bb6a-4cd7-8430-d565c4a0191e");
    private static final String SHA256 = "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesOnlyTheExactCanonicalPayloadAndRecreatesCanonicalInsertionOrder()
            throws Exception {
        LocalStagingCleanupPayload payload = LocalStagingCleanupPayload.parse(read("""
                {
                  "assetId":"6db0a6dc-bb6a-4cd7-8430-d565c4a0191e",
                  "generation":0,
                  "mimeType":"image/jpeg",
                  "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                }
                """));

        assertThat(payload.assetId()).isEqualTo(ASSET_ID);
        assertThat(payload.generation()).isZero();
        assertThat(payload.mimeType()).isEqualTo("image/jpeg");
        assertThat(payload.sha256()).isEqualTo(SHA256);
        assertThat(payload.toJobPayload()).containsExactly(
                Map.entry("assetId", ASSET_ID.toString()),
                Map.entry("generation", 0L),
                Map.entry("mimeType", "image/jpeg"),
                Map.entry("sha256", SHA256));
        assertThat(payload.idempotencyKey())
                .isEqualTo("local-staging-cleanup:" + ASSET_ID + ':' + SHA256 + ":g0");
        assertThat(payload.toString()).isEqualTo("LocalStagingCleanupPayload[redacted]");
    }

    @Test
    void rejectsMissingAdditionalOrNonTextIdentityFields() throws Exception {
        assertInvalid("""
                {"assetId":"6db0a6dc-bb6a-4cd7-8430-d565c4a0191e","generation":0,
                 "mimeType":"image/jpeg"}
                """);
        assertInvalid("""
                {"assetId":"6db0a6dc-bb6a-4cd7-8430-d565c4a0191e","generation":0,
                 "mimeType":"image/jpeg","sha256":"%s","objectKey":"staging/secret"}
                """.formatted(SHA256));
        assertInvalid("""
                {"assetId":7,"generation":0,"mimeType":"image/jpeg","sha256":"%s"}
                """.formatted(SHA256));
    }

    @Test
    void rejectsNonCanonicalUuidShaAndMime() throws Exception {
        assertInvalid("""
                {"assetId":"6DB0A6DC-BB6A-4CD7-8430-D565C4A0191E","generation":0,
                 "mimeType":"image/jpeg","sha256":"%s"}
                """.formatted(SHA256));
        assertInvalid("""
                {"assetId":"6db0a6dc-bb6a-4cd7-8430-d565c4a0191e","generation":0,
                 "mimeType":"image/jpeg","sha256":"%s"}
                """.formatted("A".repeat(64)));
        assertInvalid("""
                {"assetId":"6db0a6dc-bb6a-4cd7-8430-d565c4a0191e","generation":0,
                 "mimeType":"IMAGE/JPEG","sha256":"%s"}
                """.formatted(SHA256));
    }

    @Test
    void rejectsFractionalNegativeAndOverflowGenerations() throws Exception {
        assertInvalid(payloadWithGeneration("0.0"));
        assertInvalid(payloadWithGeneration("1e0"));
        assertInvalid(payloadWithGeneration("-1"));
        assertInvalid(payloadWithGeneration("9223372036854775808"));
        assertInvalid(payloadWithGeneration("1" + "0".repeat(20_000)));
    }

    @Test
    void acceptsTheExactMaximumLongGeneration() throws Exception {
        LocalStagingCleanupPayload payload = LocalStagingCleanupPayload.parse(
                read(payloadWithGeneration(Long.toString(Long.MAX_VALUE))));

        assertThat(payload.generation()).isEqualTo(Long.MAX_VALUE);
        assertThat(payload.idempotencyKey()).endsWith(":g" + Long.MAX_VALUE);
    }

    private String payloadWithGeneration(String generation) {
        return """
                {"assetId":"6db0a6dc-bb6a-4cd7-8430-d565c4a0191e","generation":%s,
                 "mimeType":"image/jpeg","sha256":"%s"}
                """.formatted(generation, SHA256);
    }

    private JsonNode read(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private void assertInvalid(String json) throws Exception {
        JsonNode node;
        try {
            node = read(json);
        } catch (Exception malformed) {
            node = null;
        }
        JsonNode input = node;
        assertThatIllegalArgumentException()
                .isThrownBy(() -> LocalStagingCleanupPayload.parse(input))
                .withMessage("local staging cleanup payload is invalid")
                .withNoCause();
    }
}
