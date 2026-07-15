package xyz.yychainsaw.portfolio.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.exceptions.TimeProviderException;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.TimeProvider;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import xyz.yychainsaw.portfolio.common.error.DomainException;

class SecurityCryptoTest {
    private static final byte[] KEY_ONE = sequentialBytes(0);
    private static final byte[] KEY_TWO = sequentialBytes(32);
    private static final String KEY_ONE_BASE64 = java.util.Base64.getEncoder().encodeToString(KEY_ONE);
    private static final String KEY_TWO_BASE64 = java.util.Base64.getEncoder().encodeToString(KEY_TWO);
    private static final String KEY_RING = "1=" + KEY_ONE_BASE64;
    private static final String ROTATION_KEY_RING = KEY_RING + ",2=" + KEY_TWO_BASE64;
    private static final String SEED = "JBSWY3DPEHPK3PXP";
    private static final long EPOCH_SECOND = 1_784_048_400L;
    private static final long CURRENT_COUNTER = EPOCH_SECOND / 30;
    private static final String POLICY_MESSAGE =
            "密码须为 14–128 位，并包含大小写字母、数字和符号";

    @Test
    void totpPropertiesValidateTrimAndRedactSecrets() {
        TotpProperties properties = new TotpProperties(
                1, "  " + KEY_RING + "  ", "  易嘉轩 Portfolio  ", Duration.ofMinutes(5), 5);

        assertThat(properties.activeKeyVersion()).isEqualTo(1);
        assertThat(properties.keyRing()).isEqualTo(KEY_RING);
        assertThat(properties.issuer()).isEqualTo("易嘉轩 Portfolio");
        assertThat(properties.toString())
                .contains("keyRing=<redacted>")
                .doesNotContain(KEY_ONE_BASE64);

        assertInvalidProperties(0, KEY_RING, "issuer", Duration.ofMinutes(5), 5);
        assertInvalidProperties(1, null, "issuer", Duration.ofMinutes(5), 5);
        assertInvalidProperties(1, " ", "issuer", Duration.ofMinutes(5), 5);
        assertInvalidProperties(1, KEY_RING, null, Duration.ofMinutes(5), 5);
        assertInvalidProperties(1, KEY_RING, " ", Duration.ofMinutes(5), 5);
        assertInvalidProperties(1, KEY_RING, "issuer", null, 5);
        assertInvalidProperties(1, KEY_RING, "issuer", Duration.ZERO, 5);
        assertInvalidProperties(1, KEY_RING, "issuer", Duration.ofSeconds(-1), 5);
        assertInvalidProperties(1, KEY_RING, "issuer", Duration.ofMinutes(5), 0);
    }

