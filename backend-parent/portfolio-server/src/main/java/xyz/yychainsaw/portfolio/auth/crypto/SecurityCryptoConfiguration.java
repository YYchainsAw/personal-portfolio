package xyz.yychainsaw.portfolio.auth.crypto;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.TimeProvider;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class SecurityCryptoConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    SecretGenerator totpSecretGenerator() {
        return new DefaultSecretGenerator();
    }

    @Bean
    CodeGenerator totpCodeGenerator() {
        return new AsciiDefaultCodeGenerator();
    }

    @Bean
    TimeProvider totpTimeProvider(Clock clock) {
        return () -> clock.instant().getEpochSecond();
    }

    private static final class AsciiDefaultCodeGenerator extends DefaultCodeGenerator {
        @Override
        public String generate(String secret, long counter) throws CodeGenerationException {
            String generated = super.generate(secret, counter);
            if (generated == null || generated.codePointCount(0, generated.length()) != 6) {
                throw invalidGeneratedCode();
            }

            char[] ascii = new char[6];
            int sourceIndex = 0;
            for (int targetIndex = 0; targetIndex < ascii.length; targetIndex++) {
                int codePoint = generated.codePointAt(sourceIndex);
                if (Character.getType(codePoint) != Character.DECIMAL_DIGIT_NUMBER) {
                    throw invalidGeneratedCode();
                }
                int digit = Character.digit(codePoint, 10);
                if (digit < 0) {
                    throw invalidGeneratedCode();
                }
                ascii[targetIndex] = (char) ('0' + digit);
                sourceIndex += Character.charCount(codePoint);
            }
            return new String(ascii);
        }

        private static CodeGenerationException invalidGeneratedCode() {
            return new CodeGenerationException("TOTP code format is invalid", null);
        }
    }
}
