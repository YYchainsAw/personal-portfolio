package xyz.yychainsaw.portfolio.api.admin.media;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = ValidHttpsSourceUrlValidator.class)
@Target({
        ElementType.ANNOTATION_TYPE,
        ElementType.FIELD,
        ElementType.METHOD,
        ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT,
        ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidHttpsSourceUrl {
    String message() default "must be an HTTPS URL";

    Class<?>[] groups() default { };

    Class<? extends Payload>[] payload() default { };
}
