package xyz.yychainsaw.portfolio.system.job;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class JobPayloadCodec {
    static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 160;

    private static final int MAX_STORED_PAYLOAD_BYTES = 4 * MAX_PAYLOAD_BYTES;
    private static final int MAX_NUMBER_CHARACTERS = MAX_PAYLOAD_BYTES;
    private static final Pattern JOB_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final ObjectWriter inputWriter;
    private final ObjectReader exactReader;
    private final JsonFactory exactFactory;

    public JobPayloadCodec(ObjectMapper objectMapper) {
        ObjectMapper required = Objects.requireNonNull(objectMapper, "object mapper is required");
        ObjectMapper exact = required.copy();
        exact.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNumberLength(MAX_NUMBER_CHARACTERS)
                .build());
        this.inputWriter = required.writer();
        this.exactReader = exact.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(JsonNodeFactory.withExactBigDecimals(true));
        this.exactFactory = exact.getFactory();
    }

    public String serialize(Map<String, ?> payload) {
        if (payload == null) {
            throw invalidPayload();
        }
        try {
            byte[] encoded = inputWriter.writeValueAsBytes(payload);
            if (encoded.length > MAX_PAYLOAD_BYTES) {
                throw invalidPayload();
            }
            JsonNode semanticValue = exactReader.readTree(encoded);
            if (semanticValue == null
                    || !semanticValue.isObject()
                    || !hasSafeNumericForms(semanticValue)) {
                throw invalidPayload();
            }
            return new String(writeCanonicalPayload(semanticValue), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            if ("job payload is invalid".equals(exception.getMessage())
                    && exception.getCause() == null) {
                throw exception;
            }
            throw invalidPayload();
        } catch (Exception exception) {
            throw invalidPayload();
        }
    }

    public JsonNode parseStored(String payloadJson) {
        if (payloadJson == null) {
            throw invalidPayload();
        }
        try {
            byte[] encoded = payloadJson.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_STORED_PAYLOAD_BYTES) {
                throw invalidPayload();
            }
            JsonNode payload = exactReader.readTree(encoded);
            if (payload == null
                    || !payload.isObject()
                    || !hasSafeNumericForms(payload)) {
                throw invalidPayload();
            }
            writeCanonicalPayload(payload);
            return payload.deepCopy();
        } catch (IllegalArgumentException exception) {
            if ("job payload is invalid".equals(exception.getMessage())
                    && exception.getCause() == null) {
                throw exception;
            }
            throw invalidPayload();
        } catch (Exception exception) {
            throw invalidPayload();
        }
    }

    public static void requireJobType(String jobType) {
        if (jobType == null || !JOB_TYPE.matcher(jobType).matches()) {
            throw new IllegalArgumentException("job type is invalid");
        }
    }

    public static void requireIdempotencyKey(String idempotencyKey) {
        if (!isVisibleAscii(idempotencyKey, MAX_IDEMPOTENCY_KEY_LENGTH)) {
            throw new IllegalArgumentException("job idempotency key is invalid");
        }
    }

    public static void requireScheduledAt(OffsetDateTime scheduledAt) {
        if (scheduledAt == null || !ZoneOffset.UTC.equals(scheduledAt.getOffset())) {
            throw new IllegalArgumentException("job schedule is invalid");
        }
    }

    private byte[] writeCanonicalPayload(JsonNode payload) throws IOException {
        BoundedPayloadOutputStream output = new BoundedPayloadOutputStream();
        try (JsonGenerator generator = exactFactory.createGenerator(output)) {
            writeCanonicalNode(generator, payload);
        }
        return output.toByteArray();
    }

    private static void writeCanonicalNode(JsonGenerator generator, JsonNode node)
            throws IOException {
        if (node.isObject()) {
            generator.writeStartObject();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                generator.writeFieldName(field.getKey());
                writeCanonicalNode(generator, field.getValue());
            }
            generator.writeEndObject();
            return;
        }
        if (node.isArray()) {
            generator.writeStartArray();
            Iterator<JsonNode> elements = node.elements();
            while (elements.hasNext()) {
                writeCanonicalNode(generator, elements.next());
            }
            generator.writeEndArray();
            return;
        }
        if (node.isIntegralNumber()) {
            generator.writeNumber(node.asText());
            return;
        }
        if (node.isFloatingPointNumber()) {
            generator.writeNumber(node.decimalValue().toPlainString());
            return;
        }
        if (node.isTextual()) {
            generator.writeString(node.textValue());
            return;
        }
        if (node.isBoolean()) {
            generator.writeBoolean(node.booleanValue());
            return;
        }
        if (node.isNull()) {
            generator.writeNull();
            return;
        }
        throw new IOException("unsupported JSON node");
    }

    private static boolean hasSafeNumericForms(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node.asText().length() <= MAX_NUMBER_CHARACTERS;
        }
        if (node.isFloatingPointNumber()) {
            BigDecimal value = node.decimalValue();
            if (value.signum() == 0 && value.scale() <= 0) {
                return true;
            }
            long precision = value.precision();
            long scale = value.scale();
            long sign = value.signum() < 0 ? 1L : 0L;
            long plainLength = scale <= 0
                    ? sign + precision - scale
                    : (precision > scale
                            ? sign + precision + 1L
                            : sign + scale + 2L);
            return plainLength <= MAX_NUMBER_CHARACTERS;
        }
        if (node.isContainerNode()) {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                if (!hasSafeNumericForms(children.next())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isVisibleAscii(String value, int maximumLength) {
        if (value == null
                || value.isEmpty()
                || value.length() > maximumLength
                || !value.equals(value.trim())) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException invalidPayload() {
        return new IllegalArgumentException("job payload is invalid");
    }

    private static final class BoundedPayloadOutputStream extends OutputStream {
        private final ByteArrayOutputStream output =
                new ByteArrayOutputStream(MAX_PAYLOAD_BYTES);

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            output.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            output.write(bytes, offset, length);
        }

        private void requireCapacity(int additionalBytes) throws IOException {
            if (additionalBytes < 0
                    || additionalBytes > MAX_PAYLOAD_BYTES - output.size()) {
                throw new IOException("payload exceeds limit");
            }
        }

        private byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
