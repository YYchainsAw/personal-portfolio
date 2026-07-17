package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SafeInitialJsonTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SafeInitialJson json = new SafeInitialJson(mapper);

    @Test
    void escapesHtmlBreakoutsAndJavaScriptLineSeparatorsWithoutChangingData()
            throws Exception {
        Payload source = new Payload(
                "</template><script>alert('&')</script>>\u2028middle\u2029end");

        String serialized = json.serialize(source);

        assertThat(serialized)
                .contains("\\u003c/template\\u003e")
                .contains("\\u003cscript\\u003e")
                .contains("\\u0026")
                .contains("\\u003e")
                .contains("\\u2028")
                .contains("\\u2029")
                .doesNotContain("</template>", "<script>", "&", "\u2028", "\u2029");
        assertThat(mapper.readTree(serialized).path("value").textValue())
                .isEqualTo(source.value());
    }

    @Test
    void serializesNullAsJsonNull() {
        assertThat(json.serialize(null)).isEqualTo("null");
    }

    @Test
    void wrapsSerializationFailuresWithoutLeakingTheValue() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        given(failing.writeValueAsString(any()))
                .willThrow(new JsonProcessingException("synthetic failure") { });
        SafeInitialJson serializer = new SafeInitialJson(failing);

        assertThatThrownBy(() -> serializer.serialize("private-value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cannot serialize initial portfolio data")
                .hasCauseInstanceOf(JsonProcessingException.class)
                .hasMessageNotContaining("private-value");
    }

    @Test
    void requiresObjectMapper() {
        assertThatThrownBy(() -> new SafeInitialJson(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("object mapper is required");
    }

    private record Payload(String value) {
    }
}
