package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.publishing.api.PreviewTokenRequest;
import xyz.yychainsaw.portfolio.publishing.api.PreviewTokenResponse;
import xyz.yychainsaw.portfolio.publishing.config.PreviewProperties;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.support.MutableClock;

class PreviewTokenServiceTest {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plus(TTL);
    private static final UUID ADMIN_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000002");
    private static final UUID FIXED_NONCE =
            UUID.fromString("92000000-0000-4000-8000-000000000003");
    private static final UUID OTHER_ADMIN_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_PROJECT_ID =
            UUID.fromString("92000000-0000-4000-8000-000000000005");
    private static final byte[] KEY = sequentialBytes(32);
    private static final String KEY_BASE64 = Base64.getEncoder().encodeToString(KEY);
    private static final String FIXED_PAYLOAD = "{"
            + "\"adminId\":\"92000000-0000-4000-8000-000000000001\","
            + "\"aggregateId\":\"92000000-0000-4000-8000-000000000002\","
            + "\"aggregateType\":\"PROJECT\","
            + "\"expiresAt\":\"2026-07-14T00:10:00Z\","
            + "\"nonce\":\"92000000-0000-4000-8000-000000000003\","
            + "\"workspaceVersion\":7}";
    private static final String FIXED_SIGNATURE =
            "U30aF6Lq-g1G9pJMzjKjEikx_v8fU7BR2Q5xSnE-41M";

