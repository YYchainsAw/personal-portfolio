package xyz.yychainsaw.portfolio.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DomainExceptionTest {
    @Test
    void copiesFieldErrorsAndExposesTheStableContract() {
        Map<String, String> source = new HashMap<>();
        source.put("translations.en.summary", "required");

        DomainException exception = new DomainException(
                "PROJECT_TRANSLATION_INCOMPLETE", HttpStatus.UNPROCESSABLE_ENTITY, source);
        source.clear();

        assertThat(exception.getMessage()).isEqualTo("PROJECT_TRANSLATION_INCOMPLETE");
        assertThat(exception.code()).isEqualTo("PROJECT_TRANSLATION_INCOMPLETE");
        assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(exception.fieldErrors())
                .containsExactly(Map.entry("translations.en.summary", "required"));
        assertThatThrownBy(() -> exception.fieldErrors().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullContractArgumentsWithTheirParameterNames() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DomainException(null, HttpStatus.BAD_REQUEST, Map.of()))
                .withMessage("code");
        assertThatNullPointerException()
                .isThrownBy(() -> new DomainException("CODE", null, Map.of()))
                .withMessage("status");
        assertThatNullPointerException()
                .isThrownBy(() -> new DomainException("CODE", HttpStatus.BAD_REQUEST, null))
                .withMessage("fieldErrors");
    }

    @Test
    void rejectsNullFieldNamesAndMessages() {
        Map<String, String> nullField = new HashMap<>();
        nullField.put(null, "required");
        Map<String, String> nullMessage = new HashMap<>();
        nullMessage.put("name", null);

        assertThatNullPointerException()
                .isThrownBy(() -> new DomainException("CODE", HttpStatus.BAD_REQUEST, nullField));
        assertThatNullPointerException()
                .isThrownBy(() -> new DomainException("CODE", HttpStatus.BAD_REQUEST, nullMessage));
    }
}
