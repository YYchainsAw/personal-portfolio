package xyz.yychainsaw.portfolio.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.common.trace.TraceIdFilter;
import xyz.yychainsaw.portfolio.common.trace.TraceIds;

class ProblemHandlingTest {
    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String FORGED_TRACE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        MDC.clear();
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalProblemHandler())
                .addFilters(new TraceIdFilter())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void traceContextIsClearAfterEveryTest() {
        try {
            assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
        } finally {
            validator.close();
            MDC.clear();
        }
    }

    @Test
    void domainFailureReturnsItsStable422ContractAndServerGeneratedTrace() throws Exception {
        MvcResult result = expectProblem(
                get("/test/domain"),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "TRANSLATION_INCOMPLETE",
                "Request could not be processed");

        assertThat(json(result).path("fieldErrors").path("translations.en.summary").asText())
                .isEqualTo("required");
    }

    @Test
    void beanValidationFailureReturns422AndFieldErrors() throws Exception {
        MvcResult result = expectProblem(
                post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDATION_ERROR",
                "Request validation failed");

        assertThat(json(result).path("fieldErrors").path("name").asText()).isEqualTo("required");
    }

    @Test
    void containerElementValidationFailureAlsoReturnsTheStable422Contract()
            throws Exception {
        MvcResult result = expectProblem(
                post("/test/validated-list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"name\":\"\"}]"),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDATION_ERROR",
                "Request validation failed");

        assertThat(json(result).path("fieldErrors").toString()).contains("required");
    }

    @Test
    void malformedJsonReturnsAStable400InsteadOfAnInternalError() throws Exception {
        expectProblem(
                post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"),
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Malformed request");
    }

    @Test
    void missingRequiredRequestParameterRemainsAFramework400() throws Exception {
        expectProblem(
                get("/test/required"),
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                "Bad request");
    }

    @Test
    void unsupportedMethodRemains405AndPreservesTheAllowHeader() throws Exception {
        MvcResult result = expectProblem(
                post("/test/get-only"),
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "Method not allowed");

        assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW)).contains("GET");
    }

    @Test
    void unexpectedFailureReturnsARedacted500() throws Exception {
        MvcResult result = expectProblem(
                get("/test/internal"),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal server error");

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .doesNotContain("secret/database/path")
                .doesNotContain("hunter2")
                .doesNotContain("IllegalStateException");
    }

    private MvcResult expectProblem(
            MockHttpServletRequestBuilder request,
            HttpStatus status,
            String code,
            String safeDetail) throws Exception {
        MvcResult result = mvc.perform(request.header(TRACE_HEADER, FORGED_TRACE_ID))
                .andExpect(status().is(status.value()))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists(TRACE_HEADER))
                .andExpect(jsonPath("$.type")
                        .value("urn:portfolio:problem:" + code.toLowerCase(Locale.ROOT)))
                .andExpect(jsonPath("$.title").value(safeDetail))
                .andExpect(jsonPath("$.status").value(status.value()))
                .andExpect(jsonPath("$.detail").value(safeDetail))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andReturn();

        assertTraceContract(result);
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
        return result;
    }

    private static void assertTraceContract(MvcResult result) throws Exception {
        String responseTraceId = result.getResponse().getHeader(TRACE_HEADER);
        String bodyTraceId = json(result).path("traceId").asText();

        assertThat(responseTraceId)
                .isNotEqualTo(FORGED_TRACE_ID)
                .matches("[0-9a-f]{32}");
        assertThat(bodyTraceId).isEqualTo(responseTraceId);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    @RestController
    static final class FailureController {
        @GetMapping("/test/domain")
        void domain() {
            throw new DomainException(
                    "TRANSLATION_INCOMPLETE",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("translations.en.summary", "required"));
        }

        @PostMapping("/test/validated")
        void validated(@Valid @RequestBody ValidationRequest request) {
        }

        @PostMapping("/test/validated-list")
        void validatedList(@Valid @RequestBody List<ValidationRequest> request) {
        }

        @GetMapping("/test/required")
        void required(@RequestParam("value") String value) {
        }

        @GetMapping("/test/get-only")
        void getOnly() {
        }

        @GetMapping("/test/internal")
        void internal() {
            throw new IllegalStateException("secret/database/path?password=hunter2");
        }
    }

    private record ValidationRequest(@NotBlank(message = "required") String name) {
    }
}
