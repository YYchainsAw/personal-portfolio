package xyz.yychainsaw.portfolio.message.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.common.error.DomainException;

class MessageCursorTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-18T01:02:03.123456Z");
    private static final UUID ID =
            UUID.fromString("94abcdef-0000-4000-8000-000000000002");

    @Test
    void encodesAndDecodesOneCanonicalBase64UrlJsonRepresentation() {
        MessageCursor cursor = new MessageCursor(CREATED_AT, ID);

        String encoded = cursor.encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+").doesNotContain("=");
        assertThat(new String(
                        Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo("{\"createdAt\":\"2026-07-18T01:02:03.123456Z\","
                        + "\"id\":\"" + ID + "\"}");
        assertThat(MessageCursor.decode(encoded)).isEqualTo(cursor);
        assertThat(MessageCursor.decode(encoded).encode()).isEqualTo(encoded);
    }

    @Test
    void rejectsEveryNoncanonicalOrStructurallyInvalidJsonCursor() {
        String canonicalTimestamp = CREATED_AT.toString();
        String canonicalId = ID.toString();
        List<String> invalidJson = List.of(
                "null",
                "[]",
                "{}",
                "{\"createdAt\":\"" + canonicalTimestamp + "\"}",
                "{\"id\":\"" + canonicalId + "\"}",
                "{\"id\":\"" + canonicalId + "\",\"createdAt\":\""
                        + canonicalTimestamp + "\"}",
                "{\"createdAt\":\"" + canonicalTimestamp + "\",\"id\":\""
                        + canonicalId + "\",\"extra\":true}",
                "{\"createdAt\":\"" + canonicalTimestamp + "\",\"createdAt\":\""
                        + canonicalTimestamp + "\",\"id\":\"" + canonicalId + "\"}",
                " {\"createdAt\":\"" + canonicalTimestamp + "\",\"id\":\""
                        + canonicalId + "\"}",
                "{\"createdAt\": \"" + canonicalTimestamp + "\",\"id\":\""
                        + canonicalId + "\"}",
                "{\"createdAt\":\"2026-07-18T01:02:03.123456000Z\",\"id\":\""
                        + canonicalId + "\"}",
                "{\"createdAt\":\"2026-07-18T01:02:03.123456789Z\",\"id\":\""
                        + canonicalId + "\"}",
                "{\"createdAt\":\"1969-12-31T23:59:59Z\",\"id\":\""
                        + canonicalId + "\"}",
                "{\"createdAt\":\"" + canonicalTimestamp + "\",\"id\":\""
                        + canonicalId.toUpperCase() + "\"}");

        for (String json : invalidJson) {
            assertInvalid(token(json));
        }
    }

    @Test
    void rejectsInvalidTokenAlphabetPaddingLengthAndUtf8() {
        String canonical = new MessageCursor(CREATED_AT, ID).encode();
        String alias = nonCanonicalAlias(
                new MessageCursor(Instant.parse("2026-07-18T01:02:03Z"), ID).encode());
        String tooLong = "A".repeat(117);
        String malformedUtf8 = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(new byte[] {(byte) 0xc3, (byte) 0x28});

        for (String invalid : List.of(
                " ",
                "not+a-url-token",
                canonical + "=",
                "A",
                alias,
                tooLong,
                malformedUtf8)) {
            assertInvalid(invalid);
        }
        assertInvalid(null);
    }

    @Test
    void constructorRequiresDatabaseSafeMicrosecondCoordinates() {
        assertThatNullPointerException()
                .isThrownBy(() -> new MessageCursor(null, ID))
                .withMessage("message cursor timestamp is required");
        assertThatNullPointerException()
                .isThrownBy(() -> new MessageCursor(CREATED_AT, null))
                .withMessage("message cursor id is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MessageCursor(
                        Instant.parse("2026-07-18T01:02:03.123456789Z"), ID))
                .withMessage("message cursor timestamp is invalid");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MessageCursor(
                        Instant.parse("1969-12-31T23:59:59.999999Z"), ID))
                .withMessage("message cursor timestamp is invalid");
    }

    @Test
    void cursorStringNeverExposesItsCoordinates() {
        assertThat(new MessageCursor(CREATED_AT, ID).toString())
                .isEqualTo("MessageCursor[<redacted>]")
                .doesNotContain(CREATED_AT.toString(), ID.toString());
    }

    private static String token(String json) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String nonCanonicalAlias(String canonical) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int remainder = canonical.length() % 4;
        if (remainder != 2 && remainder != 3) {
            throw new AssertionError("fixture token has no unused Base64 bits");
        }
        int last = alphabet.indexOf(canonical.charAt(canonical.length() - 1));
        return canonical.substring(0, canonical.length() - 1)
                + alphabet.charAt(last | 1);
    }

    private static void assertInvalid(String value) {
        assertThatThrownBy(() -> MessageCursor.decode(value))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("MESSAGE_CURSOR_INVALID");
                    assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(failure.fieldErrors()).containsExactlyEntriesOf(
                            java.util.Map.of("cursor", "invalid"));
                    assertThat(failure)
                            .hasMessage("MESSAGE_CURSOR_INVALID")
                            .hasNoCause();
                });
    }
}
