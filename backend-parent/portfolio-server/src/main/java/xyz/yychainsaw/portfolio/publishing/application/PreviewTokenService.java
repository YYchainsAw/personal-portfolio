package xyz.yychainsaw.portfolio.publishing.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.publishing.api.PreviewTokenRequest;
import xyz.yychainsaw.portfolio.publishing.api.PreviewTokenResponse;
import xyz.yychainsaw.portfolio.publishing.config.PreviewProperties;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PreviewTokenService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SIGNATURE_BYTES = 32;
    private static final int SIGNATURE_CHARACTERS = 43;
    private static final int MAXIMUM_TOKEN_CHARACTERS = 2_048;
    private static final int MAXIMUM_PAYLOAD_BYTES = 1_024;
    private static final int MAXIMUM_PAYLOAD_CHARACTERS =
            (MAXIMUM_PAYLOAD_BYTES * 8 + 5) / 6;
    private static final Base64.Encoder TOKEN_ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private final PreviewProperties properties;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final Supplier<UUID> nonceSupplier;
    private final Object keyMonitor = new Object();
    private final byte[] key;

    @Autowired
    public PreviewTokenService(
            PreviewProperties properties,
            Clock clock,
            ObjectMapper objectMapper) {
        this(properties, clock, objectMapper, UUID::randomUUID);
    }

    PreviewTokenService(
            PreviewProperties properties,
            Clock clock,
            ObjectMapper objectMapper,
            Supplier<UUID> nonceSupplier) {
        this.properties = Objects.requireNonNull(properties, "preview properties are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.mapper = tokenMapper(Objects.requireNonNull(objectMapper, "object mapper is required"));
        this.nonceSupplier = Objects.requireNonNull(nonceSupplier, "nonce supplier is required");
        this.key = properties.hmacKey();
        if (key.length == 0) {
            throw new IllegalArgumentException("preview HMAC key is required");
        }
    }

    public PreviewTokenResponse issue(PreviewTokenRequest request, UUID adminId) {
        Objects.requireNonNull(request, "preview token request is required");
        Objects.requireNonNull(adminId, "admin id is required");
        requireIssuable(request);

        Instant expiresAt = Objects.requireNonNull(clock.instant(), "clock instant is required")
                .plus(properties.ttl());
        UUID nonce = Objects.requireNonNull(
                nonceSupplier.get(), "nonce supplier returned no value");
        PreviewClaims claims = new PreviewClaims(
                adminId,
                request.aggregateType(),
                request.aggregateId(),
                request.workspaceVersion(),
                expiresAt,
                nonce);
        byte[] payload = encode(claims);
        if (payload.length > MAXIMUM_PAYLOAD_BYTES) {
            Arrays.fill(payload, (byte) 0);
            throw new IllegalStateException("preview token payload exceeds its bound");
        }

        byte[] signature = authenticate(payload);
        try {
            String token = TOKEN_ENCODER.encodeToString(payload)
                    + '.' + TOKEN_ENCODER.encodeToString(signature);
            if (token.length() > MAXIMUM_TOKEN_CHARACTERS) {
                throw new IllegalStateException("preview token exceeds its bound");
            }
            return new PreviewTokenResponse(token, expiresAt);
        } finally {
            Arrays.fill(payload, (byte) 0);
            Arrays.fill(signature, (byte) 0);
        }
    }

    public PreviewClaims verify(String token, UUID adminId) {
        try {
            return verifyAuthenticated(token, adminId);
        } catch (RuntimeException failure) {
            throw invalidToken();
        }
    }

    @PreDestroy
    void destroyKey() {
        synchronized (keyMonitor) {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "PreviewTokenService[key=<redacted>, ttl=" + properties.ttl() + ']';
    }

    private PreviewClaims verifyAuthenticated(String token, UUID adminId) {
        if (token == null
                || token.isEmpty()
                || token.length() > MAXIMUM_TOKEN_CHARACTERS
                || adminId == null) {
            throw malformed();
        }
        int separator = token.indexOf('.');
        if (separator <= 0
                || separator != token.lastIndexOf('.')
                || separator == token.length() - 1) {
            throw malformed();
        }
        String payloadSegment = token.substring(0, separator);
        String signatureSegment = token.substring(separator + 1);
        if (payloadSegment.length() > MAXIMUM_PAYLOAD_CHARACTERS
                || signatureSegment.length() != SIGNATURE_CHARACTERS
                || !isBase64UrlSegment(payloadSegment)
                || !isBase64UrlSegment(signatureSegment)) {
            throw malformed();
        }

        byte[] payload = decode(payloadSegment);
        byte[] signature = decode(signatureSegment);
        try {
            if (payload.length == 0
                    || payload.length > MAXIMUM_PAYLOAD_BYTES
                    || signature.length != SIGNATURE_BYTES
                    || !TOKEN_ENCODER.encodeToString(payload).equals(payloadSegment)
                    || !TOKEN_ENCODER.encodeToString(signature).equals(signatureSegment)) {
                throw malformed();
            }

            byte[] expectedSignature = authenticate(payload);
            try {
                if (!MessageDigest.isEqual(expectedSignature, signature)) {
                    throw malformed();
                }
            } finally {
                Arrays.fill(expectedSignature, (byte) 0);
            }

            PreviewClaims claims = decodeClaims(payload);
            byte[] canonical = encode(claims);
            try {
                if (!Arrays.equals(payload, canonical)) {
                    throw malformed();
                }
            } finally {
                Arrays.fill(canonical, (byte) 0);
            }
            requireConsumable(claims, adminId);
            return claims;
        } finally {
            Arrays.fill(payload, (byte) 0);
            Arrays.fill(signature, (byte) 0);
        }
    }

    private void requireConsumable(PreviewClaims claims, UUID adminId) {
        if (claims.adminId() == null
                || claims.aggregateType() == null
                || claims.aggregateId() == null
                || claims.expiresAt() == null
                || claims.nonce() == null
                || claims.workspaceVersion() < 0
                || !claims.adminId().equals(adminId)
                || !clock.instant().isBefore(claims.expiresAt())
                || claims.aggregateType() == AggregateType.PROJECT_CATALOG
                || (claims.aggregateType() == AggregateType.SITE
                        && !SiteWorkspaceDto.SITE_ID.equals(claims.aggregateId()))) {
            throw malformed();
        }
    }

    private static void requireIssuable(PreviewTokenRequest request) {
        if (request.aggregateType() == AggregateType.PROJECT_CATALOG) {
            throw new DomainException(
                    "CATALOG_PREVIEW_NOT_ALLOWED",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of());
        }
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (request.aggregateType() == null) {
            fieldErrors.put("aggregateType", "aggregate type is required");
        }
        if (request.aggregateId() == null) {
            fieldErrors.put("aggregateId", "aggregate id is required");
        } else if (request.aggregateType() == AggregateType.SITE
                && !SiteWorkspaceDto.SITE_ID.equals(request.aggregateId())) {
            fieldErrors.put("aggregateId", "site aggregate id is invalid");
        }
        if (request.workspaceVersion() < 0) {
            fieldErrors.put("workspaceVersion", "workspace version must not be negative");
        }
        if (!fieldErrors.isEmpty()) {
            throw new DomainException(
                    "PREVIEW_REQUEST_INVALID",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    fieldErrors);
        }
    }

    private byte[] authenticate(byte[] payload) {
        byte[] keySnapshot;
        synchronized (keyMonitor) {
            keySnapshot = Arrays.copyOf(key, key.length);
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keySnapshot, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw new IllegalStateException("preview token authentication failed");
        } finally {
            Arrays.fill(keySnapshot, (byte) 0);
        }
    }

    private PreviewClaims decodeClaims(byte[] payload) {
        try {
            return mapper.readValue(payload, PreviewClaims.class);
        } catch (IOException | RuntimeException failure) {
            throw malformed();
        }
    }

    private byte[] encode(PreviewClaims claims) {
        try {
            return mapper.writeValueAsBytes(claims);
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalStateException("preview token encoding failed");
        }
    }

    private static byte[] decode(String segment) {
        try {
            return TOKEN_DECODER.decode(segment.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException failure) {
            throw malformed();
        }
    }

    private static boolean isBase64UrlSegment(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-'
                    || character == '_')) {
                return false;
            }
        }
        return true;
    }

    private static ObjectMapper tokenMapper(ObjectMapper base) {
        return base.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("malformed preview token");
    }

    private static DomainException invalidToken() {
        return new DomainException(
                "PREVIEW_TOKEN_INVALID",
                HttpStatus.FORBIDDEN,
                Map.of());
    }

    public record PreviewClaims(
            UUID adminId,
            AggregateType aggregateType,
            UUID aggregateId,
            long workspaceVersion,
            Instant expiresAt,
            UUID nonce) {}
}
