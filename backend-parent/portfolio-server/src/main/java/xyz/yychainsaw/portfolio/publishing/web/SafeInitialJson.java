package xyz.yychainsaw.portfolio.publishing.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class SafeInitialJson {
    private final ObjectMapper mapper;

    public SafeInitialJson(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "object mapper is required");
    }

    public String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value)
                    .replace("&", "\\u0026")
                    .replace("<", "\\u003c")
                    .replace(">", "\\u003e")
                    .replace("\u2028", "\\u2028")
                    .replace("\u2029", "\\u2029");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "cannot serialize initial portfolio data", error);
        }
    }
}
