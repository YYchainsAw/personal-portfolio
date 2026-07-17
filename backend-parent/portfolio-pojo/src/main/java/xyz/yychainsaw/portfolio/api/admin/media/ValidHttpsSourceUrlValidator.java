package xyz.yychainsaw.portfolio.api.admin.media;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class ValidHttpsSourceUrlValidator
        implements ConstraintValidator<ValidHttpsSourceUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        try {
            StrictHttpsSourceUrl.requireValidNullable(value);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
