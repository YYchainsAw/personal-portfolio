package xyz.yychainsaw.portfolio.auth.crypto;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Component
public final class PasswordPolicy {
    private static final String MESSAGE =
            "密码须为 14–128 位，并包含大小写字母、数字和符号";

    public void requireStrong(CharSequence password) {
        if (password == null) {
            reject();
        }

        int codePointCount = 0;
        boolean hasUppercase = false;
        boolean hasLowercase = false;
        boolean hasDecimalDigit = false;
        boolean hasPunctuationOrSymbol = false;
        boolean validUtf16 = true;

        for (int index = 0; index < password.length(); ) {
            char first = password.charAt(index);
            int codePoint;
            if (Character.isHighSurrogate(first)) {
                if (index + 1 >= password.length()
                        || !Character.isLowSurrogate(password.charAt(index + 1))) {
                    validUtf16 = false;
                    break;
                }
                codePoint = Character.toCodePoint(first, password.charAt(index + 1));
                index += 2;
            } else if (Character.isLowSurrogate(first)) {
                validUtf16 = false;
                break;
            } else {
                codePoint = first;
                index++;
            }

            codePointCount++;
            hasUppercase |= Character.isUpperCase(codePoint);
            hasLowercase |= Character.isLowerCase(codePoint);
            hasDecimalDigit |= Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER;
            hasPunctuationOrSymbol |= isPunctuationOrSymbol(codePoint);
        }

        if (!validUtf16
                || codePointCount < 14
                || codePointCount > 128
                || !hasUppercase
                || !hasLowercase
                || !hasDecimalDigit
                || !hasPunctuationOrSymbol) {
            reject();
        }
    }

    private static boolean isPunctuationOrSymbol(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION,
                    Character.MATH_SYMBOL,
                    Character.CURRENCY_SYMBOL,
                    Character.MODIFIER_SYMBOL,
                    Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    private static void reject() {
        throw new DomainException(
                "PASSWORD_POLICY_VIOLATION",
                HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("password", MESSAGE));
    }
}
