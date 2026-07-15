package xyz.yychainsaw.portfolio.common.error;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

public final class DomainException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Map<String, String> fieldErrors;

    public DomainException(String code, HttpStatus status, Map<String, String> fieldErrors) {
        super(Objects.requireNonNull(code, "code"));
        this.code = code;
        this.status = Objects.requireNonNull(status, "status");
        this.fieldErrors = Map.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors"));
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