    @Test
    void encryptedTotpSecretIsSerializableDefensivelyCopiedAndHasArrayValueSemantics() {
        byte[] nonce = bytes(12, (byte) 7);
        byte[] ciphertext = bytes(17, (byte) 11);
        EncryptedTotpSecret first = new EncryptedTotpSecret(1, nonce, ciphertext);
        EncryptedTotpSecret equal = new EncryptedTotpSecret(1, nonce.clone(), ciphertext.clone());

        nonce[0] = 99;
        ciphertext[0] = 99;
        byte[] exposedNonce = first.nonce();
        byte[] exposedCiphertext = first.ciphertext();
        exposedNonce[1] = 88;
        exposedCiphertext[1] = 88;

        assertThat(first.nonce()).containsOnly((byte) 7);
        assertThat(first.ciphertext()).containsOnly((byte) 11);
        assertThat(first).isEqualTo(equal);
        assertThat(first.hashCode()).isEqualTo(equal.hashCode());
        assertThat(first).isInstanceOf(Serializable.class);
        assertThat(ObjectStreamClass.lookup(EncryptedTotpSecret.class).getSerialVersionUID()).isEqualTo(1L);
        assertThat(first.toString())
                .contains("nonce=<redacted>", "ciphertext=<redacted>")
                .doesNotContain("7", "11");

        assertThatIllegalArgumentException().isThrownBy(() -> new EncryptedTotpSecret(0, nonce, ciphertext));
        assertThatIllegalArgumentException().isThrownBy(() -> new EncryptedTotpSecret(1, null, ciphertext));
        assertThatIllegalArgumentException().isThrownBy(() -> new EncryptedTotpSecret(1, new byte[11], ciphertext));
        assertThatIllegalArgumentException().isThrownBy(() -> new EncryptedTotpSecret(1, nonce, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new EncryptedTotpSecret(1, nonce, new byte[16]));
    }

    @Test
    void keyRingParsingIsStrictAndNeverLeaksSourceEntries() {
        List<String> invalidRings = List.of(
                KEY_RING + ",",
                KEY_RING + ",,2=" + KEY_TWO_BASE64,
                "x=" + KEY_ONE_BASE64,
                "0=" + KEY_ONE_BASE64,
                "-1=" + KEY_ONE_BASE64,
                "1=" + KEY_ONE_BASE64 + ",1=" + KEY_TWO_BASE64,
                "1=" + KEY_ONE_BASE64 + ",2=" + KEY_ONE_BASE64,
                "1=not-valid-base64!",
                "1=" + java.util.Base64.getEncoder().encodeToString(new byte[31]));

        for (String invalidRing : invalidRings) {
            IllegalArgumentException exception = catchThrowableOfType(
                    IllegalArgumentException.class,
                    () -> new TotpEnvelopeCrypto(properties(1, invalidRing)));
            assertThat(exception).isNotNull();
            assertThat(exception.getMessage())
                    .isEqualTo("invalid TOTP key ring")
                    .doesNotContain(invalidRing, KEY_ONE_BASE64, KEY_TWO_BASE64);
        }

        assertThatThrownBy(() -> new TotpEnvelopeCrypto(properties(2, KEY_RING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("active TOTP key version is absent from key ring");
    }

    @Test
    void aesGcmEnvelopeUsesFreshNoncesAdminAndVersionBoundAadAndRejectsTampering() {
        TotpEnvelopeCrypto crypto = new TotpEnvelopeCrypto(properties(1, ROTATION_KEY_RING));
        UUID adminId = UUID.randomUUID();
        EncryptedTotpSecret first = crypto.encrypt(adminId, SEED);
        EncryptedTotpSecret second = crypto.encrypt(adminId, SEED);

        assertThat(crypto.activeKeyVersion()).isEqualTo(1);
        assertThat(crypto.decrypt(adminId, first)).isEqualTo(SEED);
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertAuthenticationFailure(() -> crypto.decrypt(UUID.randomUUID(), first));

        byte[] changedNonce = first.nonce();
        changedNonce[0] ^= 1;
        assertAuthenticationFailure(() -> crypto.decrypt(
                adminId, new EncryptedTotpSecret(1, changedNonce, first.ciphertext())));

        byte[] changedCiphertext = first.ciphertext();
        changedCiphertext[0] ^= 1;
        assertAuthenticationFailure(() -> crypto.decrypt(
                adminId, new EncryptedTotpSecret(1, first.nonce(), changedCiphertext)));

        assertAuthenticationFailure(() -> crypto.decrypt(
                adminId, new EncryptedTotpSecret(2, first.nonce(), first.ciphertext())));
        assertThatThrownBy(() -> crypto.decrypt(
                        adminId, new EncryptedTotpSecret(9, first.nonce(), first.ciphertext())))
                .isInstanceOf(SecurityException.class)
                .hasMessage("TOTP ciphertext authentication failed");

        assertThatIllegalArgumentException().isThrownBy(() -> crypto.encrypt(null, SEED));
        assertThatIllegalArgumentException().isThrownBy(() -> crypto.encrypt(adminId, null));
        assertThatIllegalArgumentException().isThrownBy(() -> crypto.encrypt(adminId, " "));
        assertThatIllegalArgumentException().isThrownBy(() -> crypto.decrypt(null, first));
        assertThatIllegalArgumentException().isThrownBy(() -> crypto.decrypt(adminId, null));
        assertThatIllegalArgumentException().isThrownBy(() -> crypto.reencrypt(null, first));
        assertThatIllegalArgumentException().isThrownBy(() -> crypto.reencrypt(adminId, null));
    }

    @Test
    void decryptRejectsMalformedUtf8InsteadOfReplacingIt() throws Exception {
        UUID adminId = UUID.randomUUID();
        TotpEnvelopeCrypto crypto = new TotpEnvelopeCrypto(properties(1, KEY_RING));
        EncryptedTotpSecret malformed = encryptRaw(adminId, 1, KEY_ONE, new byte[] {(byte) 0xc3, 0x28});

        assertThatThrownBy(() -> crypto.decrypt(adminId, malformed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TOTP secret decoding failed");
    }

    @Test
    void reencryptAuthenticatesOldEnvelopeRotatesToActiveKeyAndNeverReturnsOnFailure() {
        UUID adminId = UUID.randomUUID();
        TotpEnvelopeCrypto oldCrypto = new TotpEnvelopeCrypto(properties(1, ROTATION_KEY_RING));
        EncryptedTotpSecret oldEnvelope = oldCrypto.encrypt(adminId, SEED);
        TotpEnvelopeCrypto rotatingCrypto = new TotpEnvelopeCrypto(properties(2, ROTATION_KEY_RING));

        EncryptedTotpSecret rotated = rotatingCrypto.reencrypt(adminId, oldEnvelope);

        assertThat(rotated.keyVersion()).isEqualTo(2);
        assertThat(rotated.nonce()).isNotEqualTo(oldEnvelope.nonce());
        assertThat(rotated.ciphertext()).isNotEqualTo(oldEnvelope.ciphertext());
        assertThat(rotatingCrypto.decrypt(adminId, rotated)).isEqualTo(SEED);
        TotpEnvelopeCrypto newKeyOnly = new TotpEnvelopeCrypto(properties(2, "2=" + KEY_TWO_BASE64));
        assertThat(newKeyOnly.decrypt(adminId, rotated)).isEqualTo(SEED);
        EncryptedTotpSecret refreshed = rotatingCrypto.reencrypt(adminId, rotated);
        assertThat(refreshed.keyVersion()).isEqualTo(2);
        assertThat(refreshed.nonce()).isNotEqualTo(rotated.nonce());
        assertThat(refreshed.ciphertext()).isNotEqualTo(rotated.ciphertext());
        assertThat(newKeyOnly.decrypt(adminId, refreshed)).isEqualTo(SEED);

        EncryptedTotpSecret unknown =
                new EncryptedTotpSecret(9, oldEnvelope.nonce(), oldEnvelope.ciphertext());
        assertAuthenticationFailure(() -> rotatingCrypto.reencrypt(adminId, unknown));
        byte[] tampered = oldEnvelope.ciphertext();
        tampered[tampered.length - 1] ^= 1;
        assertAuthenticationFailure(() -> rotatingCrypto.reencrypt(
                adminId, new EncryptedTotpSecret(1, oldEnvelope.nonce(), tampered)));
    }

    @Test
    void totpVerificationAcceptsExactlyThreeWindowsAndAlwaysComparesAllThree() {
        RecordingCodeGenerator generator = new RecordingCodeGenerator();
        TimeProvider time = () -> EPOCH_SECOND;
        TotpService service = service(generator, time, () -> SEED);

        for (long offset : List.of(-1L, 0L, 1L)) {
            generator.clear();
            assertThat(service.verifyEnrollment(SEED, generator.valueAt(CURRENT_COUNTER + offset))).isTrue();
            assertThat(generator.counters())
                    .containsExactly(CURRENT_COUNTER - 1, CURRENT_COUNTER, CURRENT_COUNTER + 1);
        }

        generator.clear();
        assertThat(service.verifyEnrollment(SEED, generator.valueAt(CURRENT_COUNTER - 2))).isFalse();
        assertThat(generator.counters())
                .containsExactly(CURRENT_COUNTER - 1, CURRENT_COUNTER, CURRENT_COUNTER + 1);
        generator.clear();
        assertThat(service.verifyEnrollment(SEED, generator.valueAt(CURRENT_COUNTER + 2))).isFalse();
        assertThat(generator.counters())
                .containsExactly(CURRENT_COUNTER - 1, CURRENT_COUNTER, CURRENT_COUNTER + 1);

        UUID adminId = UUID.randomUUID();
        EncryptedTotpSecret encrypted = new TotpEnvelopeCrypto(properties(1, KEY_RING)).encrypt(adminId, SEED);
        for (long offset : List.of(-1L, 0L, 1L)) {
            generator.clear();
            assertThat(service.verify(adminId, encrypted, generator.valueAt(CURRENT_COUNTER + offset))).isTrue();
            assertThat(generator.counters())
                    .containsExactly(CURRENT_COUNTER - 1, CURRENT_COUNTER, CURRENT_COUNTER + 1);
        }
        for (long offset : List.of(-2L, 2L)) {
            generator.clear();
            assertThat(service.verify(adminId, encrypted, generator.valueAt(CURRENT_COUNTER + offset))).isFalse();
            assertThat(generator.counters())
                    .containsExactly(CURRENT_COUNTER - 1, CURRENT_COUNTER, CURRENT_COUNTER + 1);
        }
    }

    @Test
    void malformedTotpCodesShortCircuitBeforeDecryptionTimeAndGeneration() {
        AtomicInteger timeCalls = new AtomicInteger();
        AtomicInteger codeCalls = new AtomicInteger();
        TimeProvider time = () -> {
            timeCalls.incrementAndGet();
            throw new TimeProviderException("must not be exposed");
        };
        CodeGenerator codes = (secret, counter) -> {
            codeCalls.incrementAndGet();
            throw new CodeGenerationException("must not be exposed", null);
        };
        TotpService service = service(codes, time, () -> SEED);
        EncryptedTotpSecret unknownEnvelope = new EncryptedTotpSecret(99, new byte[12], new byte[17]);

        for (String malformed : Arrays.asList(null, "", "12345", "1234567", " 123456", "123456 ", "１２３４５６")) {
            assertThat(service.verifyEnrollment(SEED, malformed)).isFalse();
            assertThat(service.verify(UUID.randomUUID(), unknownEnvelope, malformed)).isFalse();
        }
        assertThat(timeCalls).hasValue(0);
        assertThat(codeCalls).hasValue(0);
    }

    @Test
    void providerFailuresUseFixedExceptionClassifications() {
        String codeProviderSecret = "seed-leak-from-code-provider";
        CodeGenerator brokenCodes = (secret, counter) -> {
            throw new CodeGenerationException(codeProviderSecret, new RuntimeException(codeProviderSecret));
        };
        TotpService codeFailure = service(brokenCodes, () -> EPOCH_SECOND, () -> SEED);
        IllegalStateException codeException = catchThrowableOfType(
                IllegalStateException.class,
                () -> codeFailure.verifyEnrollment(SEED, "123456"));
        assertThat(codeException)
                .hasMessage("TOTP generation failed")
                .hasNoCause();
        assertThat(codeException.toString() + Arrays.toString(codeException.getStackTrace()))
                .doesNotContain(codeProviderSecret);

        String timeProviderSecret = "seed-leak-from-time-provider";
        TimeProvider brokenTime = () -> {
            throw new TimeProviderException(timeProviderSecret, new RuntimeException(timeProviderSecret));
        };
        TotpService timeFailure = service(new RecordingCodeGenerator(), brokenTime, () -> SEED);
        IllegalStateException timeException = catchThrowableOfType(
                IllegalStateException.class,
                () -> timeFailure.verifyEnrollment(SEED, "123456"));
        assertThat(timeException)
                .hasMessage("TOTP time lookup failed")
                .hasNoCause();
        assertThat(timeException.toString() + Arrays.toString(timeException.getStackTrace()))
                .doesNotContain(timeProviderSecret);
    }

    @Test
    void enrollmentValidatesInputsUsesRfc3986EncodingAndRedactsItsStringForm() {
        TotpProperties properties = new TotpProperties(
                1, KEY_RING, " Y Y/工作? ", Duration.ofMinutes(5), 5);
        TotpEnvelopeCrypto crypto = new TotpEnvelopeCrypto(properties);
        SecretGenerator generator = () -> "ABC= 你好";
        TotpService service = new TotpService(
                properties, crypto, generator, new RecordingCodeGenerator(), () -> EPOCH_SECOND);
        UUID adminId = UUID.randomUUID();

        TotpService.Enrollment enrollment = service.beginEnrollment(adminId, "a:b c/易?&");

        assertThat(enrollment.plaintextSecret()).isEqualTo("ABC= 你好");
        assertThat(crypto.decrypt(adminId, enrollment.encryptedSecret())).isEqualTo("ABC= 你好");
        assertThat(enrollment.provisioningUri()).isEqualTo(
                "otpauth://totp/Y%20Y%2F%E5%B7%A5%E4%BD%9C%3F:"
                        + "a%3Ab%20c%2F%E6%98%93%3F%26"
                        + "?secret=ABC%3D%20%E4%BD%A0%E5%A5%BD"
                        + "&issuer=Y%20Y%2F%E5%B7%A5%E4%BD%9C%3F"
                        + "&algorithm=SHA1&digits=6&period=30");
        assertThat(URI.create(enrollment.provisioningUri()).getScheme()).isEqualTo("otpauth");
        assertThat(enrollment.toString())
                .isEqualTo("Enrollment[plaintextSecret=<redacted>, encryptedSecret=<redacted>, provisioningUri=<redacted>]")
                .doesNotContain("ABC", "otpauth");

        assertThatIllegalArgumentException().isThrownBy(() -> service.beginEnrollment(null, "admin"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.beginEnrollment(adminId, null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.beginEnrollment(adminId, " "));
        assertThatIllegalArgumentException().isThrownBy(() -> service(codes(), () -> EPOCH_SECOND, () -> null)
                .beginEnrollment(adminId, "admin"));
        assertThatIllegalArgumentException().isThrownBy(() -> service(codes(), () -> EPOCH_SECOND, () -> " ")
                .beginEnrollment(adminId, "admin"));
    }

    @Test
    void springConfigurationSuppliesRealArgon2AsciiTotpAndClockBackedBeans() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(EPOCH_SECOND), ZoneOffset.UTC);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Clock.class, () -> clock);
            context.register(SecurityCryptoConfiguration.class);
            context.refresh();

            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
            assertThat(encoder).isInstanceOf(Argon2PasswordEncoder.class);
            String encoded = encoder.encode("Correct-Horse-7!Battery");
            assertThat(encoded).startsWith("$argon2id$v=19$m=16384,t=2,p=1$");
            assertThat(encoder.matches("Correct-Horse-7!Battery", encoded)).isTrue();
            assertThat(encoder.matches("wrong-password", encoded)).isFalse();
            assertThat(context.getBean(SecretGenerator.class)).isInstanceOf(DefaultSecretGenerator.class);

            CodeGenerator codeGenerator = context.getBean(CodeGenerator.class);
            assertThat(codeGenerator).isInstanceOf(DefaultCodeGenerator.class);
            Locale original = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
                assertThat(codeGenerator.generate(SEED, CURRENT_COUNTER)).matches("[0-9]{6}");
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, original);
            }
            assertThat(context.getBean(TimeProvider.class).getTime()).isEqualTo(EPOCH_SECOND);
        }
    }

    @Test
    void passwordPolicyCountsUnicodeCodePointsAndAcceptsInclusiveBoundaries() {
        PasswordPolicy policy = new PasswordPolicy();
        policy.requireStrong("Aa1!" + "a".repeat(10));
        policy.requireStrong("Aa1!" + "a".repeat(124));
        policy.requireStrong(new StringBuilder("Aa1!" + "a".repeat(10)));

        String supplementaryUpper = new String(Character.toChars(0x10400));
        String supplementaryLower = new String(Character.toChars(0x10428));
        String supplementaryPassword = supplementaryUpper + supplementaryLower + "1!" + "a".repeat(10);
        assertThat(supplementaryPassword.codePointCount(0, supplementaryPassword.length())).isEqualTo(14);
        policy.requireStrong(supplementaryPassword);
    }

    @Test
    void passwordPolicyRejectsAdjacentLengthsMissingClassesAndInvalidUtf16() {
        PasswordPolicy policy = new PasswordPolicy();
        List<CharSequence> invalid = List.of(
                "Aa1!" + "a".repeat(9),
                "Aa1!" + "a".repeat(125),
                "aa1!" + "a".repeat(10),
                "AA1!" + "A".repeat(10),
                "Aaa!" + "a".repeat(10),
                "Aa11" + "a".repeat(10),
                "Aa1 " + "a".repeat(10),
                "Aa1\u0001" + "a".repeat(10),
                "Aa1\u0301" + "a".repeat(10),
                "Aa1!" + "a".repeat(9) + "\uD800",
                "Aa1!" + "a".repeat(9) + "\uDC00");

        assertPasswordViolation(policy, null);
        invalid.forEach(password -> assertPasswordViolation(policy, password));
    }

    @Test
    void recoveryCodesAreBoundedUniqueHumanReadableAndImmutable() {
        RecoveryCodeGenerator generator = new RecoveryCodeGenerator();
        List<String> one = generator.generate(1);
        List<String> ten = generator.generate(10);

        assertThat(one).hasSize(1);
        assertThat(ten).hasSize(10);
        assertThat(new HashSet<>(ten)).hasSize(10);
        assertThat(ten).allMatch(code -> code.matches(
                "[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> ten.add("ABCD-EFGH-JKLM"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> generator.generate(0))
                .withMessage("recovery-code count must be between 1 and 10");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> generator.generate(11))
                .withMessage("recovery-code count must be between 1 and 10");
    }

    @Test
    void recoveryCodeCollisionBudgetTerminatesDeterministically() {
        CountingZeroRandom random = new CountingZeroRandom();
        RecoveryCodeGenerator generator = new RecoveryCodeGenerator(random);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThatThrownBy(() -> generator.generate(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("recovery-code generation exhausted collision budget"));
        assertThat(random.calls()).isEqualTo(2 * 32 * 12);
    }

    private static void assertInvalidProperties(
            int activeVersion, String keyRing, String issuer, Duration lifetime, int attempts) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TotpProperties(activeVersion, keyRing, issuer, lifetime, attempts));
    }

    private static void assertAuthenticationFailure(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SecurityException.class)
                .hasMessage("TOTP ciphertext authentication failed");
    }

    private static void assertPasswordViolation(PasswordPolicy policy, CharSequence password) {
        DomainException exception = catchThrowableOfType(
                DomainException.class, () -> policy.requireStrong(password));
        assertThat(exception).isNotNull();
        assertThat(exception.code()).isEqualTo("PASSWORD_POLICY_VIOLATION");
        assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(exception.fieldErrors()).containsExactlyEntriesOf(java.util.Map.of("password", POLICY_MESSAGE));
        assertThat(exception.getMessage()).isEqualTo("PASSWORD_POLICY_VIOLATION");
        if (password != null) {
            assertThat(exception.toString()).doesNotContain(password);
        }
    }

    private static TotpProperties properties(int activeVersion, String keyRing) {
        return new TotpProperties(activeVersion, keyRing, "yychainsaw.xyz", Duration.ofMinutes(5), 5);
    }

    private static TotpService service(CodeGenerator codes, TimeProvider time, SecretGenerator secrets) {
        TotpProperties properties = properties(1, KEY_RING);
        return new TotpService(properties, new TotpEnvelopeCrypto(properties), secrets, codes, time);
    }

    private static CodeGenerator codes() {
        return new RecordingCodeGenerator();
    }

    private static EncryptedTotpSecret encryptRaw(
            UUID adminId, int keyVersion, byte[] key, byte[] plaintext) throws Exception {
        byte[] nonce = bytes(12, (byte) 42);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("portfolio-admin-totp:v1|key=" + keyVersion + "|admin=" + adminId)
                .getBytes(StandardCharsets.UTF_8));
        return new EncryptedTotpSecret(keyVersion, nonce, cipher.doFinal(plaintext));
    }

    private static byte[] sequentialBytes(int start) {
        byte[] value = new byte[32];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (start + i);
        }
        return value;
    }

    private static byte[] bytes(int length, byte value) {
        byte[] result = new byte[length];
        Arrays.fill(result, value);
        return result;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class RecordingCodeGenerator implements CodeGenerator {
        private final List<Long> counters = new ArrayList<>();

        @Override
        public String generate(String secret, long counter) {
            counters.add(counter);
            return valueAt(counter);
        }

        private String valueAt(long counter) {
            return String.format(Locale.ROOT, "%06d", Math.floorMod(counter, 1_000_000));
        }

        private List<Long> counters() {
            return List.copyOf(counters);
        }

        private void clear() {
            counters.clear();
        }
    }

    private static final class CountingZeroRandom extends SecureRandom {
        private static final long serialVersionUID = 1L;
        private int calls;

        @Override
        public int nextInt(int bound) {
            calls++;
            return 0;
        }

        private int calls() {
            return calls;
        }
    }
}
