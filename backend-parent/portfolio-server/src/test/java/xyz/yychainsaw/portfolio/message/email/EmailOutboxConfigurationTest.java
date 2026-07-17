package xyz.yychainsaw.portfolio.message.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import xyz.yychainsaw.portfolio.message.config.ContactProperties;
import xyz.yychainsaw.portfolio.message.config.EmailOutboxProperties;

class EmailOutboxConfigurationTest {
    private static final String CONTACT_SECRET =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String[] ENABLED_PROPERTIES = {
        "portfolio.email.enabled=true",
        "portfolio.email.from=notify@example.com",
        "portfolio.email.poll-interval=10s",
        "portfolio.email.lease-duration=2m",
        "portfolio.email.batch-size=10",
        "spring.mail.protocol=smtp",
        "spring.mail.host=smtp.example.com",
        "spring.mail.port=587",
        "spring.mail.username=smtp-user",
        "spring.mail.password=smtp-password",
        "spring.mail.properties.mail.smtp.auth=true",
        "spring.mail.properties.mail.smtp.starttls.enable=true",
        "spring.mail.properties.mail.smtp.starttls.required=true",
        "spring.mail.properties.mail.smtp.ssl.checkserveridentity=true",
        "spring.mail.properties.mail.smtp.connectiontimeout=10000",
        "spring.mail.properties.mail.smtp.timeout=10000",
        "spring.mail.properties.mail.smtp.writetimeout=10000"
    };

    @Test
    void disabledDeliveryStartsWithoutAddressesCredentialsOrWorkerBeans() {
        runner().run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SmtpEmailSender.class);
                    assertThat(context).doesNotHaveBean(EmailOutboxWorker.class);
                    assertThat(context).doesNotHaveBean(EmailOutboxWorker.SCHEDULER_BEAN);
                    assertThat(context.getBean(EmailOutboxProperties.class))
                            .satisfies(properties -> {
                                assertThat(properties.enabled()).isFalse();
                                assertThat(properties.from()).isEmpty();
                                assertThat(properties.pollInterval())
                                        .isEqualTo(Duration.ofSeconds(10));
                                assertThat(properties.leaseDuration())
                                        .isEqualTo(Duration.ofMinutes(2));
                                assertThat(properties.batchSize()).isEqualTo(10);
                            });
                });
    }

    @Test
    void enabledDeliveryCreatesSenderWorkerAndIndependentScheduler() {
        runner().withPropertyValues(ENABLED_PROPERTIES).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SmtpEmailSender.class);
            assertThat(context).hasSingleBean(EmailOutboxWorker.class);
            assertThat(context).hasBean(EmailOutboxWorker.SCHEDULER_BEAN);
            assertThat(context.getBean(EmailOutboxWorker.SCHEDULER_BEAN))
                    .isInstanceOf(ThreadPoolTaskScheduler.class);
        });
    }

    @Test
    void enabledDeliveryFailsClosedWhenSenderAddressIsBlank() {
        runner().withPropertyValues(replace(
                        ENABLED_PROPERTIES,
                        "portfolio.email.from=notify@example.com",
                        "portfolio.email.from="))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledDeliveryFailsClosedWhenSmtpHostIsBlank() {
        runner().withPropertyValues(replace(
                        ENABLED_PROPERTIES,
                        "spring.mail.host=smtp.example.com",
                        "spring.mail.host="))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledDeliveryFailsClosedWhenStartTlsIsDisabled() {
        runner().withPropertyValues(replace(
                        ENABLED_PROPERTIES,
                        "spring.mail.properties.mail.smtp.starttls.required=true",
                        "spring.mail.properties.mail.smtp.starttls.required=false"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledDeliveryRejectsTrustAllTlsAndLeaseShorterThanTransportBudget() {
        runner().withPropertyValues(append(
                        ENABLED_PROPERTIES,
                        "spring.mail.properties.mail.smtp.ssl.trust=*"))
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues(replace(
                        ENABLED_PROPERTIES,
                        "portfolio.email.lease-duration=2m",
                        "portfolio.email.lease-duration=30s"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledDeliveryRejectsAlternateSessionAndSocketTrustOverrides() {
        runner().withPropertyValues(append(
                        ENABLED_PROPERTIES,
                        "spring.mail.jndi-name=java:comp/env/mail/Unsafe"))
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues(append(
                        ENABLED_PROPERTIES,
                        "spring.mail.ssl.enabled=true"))
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues(append(
                        ENABLED_PROPERTIES,
                        "spring.mail.ssl.bundle=unsafe-bundle"))
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues(append(
                        ENABLED_PROPERTIES,
                        "spring.mail.properties.mail.smtp.ssl.trust=smtp.example.com"))
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues(append(
                        ENABLED_PROPERTIES,
                        "spring.mail.properties.mail.smtp.socketFactory.class=example.UnsafeFactory"))
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues(append(
                        ENABLED_PROPERTIES,
                        "spring.mail.properties.mail.debug.auth=true"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void typedPropertiesRejectUnsafeBoundsAndRedactTheSender() {
        assertThat(new EmailOutboxProperties(
                        true,
                        "notify@example.com",
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(2),
                        10)
                .toString())
                .contains("<redacted>")
                .doesNotContain("notify@example.com");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new EmailOutboxProperties(
                                true,
                                "notify@example.com\r\nBcc: attacker@example.com",
                                Duration.ofSeconds(10),
                                Duration.ofMinutes(2),
                                10))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new EmailOutboxProperties(
                                false, "", Duration.ZERO, Duration.ofMinutes(2), 10))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new EmailOutboxProperties(
                                false, "", Duration.ofSeconds(10), Duration.ZERO, 10))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new EmailOutboxProperties(
                                false, "", Duration.ofSeconds(10), Duration.ofMinutes(2), 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(EmailTestConfiguration.class);
    }

    private static String[] replace(
            String[] source, String existing, String replacement) {
        String[] copy = source.clone();
        for (int index = 0; index < copy.length; index++) {
            if (copy[index].equals(existing)) {
                copy[index] = replacement;
                return copy;
            }
        }
        throw new IllegalArgumentException("test property was not found");
    }

    private static String[] append(String[] source, String extra) {
        String[] copy = java.util.Arrays.copyOf(source, source.length + 1);
        copy[source.length] = extra;
        return copy;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EmailOutboxProperties.class)
    @Import({
        SmtpEmailSender.class,
        EmailOutboxWorker.class,
        EmailOutboxSchedulingConfiguration.class
    })
    static class EmailTestConfiguration {
        @Bean
        JavaMailSender javaMailSender() {
            return org.mockito.Mockito.mock(JavaMailSender.class);
        }

        @Bean
        @ConfigurationProperties("spring.mail")
        MailProperties mailProperties() {
            return new MailProperties();
        }

        @Bean
        ContactProperties contactProperties() {
            return new ContactProperties(
                    CONTACT_SECRET, "owner@example.com", "yychainsaw.xyz");
        }

        @Bean
        EmailOutboxRepository emailOutboxRepository() {
            return org.mockito.Mockito.mock(EmailOutboxRepository.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
