package xyz.yychainsaw.portfolio.common.error;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import xyz.yychainsaw.portfolio.common.trace.TraceIds;

@RestControllerAdvice
public final class GlobalProblemHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalProblemHandler.class);
    private static final String TRACE_HEADER = "X-Trace-Id";

    @ExceptionHandler(DomainException.class)
    protected ResponseEntity<Object> handleDomainException(
            DomainException exception, WebRequest request) {
        return handleProblem(
                exception,
                new HttpHeaders(),
                exception.status(),
                exception.code(),
                "Request could not be processed",
                exception.fieldErrors(),
                request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            String message = error.getDefaultMessage();
            fieldErrors.putIfAbsent(error.getField(), message == null ? "invalid" : message);
        }
        return handleProblem(
                exception,
                headers,
                HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDATION_ERROR",
                "Request validation failed",
                fieldErrors,
                request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return handleProblem(
                exception,
                headers,
                status,
                "MALFORMED_REQUEST",
                "Malformed request",
                Map.of(),
                request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemContract contract = contractFor(status);
        return handleProblem(
                exception,
                headers,
                status,
                contract.code(),
                contract.detail(),
                Map.of(),
                request);
    }

    @ExceptionHandler(Exception.class)
    protected ResponseEntity<Object> handleUnexpectedException(
            Exception exception, WebRequest request) {
        String traceId = TraceIds.current();
        log.error(
                "Unhandled request failure traceId={} type={}",
                traceId,
                exception.getClass().getName());
        return handleProblem(
                exception,
                new HttpHeaders(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal server error",
                Map.of(),
                request,
                traceId);
    }

    private ResponseEntity<Object> handleProblem(
            Exception exception,
            HttpHeaders headers,
            HttpStatusCode status,
            String code,
            String detail,
            Map<String, String> fieldErrors,
            WebRequest request) {
        return handleProblem(
                exception,
                headers,
                status,
                code,
                detail,
                fieldErrors,
                request,
                TraceIds.current());
    }

    private ResponseEntity<Object> handleProblem(
            Exception exception,
            HttpHeaders headers,
            HttpStatusCode status,
            String code,
            String detail,
            Map<String, String> fieldErrors,
            WebRequest request,
            String traceId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(detail);
        problem.setType(URI.create("urn:portfolio:problem:" + code.toLowerCase(Locale.ROOT)));
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId);
        problem.setProperty("fieldErrors", Map.copyOf(fieldErrors));

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.set(TRACE_HEADER, traceId);
        return super.handleExceptionInternal(exception, problem, responseHeaders, status, request);
    }

    private static ProblemContract contractFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> new ProblemContract("BAD_REQUEST", "Bad request");
            case 401 -> new ProblemContract("UNAUTHORIZED", "Unauthorized");
            case 403 -> new ProblemContract("FORBIDDEN", "Forbidden");
            case 404 -> new ProblemContract("NOT_FOUND", "Resource not found");
            case 405 -> new ProblemContract("METHOD_NOT_ALLOWED", "Method not allowed");
            case 406 -> new ProblemContract("NOT_ACCEPTABLE", "Not acceptable");
            case 409 -> new ProblemContract("CONFLICT", "Request conflict");
            case 413 -> new ProblemContract("PAYLOAD_TOO_LARGE", "Payload too large");
            case 415 -> new ProblemContract("UNSUPPORTED_MEDIA_TYPE", "Unsupported media type");
            case 422 -> new ProblemContract("UNPROCESSABLE_ENTITY", "Request could not be processed");
            case 429 -> new ProblemContract("TOO_MANY_REQUESTS", "Too many requests");
            default -> status.is4xxClientError()
                    ? new ProblemContract("CLIENT_ERROR", "Request could not be completed")
                    : new ProblemContract("INTERNAL_ERROR", "Internal server error");
        };
    }

    private record ProblemContract(String code, String detail) {
    }
}
