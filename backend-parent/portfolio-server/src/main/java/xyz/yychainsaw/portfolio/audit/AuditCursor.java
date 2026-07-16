package xyz.yychainsaw.portfolio.audit;

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

record AuditCursor(Instant createdAt, UUID id) {
    private static final int MAXIMUM_TOKEN_LENGTH = 86;
    private static final Instant EARLIEST = Instant.EPOCH;
    private static final Instant LATEST =
            Instant.parse("9999-12-31T23:59:59.999999Z");
    private static final Pattern URL_TOKEN = Pattern.compile("[A-Za-z0-9_-]+");

    AuditCursor {
        Objects.requireNonNull(createdAt, "audit cursor timestamp is required");
        Objects.requireNonNull(id, "audit cursor id is required");
        if (!isSupported(createdAt)) {
            throw new IllegalArgumentException("audit cursor timestamp is invalid");
        }
    }

    String encode() {
        String decoded = createdAt + "\n" + id;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(decoded.getBytes(StandardCharsets.UTF_8));
    }

    static AuditCursor decode(String value) {
        try {
            if (value == null
                    || value.isBlank()
                    || value.length() > MAXIMUM_TOKEN_LENGTH
                    || !URL_TOKEN.matcher(value).matches()) {
                throw new IllegalArgumentException("invalid cursor token");
            }
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            String decoded = strictUtf8(bytes);
            int separator = decoded.indexOf('\n');
            if (separator <= 0 || separator != decoded.lastIndexOf('\n')) {
                throw new IllegalArgumentException("invalid cursor shape");
            }
            String timestampValue = decoded.substring(0, separator);
            String idValue = decoded.substring(separator + 1);
            Instant timestamp = Instant.parse(timestampValue);
            UUID id = UUID.fromString(idValue);
            if (!timestamp.toString().equals(timestampValue)
                    || !id.toString().equals(idValue)) {
                throw new IllegalArgumentException("noncanonical cursor value");
            }
            AuditCursor cursor = new AuditCursor(timestamp, id);
            if (!cursor.encode().equals(value)) {
                throw new IllegalArgumentException("noncanonical cursor token");
            }
            return cursor;
        } catch (CharacterCodingException | RuntimeException failure) {
            throw invalid();
        }
    }

    static boolean isSupported(Instant value) {
        return value != null
                && !value.isBefore(EARLIEST)
                && !value.isAfter(LATEST)
                && value.getNano() % 1_000 == 0;
    }

    @Override
    public String toString() {
        return "AuditCursor[<redacted>]";
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
                "AUDIT_CURSOR_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("cursor", "invalid"));
    }
}
