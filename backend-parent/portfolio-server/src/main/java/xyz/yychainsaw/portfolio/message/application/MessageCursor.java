package xyz.yychainsaw.portfolio.message.application;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;

record MessageCursor(Instant createdAt, UUID id) {
    private static final int MAXIMUM_TOKEN_LENGTH = 116;
    private static final Instant EARLIEST = Instant.EPOCH;
    private static final Instant LATEST =
            Instant.parse("9999-12-31T23:59:59.999999Z");
    private static final Pattern URL_TOKEN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String JSON_PREFIX = "{\"createdAt\":\"";
    private static final String JSON_SEPARATOR = "\",\"id\":\"";
    private static final String JSON_SUFFIX = "\"}";

    MessageCursor {
        Objects.requireNonNull(createdAt, "message cursor timestamp is required");
        Objects.requireNonNull(id, "message cursor id is required");
        if (!isSupported(createdAt)) {
            throw new IllegalArgumentException("message cursor timestamp is invalid");
        }
    }

    String encode() {
        String json = JSON_PREFIX + createdAt + JSON_SEPARATOR + id + JSON_SUFFIX;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static MessageCursor decode(String value) {
        try {
            if (value == null
                    || value.isBlank()
                    || value.length() > MAXIMUM_TOKEN_LENGTH
                    || !URL_TOKEN.matcher(value).matches()) {
                throw new IllegalArgumentException("invalid cursor token");
            }
            String json = strictUtf8(Base64.getUrlDecoder().decode(value));
            if (!json.startsWith(JSON_PREFIX) || !json.endsWith(JSON_SUFFIX)) {
                throw new IllegalArgumentException("invalid cursor shape");
            }
            int separator = json.indexOf(JSON_SEPARATOR, JSON_PREFIX.length());
            if (separator < JSON_PREFIX.length()
                    || separator != json.lastIndexOf(JSON_SEPARATOR)) {
                throw new IllegalArgumentException("invalid cursor fields");
            }
            String timestampValue = json.substring(JSON_PREFIX.length(), separator);
            String idValue = json.substring(
                    separator + JSON_SEPARATOR.length(),
                    json.length() - JSON_SUFFIX.length());
            Instant timestamp = Instant.parse(timestampValue);
            UUID id = UUID.fromString(idValue);
            if (!timestamp.toString().equals(timestampValue)
                    || !id.toString().equals(idValue)) {
                throw new IllegalArgumentException("noncanonical cursor value");
            }
            MessageCursor cursor = new MessageCursor(timestamp, id);
            if (!cursor.encode().equals(value)) {
                throw new IllegalArgumentException("noncanonical cursor token");
            }
            return cursor;
        } catch (CharacterCodingException | RuntimeException failure) {
            throw invalid();
        }
    }

    @Override
    public String toString() {
        return "MessageCursor[<redacted>]";
    }

    private static boolean isSupported(Instant value) {
        return value != null
                && !value.isBefore(EARLIEST)
                && !value.isAfter(LATEST)
                && value.getNano() % 1_000 == 0;
    }

    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static DomainException invalid() {
        return new DomainException(
                "MESSAGE_CURSOR_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("cursor", "invalid"));
    }
}
