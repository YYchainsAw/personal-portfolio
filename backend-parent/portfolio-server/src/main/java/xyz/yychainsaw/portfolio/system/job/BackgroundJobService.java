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
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BackgroundJobService {
    static final int MAX_ATTEMPTS = 10;

    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final int MAX_NUMBER_CHARACTERS = MAX_PAYLOAD_BYTES;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 160;
    private static final int MAX_WORKER_ID_LENGTH = 120;
    private static final Duration MAX_LEASE_DURATION = Duration.ofHours(24);
    private static final Set<String> FAILURE_CODES = Set.of(
            "JOB_FAILED",
            "JOB_HANDLER_FAILED",
            "JOB_HANDLER_UNAVAILABLE",
            "JOB_HANDLER_INTERRUPTED",
            "JOB_ATTEMPTS_EXHAUSTED");

    private final BackgroundJobMapper mapper;
    private final ObjectReader exactJsonReader;
    private final ObjectWriter inputJsonWriter;
    private final JsonFactory exactJsonFactory;
    private final Clock clock;
    private final JobHandlerRegistry handlers;

    public BackgroundJobService(
            BackgroundJobMapper mapper,
            ObjectMapper objectMapper,
            Clock clock,
            JobHandlerRegistry handlers) {
        this.mapper = Objects.requireNonNull(mapper, "job mapper is required");
        ObjectMapper requiredObjectMapper =
                Objects.requireNonNull(objectMapper, "object mapper is required");
        ObjectMapper exactObjectMapper = requiredObjectMapper.copy();
        exactObjectMapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxNumberLength(MAX_NUMBER_CHARACTERS)
                        .build());
        this.exactJsonFactory = exactObjectMapper.getFactory();
        this.exactJsonReader = exactObjectMapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(JsonNodeFactory.withExactBigDecimals(true));
        this.inputJsonWriter = requiredObjectMapper.writer();
        this.clock = requireUtcClock(clock);
        this.handlers = Objects.requireNonNull(handlers, "job handlers are required");
    }

    @Transactional
    public UUID enqueue(
            String jobType, String idempotencyKey, Map<String, ?> payload) {
        requireRegisteredJobType(jobType);
        requireIdempotencyKey(idempotencyKey);
        SerializedPayload serialized = serializePayload(payload);
        OffsetDateTime now = now();
        UUID candidateId = UUID.randomUUID();

        Optional<UUID> inserted;
        try {
            inserted = mapper.insertIfAbsent(
                    candidateId, jobType, idempotencyKey, serialized.json(), now);
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_ENQUEUE_FAILED");
        }
        if (inserted.isPresent()) {
            return inserted.get();
        }

        ExistingBackgroundJobRow existing;
        try {
            existing = mapper.findByIdempotencyKey(idempotencyKey, serialized.json())
                    .orElseThrow(() -> fixedFailure("JOB_ENQUEUE_FAILED"));
        } catch (RuntimeException exception) {
            if (isFixedFailure(exception, "JOB_ENQUEUE_FAILED")) {
                throw exception;
            }
            throw fixedFailure("JOB_ENQUEUE_FAILED");
        }

        if (!existing.jobType().equals(jobType)
                || !existing.payloadMatches()) {
            throw fixedFailure("JOB_IDEMPOTENCY_CONFLICT");
        }
        return existing.id();
    }

    @Transactional
    public Optional<LeasedJob> leaseNext(String workerId, Duration leaseDuration) {
        requireWorkerId(workerId);
        requireLeaseDuration(leaseDuration);
        OffsetDateTime now = now();
        OffsetDateTime leaseUntil;
        try {
            leaseUntil = now.plus(leaseDuration);
        } catch (DateTimeException | ArithmeticException exception) {
            throw invalidLeaseDuration();
        }

        Optional<BackgroundJobRow> exhausted;
        try {
            exhausted = mapper.deadLetterNextExhausted(
                    now, "JOB_ATTEMPTS_EXHAUSTED");
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_LEASE_FAILED");
        }
        exhausted.ifPresent(row -> invokeDeadLetter(row, "JOB_ATTEMPTS_EXHAUSTED"));

        Optional<BackgroundJobRow> claimed;
        try {
            claimed = mapper.claimNext(workerId, now, leaseUntil);
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_LEASE_FAILED");
        }
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        BackgroundJobRow row = claimed.get();
        try {
            return Optional.of(new LeasedJob(
                    row.id(),
                    row.jobType(),
                    parseStoredPayload(row.payloadJson()),
                    row.attempts(),
                    row.leaseOwner()));
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_LEASE_FAILED");
        }
    }

    @Transactional
    public boolean succeed(UUID jobId, String leaseOwner, int attemptFence) {
        requireCompletionFence(jobId, leaseOwner, attemptFence);
        try {
            return mapper.succeed(jobId, leaseOwner, attemptFence) == 1;
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_COMPLETION_FAILED");
        }
    }

    @Transactional
    public boolean fail(
            UUID jobId,
            String leaseOwner,
            int attemptFence,
            String safeSummaryCode) {
        requireCompletionFence(jobId, leaseOwner, attemptFence);
        String summary = normalizeFailureCode(safeSummaryCode);
        OffsetDateTime now = now();
        OffsetDateTime nextRunAt = now;
        if (attemptFence < MAX_ATTEMPTS) {
            long delaySeconds = Math.min(1L << attemptFence, 3_600L);
            try {
                nextRunAt = now.plusSeconds(delaySeconds);
            } catch (DateTimeException | ArithmeticException exception) {
                throw fixedFailure("JOB_COMPLETION_FAILED");
            }
        }

        Optional<BackgroundJobRow> transitioned;
        try {
            transitioned = mapper.fail(
                    jobId, leaseOwner, attemptFence, summary, nextRunAt);
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_COMPLETION_FAILED");
        }
        if (transitioned.isEmpty()) {
            return false;
        }
        BackgroundJobRow row = transitioned.get();
        if ("DEAD".equals(row.status())) {
            invokeDeadLetter(row, summary);
        }
        return true;
    }

    private void requireRegisteredJobType(String jobType) {
        if (!JobHandlerRegistry.isCanonicalType(jobType) || !handlers.contains(jobType)) {
            throw new IllegalArgumentException("job type is invalid");
        }
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (!isVisibleAscii(idempotencyKey, MAX_IDEMPOTENCY_KEY_LENGTH)) {
            throw new IllegalArgumentException("job idempotency key is invalid");
        }
    }

    private SerializedPayload serializePayload(Map<String, ?> payload) {
        if (payload == null) {
            throw invalidPayload();
        }
        try {
            byte[] encoded = inputJsonWriter.writeValueAsBytes(payload);
            if (encoded.length > MAX_PAYLOAD_BYTES) {
                throw invalidPayload();
            }
            JsonNode semanticValue = exactJsonReader.readTree(encoded);
            if (semanticValue == null
                    || !semanticValue.isObject()
                    || !hasSafeNumericForms(semanticValue)) {
                throw invalidPayload();
            }
            byte[] canonical = writeCanonicalPayload(semanticValue);
            return new SerializedPayload(new String(canonical, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            if ("job payload is invalid".equals(exception.getMessage())) {
                throw exception;
            }
            throw invalidPayload();
        } catch (Exception exception) {
            throw invalidPayload();
        }
    }

    private JsonNode parseStoredPayload(String payloadJson) {
        if (payloadJson == null) {
            throw fixedFailure("JOB_PAYLOAD_INVALID");
        }
        try {
            JsonNode payload = exactJsonReader.readTree(payloadJson);
            if (payload == null
                    || !payload.isObject()
                    || !hasSafeNumericForms(payload)) {
                throw fixedFailure("JOB_PAYLOAD_INVALID");
            }
            writeCanonicalPayload(payload);
            return payload.deepCopy();
        } catch (RuntimeException exception) {
            if (isFixedFailure(exception, "JOB_PAYLOAD_INVALID")) {
                throw exception;
            }
            throw fixedFailure("JOB_PAYLOAD_INVALID");
        } catch (Exception exception) {
            throw fixedFailure("JOB_PAYLOAD_INVALID");
        }
    }

    private void invokeDeadLetter(BackgroundJobRow row, String summaryCode) {
        Optional<JobHandler> handler = handlers.find(row.jobType());
        if (handler.isEmpty()) {
            return;
        }
        try {
            handler.get().onDeadLetter(parseStoredPayload(row.payloadJson()), summaryCode);
        } catch (Exception exception) {
            throw fixedFailure("job dead-letter hook failed");
        }
    }

    private OffsetDateTime now() {
        try {
            Instant instant = Objects.requireNonNull(clock.instant(), "clock returned no instant");
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            throw fixedFailure("JOB_CLOCK_FAILED");
        }
    }

    static boolean isValidWorkerId(String workerId) {
        return isVisibleAscii(workerId, MAX_WORKER_ID_LENGTH);
    }

    private static void requireWorkerId(String workerId) {
        if (!isValidWorkerId(workerId)) {
            throw new IllegalArgumentException("worker id is invalid");
        }
    }

    private static void requireLeaseDuration(Duration leaseDuration) {
        if (leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(MAX_LEASE_DURATION) > 0) {
            throw invalidLeaseDuration();
        }
    }

    private static void requireCompletionFence(
            UUID jobId, String leaseOwner, int attemptFence) {
        if (jobId == null
                || !isValidWorkerId(leaseOwner)
                || attemptFence < 1
                || attemptFence > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("job completion fence is invalid");
        }
    }

    private static String normalizeFailureCode(String summaryCode) {
        return summaryCode != null && FAILURE_CODES.contains(summaryCode)
                ? summaryCode
                : "JOB_FAILED";
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

    private byte[] writeCanonicalPayload(JsonNode payload) throws IOException {
        BoundedPayloadOutputStream output = new BoundedPayloadOutputStream();
        try (JsonGenerator generator = exactJsonFactory.createGenerator(output)) {
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

    private static Clock requireUtcClock(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("job clock is required");
        }
        ZoneRules rules = clock.getZone().getRules();
        if (!rules.isFixedOffset()
                || !ZoneOffset.UTC.equals(rules.getOffset(Instant.EPOCH))) {
            throw new IllegalArgumentException("job clock must use UTC");
        }
        return clock;
    }

    private static IllegalArgumentException invalidPayload() {
        return new IllegalArgumentException("job payload is invalid");
    }

    private static IllegalArgumentException invalidLeaseDuration() {
        return new IllegalArgumentException("lease duration is invalid");
    }

    private static IllegalStateException fixedFailure(String message) {
        return new IllegalStateException(message);
    }

    private static boolean isFixedFailure(RuntimeException exception, String message) {
        return exception.getClass() == IllegalStateException.class
                && message.equals(exception.getMessage())
                && exception.getCause() == null;
    }

    private record SerializedPayload(String json) {}

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
            requireCapacity(length);
            output.write(bytes, offset, length);
        }

        private void requireCapacity(int additionalBytes) throws IOException {
            if (additionalBytes < 0
                    || additionalBytes > MAX_PAYLOAD_BYTES - output.size()) {
                throw new IOException("job payload exceeds its byte budget");
            }
        }

        private byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
