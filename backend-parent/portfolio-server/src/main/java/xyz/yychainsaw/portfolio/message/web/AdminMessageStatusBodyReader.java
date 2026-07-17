package xyz.yychainsaw.portfolio.message.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.message.application.MessageStatus;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminMessageStatusBodyReader {
    static final int MAXIMUM_BODY_BYTES = 4_096;

    private final ObjectMapper mapper;

    public AdminMessageStatusBodyReader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(
                mapper, "object mapper is required").copy();
        this.mapper.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public UpdateMessageStatusRequest read(HttpServletRequest request) {
        Objects.requireNonNull(request, "HTTP request is required");
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAXIMUM_BODY_BYTES) {
            throw payloadTooLarge();
        }

        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
        } catch (IOException | RuntimeException failure) {
            throw malformedRequest();
        }
        if (body.length > MAXIMUM_BODY_BYTES) {
            throw payloadTooLarge();
        }

        JsonNode tree;
        try {
            tree = mapper.readTree(body);
        } catch (JsonProcessingException malformed) {
            throw malformedRequest();
        } catch (IOException impossibleForByteArray) {
            throw malformedRequest();
        }
        if (!(tree instanceof ObjectNode object)) {
            throw requestBodyInvalid();
        }
        if (object.size() != 2
                || !object.has("status")
                || !object.has("version")) {
            throw requestBodyInvalid();
        }

        MessageStatus status = status(object.get("status"));
        int version = version(object.get("version"));
        return new UpdateMessageStatusRequest(status, version);
    }

    private static MessageStatus status(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw validationError("status", "must not be null");
        }
        String encoded = value.textValue();
        try {
            MessageStatus parsed = MessageStatus.valueOf(encoded);
            if (!parsed.name().equals(encoded)) {
                throw new IllegalArgumentException("noncanonical message status");
            }
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw validationError("status", "invalid");
        }
    }

    private static int version(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw validationError("version", "invalid");
        }
        int parsed = value.intValue();
        if (parsed < 0) {
            throw validationError(
                    "version", "must be greater than or equal to 0");
        }
        return parsed;
    }

    private static DomainException validationError(String field, String message) {
        return new DomainException(
                "VALIDATION_ERROR",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of(field, message));
    }

    private static DomainException requestBodyInvalid() {
        return new DomainException(
                "REQUEST_BODY_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("request", "request body is invalid"));
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
