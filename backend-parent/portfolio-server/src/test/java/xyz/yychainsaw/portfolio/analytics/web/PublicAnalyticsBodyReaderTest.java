package xyz.yychainsaw.portfolio.analytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import xyz.yychainsaw.portfolio.common.error.DomainException;

class PublicAnalyticsBodyReaderTest {
    private final PublicAnalyticsBodyReader reader =
            new PublicAnalyticsBodyReader(new ObjectMapper());

    @Test
    void unknownLengthBodyAcceptsExactly32768Bytes() {
        byte[] body = new byte[32_768];
        Arrays.fill(body, (byte) ' ');

        assertThat(reader.readBounded(unknownLength(body))).containsExactly(body);
    }

    @Test
    void unknownLengthBodyRejectsThe32769thByte() {
        byte[] body = new byte[32_769];
        Arrays.fill(body, (byte) ' ');

        assertThatThrownBy(() -> reader.readBounded(unknownLength(body)))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PAYLOAD_TOO_LARGE");
                    assertThat(failure.fieldErrors()).isEmpty();
                });
    }

    private static MockHttpServletRequest unknownLength(byte[] body) {
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
}
