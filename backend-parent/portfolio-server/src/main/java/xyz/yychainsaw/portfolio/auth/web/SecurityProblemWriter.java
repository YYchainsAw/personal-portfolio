package xyz.yychainsaw.portfolio.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.trace.TraceIds;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class SecurityProblemWriter {
    private static final String TRACE_HEADER = "X-Trace-Id";

    private final ObjectMapper json;

    public SecurityProblemWriter(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "object mapper is required");
    }

    public void write(HttpServletResponse response, HttpStatus status, String code)
            throws IOException {
        Objects.requireNonNull(response, "response is required");
        ProblemContract contract = contract(status, code);
        String traceId = TraceIds.current();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, contract.detail());
        problem.setTitle(contract.detail());
        problem.setType(URI.create(
                "urn:portfolio:problem:" + contract.code().toLowerCase(Locale.ROOT)));
        problem.setProperty("code", contract.code());
        problem.setProperty("traceId", traceId);
        problem.setProperty("fieldErrors", Map.of());

        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(TRACE_HEADER, traceId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        json.writeValue(response.getOutputStream(), problem);
    }

    private static ProblemContract contract(HttpStatus status, String code) {
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(code, "code is required");
        ProblemContract contract = switch (code) {
            case "AUTHENTICATION_REQUIRED" -> new ProblemContract(
                    code, HttpStatus.UNAUTHORIZED, "Authentication required");
            case "CSRF_INVALID" -> new ProblemContract(
                    code, HttpStatus.FORBIDDEN, "CSRF validation failed");
            case "ACCESS_DENIED" -> new ProblemContract(
                    code, HttpStatus.FORBIDDEN, "Access denied");
            case "RATE_LIMITED" -> new ProblemContract(
                    code, HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
            case "INTERNAL_ERROR" -> new ProblemContract(
                    code, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
            default -> throw new IllegalArgumentException("unsupported security problem code");
        };
        if (contract.status() != status) {
            throw new IllegalArgumentException("security problem status does not match its code");
        }
        return contract;
    }

    private record ProblemContract(String code, HttpStatus status, String detail) {
    }
}
