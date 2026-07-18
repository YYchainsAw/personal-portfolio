package xyz.yychainsaw.portfolio.analytics.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class PublicAnalyticsBodyReader {
    static final int MAXIMUM_BODY_BYTES = 32_768;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "analyticsConsent", "visitorId", "sessionId", "events");
    private static final Set<String> EVENT_FIELDS = Set.of(
            "eventId", "type", "pageKey", "projectId", "referrer", "locale");
    private static final Pattern EVENT_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern PROJECT_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ObjectMapper mapper;

    public PublicAnalyticsBodyReader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "object mapper is required").copy();
        this.mapper.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public PublicAnalyticsBatchRequest read(HttpServletRequest request) {
        Objects.requireNonNull(request, "HTTP request is required");
        return parse(readBounded(request));
    }

    PublicAnalyticsBatchRequest parse(byte[] body) {
        Objects.requireNonNull(body, "analytics request body is required");
        try {
            JsonNode tree = mapper.readTree(body);
            if (!(tree instanceof ObjectNode object)) {
                throw malformedRequest();
            }
            requireExactFields(object, ROOT_FIELDS);
            JsonNode consent = object.get("analyticsConsent");
            if (consent == null || consent.isNull()) {
                return suppressedRequest();
            }
            if (!consent.isBoolean()) {
                throw malformedRequest();
            }
            if (!consent.booleanValue()) {
                return suppressedRequest();
            }

            requireTextOrNull(object, "visitorId");
            requireTextOrNull(object, "sessionId");
            JsonNode events = object.get("events");
            if (events != null && !events.isNull()) {
                if (!(events instanceof ArrayNode array)) {
                    throw malformedRequest();
                }
                for (JsonNode item : array) {
                    if (!(item instanceof ObjectNode event)) {
                        throw malformedRequest();
                    }
                    requireExactFields(event, EVENT_FIELDS);
                    requireUuidOrNull(event, "eventId", EVENT_ID);
                    requireTextOrNull(event, "type");
                    requireTextOrNull(event, "pageKey");
                    requireUuidOrNull(event, "projectId", PROJECT_ID);
                    requireTextOrNull(event, "referrer");
                    requireTextOrNull(event, "locale");
                }
            }
            PublicAnalyticsBatchRequest parsed = mapper.treeToValue(
                    tree, PublicAnalyticsBatchRequest.class);
            return Objects.requireNonNull(parsed, "analytics request is required");
        } catch (DomainException expected) {
            throw expected;
        } catch (JsonProcessingException | RuntimeException malformed) {
            throw malformedRequest();
        } catch (IOException impossibleForByteArray) {
            throw malformedRequest();
        }
    }

    private static PublicAnalyticsBatchRequest suppressedRequest() {
        return new PublicAnalyticsBatchRequest(false, null, null, null);
    }

    private static void requireExactFields(ObjectNode object, Set<String> allowed) {
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw malformedRequest();
            }
        }
    }

    private static void requireTextOrNull(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value != null && !value.isNull() && !value.isTextual()) {
            throw malformedRequest();
        }
    }

    private static void requireUuidOrNull(
            ObjectNode object, String field, Pattern canonicalFormat) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isTextual() || !canonicalFormat.matcher(value.textValue()).matches()) {
            throw malformedRequest();
        }
    }

    byte[] readBounded(HttpServletRequest request) {
        Objects.requireNonNull(request, "HTTP request is required");
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAXIMUM_BODY_BYTES) {
            throw payloadTooLarge();
        }
        try {
            byte[] body = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
            if (body.length > MAXIMUM_BODY_BYTES) {
                throw payloadTooLarge();
            }
            return body;
        } catch (DomainException expected) {
            throw expected;
        } catch (IOException | RuntimeException failure) {
            throw malformedRequest();
        }
    }

    private static DomainException payloadTooLarge() {
        return new DomainException(
                "PAYLOAD_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, Map.of());
    }

    private static DomainException malformedRequest() {
        return new DomainException(
                "MALFORMED_REQUEST", HttpStatus.BAD_REQUEST, Map.of());
    }
}