    @Test
    void issuesTheFixedCanonicalHmacVectorAndRoundTripsEveryClaim() {
        MutableClock clock = new MutableClock(NOW);
        PreviewTokenService service = service(clock, () -> FIXED_NONCE);

        PreviewTokenResponse response = service.issue(
                new PreviewTokenRequest(AggregateType.PROJECT, PROJECT_ID, 7), ADMIN_ID);

        String expectedToken = base64Url(FIXED_PAYLOAD.getBytes(StandardCharsets.UTF_8))
                + "." + FIXED_SIGNATURE;
        assertThat(response.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(response.token()).isEqualTo(expectedToken);
        assertThat(response.token().split("\\.", -1))
                .hasSize(2)
                .allSatisfy(segment -> assertThat(segment).doesNotContain("="));

        PreviewTokenService.PreviewClaims claims = service.verify(response.token(), ADMIN_ID);
        assertThat(claims.adminId()).isEqualTo(ADMIN_ID);
        assertThat(claims.aggregateType()).isEqualTo(AggregateType.PROJECT);
        assertThat(claims.aggregateId()).isEqualTo(PROJECT_ID);
        assertThat(claims.workspaceVersion()).isEqualTo(7);
        assertThat(claims.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(claims.nonce()).isEqualTo(FIXED_NONCE);
    }

    @Test
    void expiresAtTheExactBoundaryButRemainsValidOneNanosecondBeforeIt() {
        MutableClock clock = new MutableClock(NOW);
        PreviewTokenService service = service(clock, () -> FIXED_NONCE);
        PreviewTokenResponse response = service.issue(projectRequest(7), ADMIN_ID);

        clock.set(response.expiresAt().minusNanos(1));
        assertThat(service.verify(response.token(), ADMIN_ID).expiresAt())
                .isEqualTo(response.expiresAt());

        clock.set(response.expiresAt());
        assertInvalidToken(() -> service.verify(response.token(), ADMIN_ID));
    }

    @Test
    void identicalClaimsIssuedAtTheSameInstantStillUseFreshNonces() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger sequence = new AtomicInteger(3);
        PreviewTokenService service = service(clock, () -> UUID.fromString(
                "92000000-0000-4000-8000-%012d".formatted(sequence.getAndIncrement())));

        PreviewTokenResponse first = service.issue(projectRequest(7), ADMIN_ID);
        PreviewTokenResponse second = service.issue(projectRequest(7), ADMIN_ID);

        assertThat(first.expiresAt()).isEqualTo(second.expiresAt());
        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(service.verify(first.token(), ADMIN_ID).nonce())
                .isNotEqualTo(service.verify(second.token(), ADMIN_ID).nonce());
    }

    @Test
    void rejectsPayloadAndSignatureTamperingWithTheSameOpaqueFailure() {
        PreviewTokenService service = service(new MutableClock(NOW), () -> FIXED_NONCE);
        String token = service.issue(projectRequest(7), ADMIN_ID).token();
        String[] segments = token.split("\\.", -1);
        String payload = new String(
                Base64.getUrlDecoder().decode(segments[0]), StandardCharsets.UTF_8);
        String changedPayload = payload.replace(
                "\"workspaceVersion\":7", "\"workspaceVersion\":8");
        String payloadTampered = base64Url(changedPayload.getBytes(StandardCharsets.UTF_8))
                + "." + segments[1];
        char replacement = segments[1].charAt(0) == 'A' ? 'B' : 'A';
        String signatureTampered = segments[0] + "." + replacement + segments[1].substring(1);

        assertInvalidToken(() -> service.verify(payloadTampered, ADMIN_ID));
        assertInvalidToken(() -> service.verify(signatureTampered, ADMIN_ID));
    }

    @Test
    void rejectsACrossAdminTokenWithTheSameOpaqueFailure() {
        PreviewTokenService service = service(new MutableClock(NOW), () -> FIXED_NONCE);
        String token = service.issue(projectRequest(7), ADMIN_ID).token();

        assertInvalidToken(() -> service.verify(token, OTHER_ADMIN_ID));
    }

    @Test
    void rejectsMalformedAndBoundedWireInputsWithoutLeakingTheFailedCheck() {
        PreviewTokenService service = service(new MutableClock(NOW), () -> FIXED_NONCE);
        String valid = service.issue(projectRequest(7), ADMIN_ID).token();
        String[] segments = valid.split("\\.", -1);
        String validPayload = new String(
                Base64.getUrlDecoder().decode(segments[0]), StandardCharsets.UTF_8);

        List<String> malformed = new ArrayList<>(List.of(
                "",
                " ",
                ".",
                "payload-without-signature",
                "." + segments[1],
                segments[0] + ".",
                segments[0] + ".." + segments[1],
                valid + ".extra",
                segments[0] + "=." + segments[1],
                segments[0] + "." + segments[1] + "=",
                segments[0] + "." + segments[1].substring(0, 42) + "+",
                "中." + segments[1],
                signedToken("x".repeat(1_025)),
                wire(validPayload.getBytes(StandardCharsets.UTF_8), new byte[31]),
                wire(validPayload.getBytes(StandardCharsets.UTF_8), new byte[33]),
                "a".repeat(2_005) + "." + base64Url(new byte[32])));

        assertInvalidToken(() -> service.verify(null, ADMIN_ID));
        for (String token : malformed) {
            assertInvalidToken(() -> service.verify(token, ADMIN_ID));
        }
    }

    @Test
    void rejectsAuthenticatedNonCanonicalJsonUnknownFieldsAndDuplicateFields() {
        PreviewTokenService service = service(new MutableClock(NOW), () -> FIXED_NONCE);
        String reordered = "{"
                + "\"aggregateId\":\"" + PROJECT_ID + "\","
                + "\"adminId\":\"" + ADMIN_ID + "\","
                + "\"aggregateType\":\"PROJECT\","
                + "\"expiresAt\":\"" + EXPIRES_AT + "\","
                + "\"nonce\":\"" + FIXED_NONCE + "\","
                + "\"workspaceVersion\":7}";
        String whitespace = FIXED_PAYLOAD.replace("{", "{ ");
        String nonCanonicalInstant = FIXED_PAYLOAD.replace(
                "2026-07-14T00:10:00Z", "2026-07-14T00:10:00.000Z");
        String unknownField = FIXED_PAYLOAD.replace(
                "\"workspaceVersion\":7", "\"unexpected\":true,\"workspaceVersion\":7");
        String duplicateField = FIXED_PAYLOAD.replace(
                "{", "{\"adminId\":\"" + ADMIN_ID + "\",");

        for (String payload : List.of(
                reordered, whitespace, nonCanonicalInstant, unknownField, duplicateField)) {
            assertInvalidToken(() -> service.verify(signedToken(payload), ADMIN_ID));
        }
    }

    @Test
    void rejectsAuthenticatedMissingMalformedAndForbiddenClaimsDefensively() {
        PreviewTokenService service = service(new MutableClock(NOW), () -> FIXED_NONCE);
        String missingNonce = FIXED_PAYLOAD.replace(
                "\"nonce\":\"" + FIXED_NONCE + "\",", "");
        String malformedAdmin = FIXED_PAYLOAD.replace(ADMIN_ID.toString(), "not-a-uuid");
        String malformedAggregate = FIXED_PAYLOAD.replace(PROJECT_ID.toString(), "not-a-uuid");
        String malformedExpiry = FIXED_PAYLOAD.replace(EXPIRES_AT.toString(), "not-an-instant");
        String malformedNonce = FIXED_PAYLOAD.replace(FIXED_NONCE.toString(), "not-a-uuid");
        String unknownType = FIXED_PAYLOAD.replace("PROJECT", "UNKNOWN");
        String negativeVersion = FIXED_PAYLOAD.replace("\"workspaceVersion\":7", "\"workspaceVersion\":-1");
        String overflowingVersion = FIXED_PAYLOAD.replace(
                "\"workspaceVersion\":7",
                "\"workspaceVersion\":9223372036854775808");
        String wrongSiteId = canonicalPayload(
                ADMIN_ID, AggregateType.SITE, PROJECT_ID, 7, EXPIRES_AT, FIXED_NONCE);
        String catalog = canonicalPayload(
                ADMIN_ID, AggregateType.PROJECT_CATALOG, PROJECT_ID, 7, EXPIRES_AT, FIXED_NONCE);

        for (String payload : List.of(
                missingNonce,
                malformedAdmin,
                malformedAggregate,
                malformedExpiry,
                malformedNonce,
                unknownType,
                negativeVersion,
                overflowingVersion,
                wrongSiteId,
                catalog)) {
            assertInvalidToken(() -> service.verify(signedToken(payload), ADMIN_ID));
        }
    }

    @Test
    void acceptsSiteAndProjectVersionZeroButRejectsInvalidIssueClaims() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger sequence = new AtomicInteger(3);
        PreviewTokenService service = service(clock, () -> UUID.fromString(
                "92000000-0000-4000-8000-%012d".formatted(sequence.getAndIncrement())));

        PreviewTokenResponse project = service.issue(projectRequest(0), ADMIN_ID);
        PreviewTokenResponse site = service.issue(
                new PreviewTokenRequest(AggregateType.SITE, SiteWorkspaceDto.SITE_ID, 0),
                ADMIN_ID);

        assertThat(service.verify(project.token(), ADMIN_ID).workspaceVersion()).isZero();
        assertThat(service.verify(project.token(), ADMIN_ID).aggregateType())
                .isEqualTo(AggregateType.PROJECT);
        assertThat(service.verify(site.token(), ADMIN_ID).workspaceVersion()).isZero();
        assertThat(service.verify(site.token(), ADMIN_ID).aggregateId())
                .isEqualTo(SiteWorkspaceDto.SITE_ID);

        assertPreviewRequestInvalid(
                () -> service.issue(new PreviewTokenRequest(null, PROJECT_ID, 0), ADMIN_ID),
                "aggregateType");
        assertPreviewRequestInvalid(
                () -> service.issue(new PreviewTokenRequest(AggregateType.PROJECT, null, 0), ADMIN_ID),
                "aggregateId");
        assertPreviewRequestInvalid(
                () -> service.issue(new PreviewTokenRequest(
                        AggregateType.SITE, OTHER_PROJECT_ID, 0), ADMIN_ID),
                "aggregateId");
        assertPreviewRequestInvalid(
                () -> service.issue(new PreviewTokenRequest(
                        AggregateType.PROJECT, PROJECT_ID, -1), ADMIN_ID),
                "workspaceVersion");

        DomainException catalog = catchThrowableOfType(
                DomainException.class,
                () -> service.issue(new PreviewTokenRequest(
                        AggregateType.PROJECT_CATALOG, PROJECT_ID, 0), ADMIN_ID));
        assertThat(catalog).isNotNull();
        assertThat(catalog.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(catalog.code()).isEqualTo("CATALOG_PREVIEW_NOT_ALLOWED");
        assertThat(catalog.fieldErrors()).isEmpty();
    }

    @Test
    void previewPropertiesDecodeDefensivelyCopyAndRedactTheHmacKey() {
        PreviewProperties properties = properties(KEY_BASE64, TTL);
        byte[] firstRead = properties.hmacKey();
        firstRead[0] = (byte) 99;

        assertThat(properties.hmacKey()).containsExactly(KEY);
        assertThat(properties.ttl()).isEqualTo(TTL);
        assertThat(properties.toString())
                .contains("hmacKey=<redacted>", "ttl=PT10M")
                .doesNotContain(KEY_BASE64);

        String longerKey = Base64.getEncoder().encodeToString(sequentialBytes(64));
        assertThat(properties(longerKey, TTL).hmacKey()).hasSize(64);
    }

    @Test
    void previewPropertiesRejectInvalidBase64ShortKeysAndInvalidTtlWithoutEchoingSecrets() {
        String urlAlphabet = Base64.getUrlEncoder().encodeToString(bytes(32, (byte) 0xff));
        String shortKey = Base64.getEncoder().encodeToString(sequentialBytes(31));
        String unpaddedKey = KEY_BASE64.substring(0, KEY_BASE64.length() - 1);

        for (String invalidKey : List.of("not-standard-base64!", urlAlphabet, shortKey, unpaddedKey)) {
            assertThatThrownBy(() -> properties(invalidKey, TTL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(invalidKey)
                    .hasMessageNotContaining(KEY_BASE64);
        }

        for (Duration invalidTtl : List.of(
                Duration.ZERO,
                Duration.ofNanos(-1),
                TTL.plusNanos(1))) {
            assertThatThrownBy(() -> properties(KEY_BASE64, invalidTtl))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(KEY_BASE64);
        }
        assertThatThrownBy(() -> properties(KEY_BASE64, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(KEY_BASE64);
    }

    @Test
    void tokenServiceRequiresAConfiguredKeyAlthoughEmptyPropertiesCanBindForCliContexts() {
        MutableClock clock = new MutableClock(NOW);
        ObjectMapper mapper = objectMapper();
        PreviewProperties missing = properties(null, TTL);
        PreviewProperties blank = properties("   ", TTL);

        assertThat(missing.hmacKey()).isEmpty();
        assertThat(blank.hmacKey()).isEmpty();
        assertThatThrownBy(() -> new PreviewTokenService(missing, clock, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(KEY_BASE64);
        assertThatThrownBy(() -> new PreviewTokenService(blank, clock, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(KEY_BASE64);
    }

    @Test
    void previewTokenResponseKeepsTheWireCredentialButRedactsDiagnostics() {
        String credential = "payload.signature";
        PreviewTokenResponse response = new PreviewTokenResponse(credential, EXPIRES_AT);

        assertThat(response.token()).isEqualTo(credential);
        assertThat(response.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(response.toString())
                .contains("token=<redacted>", "expiresAt=" + EXPIRES_AT)
                .doesNotContain(credential);
    }

    private static PreviewTokenService service(
            MutableClock clock, Supplier<UUID> nonceSupplier) {
        return new PreviewTokenService(
                properties(KEY_BASE64, TTL), clock, objectMapper(), nonceSupplier);
    }

    private static PreviewProperties properties(String hmacKey, Duration ttl) {
        return new PreviewProperties(hmacKey, ttl);
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static PreviewTokenRequest projectRequest(long workspaceVersion) {
        return new PreviewTokenRequest(AggregateType.PROJECT, PROJECT_ID, workspaceVersion);
    }

    private static void assertInvalidToken(Runnable action) {
        DomainException failure = catchThrowableOfType(DomainException.class, action::run);
        assertThat(failure).isNotNull();
        assertThat(failure.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(failure.code()).isEqualTo("PREVIEW_TOKEN_INVALID");
        assertThat(failure.fieldErrors()).isEmpty();
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).isEqualTo("PREVIEW_TOKEN_INVALID");
    }

    private static void assertPreviewRequestInvalid(Runnable action, String field) {
        DomainException failure = catchThrowableOfType(DomainException.class, action::run);
        assertThat(failure).isNotNull();
        assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failure.code()).isEqualTo("PREVIEW_REQUEST_INVALID");
        assertThat(failure.fieldErrors()).containsOnlyKeys(field);
    }

    private static String canonicalPayload(
            UUID adminId,
            AggregateType aggregateType,
            UUID aggregateId,
            long workspaceVersion,
            Instant expiresAt,
            UUID nonce) {
        return "{"
                + "\"adminId\":\"" + adminId + "\","
                + "\"aggregateId\":\"" + aggregateId + "\","
                + "\"aggregateType\":\"" + aggregateType + "\","
                + "\"expiresAt\":\"" + expiresAt + "\","
                + "\"nonce\":\"" + nonce + "\","
                + "\"workspaceVersion\":" + workspaceVersion + "}";
    }

    private static String signedToken(String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return wire(payloadBytes, hmac(payloadBytes));
    }

    private static String wire(byte[] payload, byte[] signature) {
        return base64Url(payload) + "." + base64Url(signature);
    }

    private static byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(KEY, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception failure) {
            throw new AssertionError("test HMAC setup failed", failure);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sequentialBytes(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) index;
        }
        return bytes;
    }

    private static byte[] bytes(int size, byte value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
