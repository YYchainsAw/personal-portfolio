package xyz.yychainsaw.portfolio.message.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import jakarta.validation.Validator;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.yychainsaw.portfolio.message.application.ContactFingerprintService;
import xyz.yychainsaw.portfolio.message.application.ContactMessageService;
import xyz.yychainsaw.portfolio.message.persistence.ContactMessageMapper;
import xyz.yychainsaw.portfolio.message.persistence.EmailOutboxMapper;
import xyz.yychainsaw.portfolio.message.web.ContactRateLimitSubjectHasher;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;

class ContactPropertiesTest {
    private static final String SECRET =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void validConfigurationIsCanonicalDefensiveAndRedacted() {
        ContactProperties properties = new ContactProperties(
                SECRET, "Owner@EXAMPLE.COM", "MAIL.EXAMPLE.COM");

        byte[] first = properties.dedupeSecret();
        first[0] = (byte) 0xff;

        assertThat(properties.dedupeSecret()[0]).isZero();
        assertThat(properties.ownerEmail()).isEqualTo("Owner@example.com");
        assertThat(properties.mailIdDomain()).isEqualTo("mail.example.com");
        assertThat(properties.toString())
                .contains("<redacted>")
                .doesNotContain(SECRET, "Owner@example.com");
    }

    @Test
    void invalidSecretsAreRejectedWithoutEchoingTheirValues() {
        for (String invalid : new String[] {
            "not-base64", "c2hvcnQ=", SECRET.substring(0, SECRET.length() - 1)
        }) {
            assertThatThrownBy(() -> new ContactProperties(
                            invalid, "owner@example.com", "mail.example.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid contact dedupe secret")
                    .hasMessageNotContaining(invalid);
        }
    }

    @Test
    void unsafeOwnerAndMessageIdDomainsAreRejected() {
        assertThatThrownBy(() -> new ContactProperties(
                        SECRET,
                        "owner@example.com\r\nBcc:attacker@example.com",
                        "mail.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContactProperties(
                        SECRET, "owner@example.com", "single-label"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContactProperties(
                        SECRET, "owner@example.com", "a".repeat(199) + ".com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void webComponentsFailClosedWhenRequiredSecretsOrRecipientAreBlank() {
        ContactProperties missingSecret =
                new ContactProperties("", "owner@example.com", "mail.example.com");
        assertThatThrownBy(() -> new ContactFingerprintService(missingSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("contact dedupe secret is not configured");

        ContactProperties missingOwner =
                new ContactProperties(SECRET, "", "mail.example.com");
        ContactFingerprintService fingerprints =
                new ContactFingerprintService(missingOwner);
        assertThatThrownBy(() -> new ContactMessageService(
                        mock(RateLimiter.class),
                        mock(Validator.class),
                        fingerprints,
                        mock(ContactMessageMapper.class),
                        mock(EmailOutboxMapper.class),
                        missingOwner,
                        mock(PlatformTransactionManager.class),
                        Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("contact owner email is not configured");

        ContactProperties missingDomain =
                new ContactProperties(SECRET, "owner@example.com", "");
        ContactFingerprintService domainFingerprints =
                new ContactFingerprintService(missingDomain);
        assertThatThrownBy(() -> new ContactMessageService(
                        mock(RateLimiter.class),
                        mock(Validator.class),
                        domainFingerprints,
                        mock(ContactMessageMapper.class),
                        mock(EmailOutboxMapper.class),
                        missingDomain,
                        mock(PlatformTransactionManager.class),
                        Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("contact mail ID domain is not configured");
    }

    @Test
    void emptyContactConfigurationDoesNotCreateWebOnlyBeansInNonWebContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(NonWebContactConfiguration.class)
                .withPropertyValues(
                        "portfolio.contact.dedupe-secret=",
                        "portfolio.contact.owner-email=",
                        "portfolio.contact.mail-id-domain=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ContactProperties.class);
                    assertThat(context).doesNotHaveBean(ContactFingerprintService.class);
                    assertThat(context).doesNotHaveBean(ContactRateLimitSubjectHasher.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContactProperties.class)
    @Import({ContactFingerprintService.class, ContactRateLimitSubjectHasher.class})
    static class NonWebContactConfiguration {
    }
}
