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

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class PublicContactBodyReader {
    static final int MAXIMUM_BODY_BYTES = 32_768;

    private final ObjectMapper mapper;

    public PublicContactBodyReader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "object mapper is required").copy();
        this.mapper.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public PublicContactRequest read(HttpServletRequest request) {
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

        try {
            JsonNode tree = mapper.readTree(body);
            if (!(tree instanceof ObjectNode object)) {
                throw malformedRequest();
            }
            requireTextOrNull(object, "name");
            requireTextOrNull(object, "email");
            requireTextOrNull(object, "subject");
            requireTextOrNull(object, "message");
            requireTextOrNull(object, "website");
            JsonNode privacyAccepted = object.get("privacyAccepted");
            if (privacyAccepted == null) {
                object.put("privacyAccepted", false);
            } else if (!privacyAccepted.isBoolean()) {
                throw malformedRequest();
            }
            PublicContactRequest parsed = mapper.treeToValue(
                    tree, PublicContactRequest.class);
            return Objects.requireNonNull(parsed, "contact request is required");
        } catch (JsonProcessingException | RuntimeException malformed) {
            throw malformedRequest();
        } catch (IOException impossibleForByteArray) {
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

    private static void requireTextOrNull(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (value != null && !value.isNull() && !value.isTextual()) {
            throw malformedRequest();
        }
    }
}
