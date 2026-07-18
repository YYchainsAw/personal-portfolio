package xyz.yychainsaw.portfolio.message.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import xyz.yychainsaw.portfolio.common.error.DomainException;

class PublicContactBodyReaderTest {
    private final PublicContactBodyReader reader =
            new PublicContactBodyReader(new ObjectMapper());

    @Test
    void unknownLengthStreamStillRejectsMoreThan32768RawBytes() {
        byte[] maximum = paddedHoneypotJson(32_768);
        byte[] oversized = paddedHoneypotJson(32_769);

        PublicContactRequest accepted = reader.read(unknownLengthRequest(maximum));
        DomainException rejected = catchThrowableOfType(
                () -> reader.read(unknownLengthRequest(oversized)),
                DomainException.class);

        assertThat(accepted.website()).isEqualTo("https://spam.invalid");
        assertThat(rejected.code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(rejected.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void explicitNullPrimitiveAndTrailingJsonRemainMalformed() {
        DomainException nullConsent = catchThrowableOfType(
                () -> reader.read(request("{\"privacyAccepted\":null}")),
                DomainException.class);
        DomainException trailing = catchThrowableOfType(
                () -> reader.read(request("{\"website\":\"\"} {}")),
                DomainException.class);

        assertThat(nullConsent.code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(trailing.code()).isEqualTo("MALFORMED_REQUEST");
    }

    private static MockHttpServletRequest unknownLengthRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body);
        return request;
    }

    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static byte[] paddedHoneypotJson(int size) {
        byte[] json = "{\"website\":\"https://spam.invalid\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[size];
        Arrays.fill(padded, (byte) ' ');
        System.arraycopy(json, 0, padded, 0, json.length);
        return padded;
    }
}
