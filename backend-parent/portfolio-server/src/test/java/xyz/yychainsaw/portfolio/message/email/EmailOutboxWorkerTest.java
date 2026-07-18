package xyz.yychainsaw.portfolio.message.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import xyz.yychainsaw.portfolio.message.config.ContactProperties;
import xyz.yychainsaw.portfolio.message.config.EmailOutboxProperties;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class EmailOutboxWorkerTest {
    private static final Instant NOW = Instant.parse("2026-07-14T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(10);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = 3;
    private static final String FROM = "portfolio-notify@example.com";
    private static final String OWNER = "portfolio-owner@example.com";
    private static final String VISITOR_NAME = "PII Name Sentinel \u6613\u5609\u8f69";
    private static final String VISITOR_EMAIL = "pii.sentinel@example.net";
    private static final String VISITOR_SUBJECT = "UE \u5408\u4f5c \ud83d\ude80";
    private static final String VISITOR_BODY = "PII Body Sentinel\n\u4f60\u597d\uff0c\u6211\u60f3\u804a\u804a\u6e38\u620f\u5f00\u53d1\u3002";
    private static final String TEMPLATE = "contact-notification-v1";
    private static final String CONTACT_SECRET =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String MAIL_ID_DOMAIN = "yychainsaw.xyz";
    private static final UUID OUTBOX_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID SECOND_OUTBOX_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final UUID FIRST_CYCLE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000201");
    private static final UUID SECOND_CYCLE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000202");
    private static final String FIRST_LEASE = "portfolio-email-" + FIRST_CYCLE_ID;
    private static final String SECOND_LEASE = "portfolio-email-" + SECOND_CYCLE_ID;

    @Mock EmailOutboxRepository repository;
    @Mock EmailSenderPort sender;

    @Test
    void disabledDeliveryDoesNotGenerateLeaseOrTouchRepositoryOrSender() {
        AtomicInteger generatedLeaseTokens = new AtomicInteger();
        Supplier<UUID> tokenSupplier = () -> {
            generatedLeaseTokens.incrementAndGet();
            return FIRST_CYCLE_ID;
        };
        EmailOutboxWorker worker = new EmailOutboxWorker(
                repository, sender, properties(false), CLOCK, tokenSupplier);

        worker.runOnce();

        assertThat(generatedLeaseTokens).hasValue(0);
        verifyNoInteractions(repository, sender);
    }

    @Test
    void successfulDeliveryRenewsTheFenceSendsOutsideATransactionAndMarksSent() {
        ContactNotification notification = notification(OUTBOX_ID);
        LeasedEmail leased = new LeasedEmail(FIRST_LEASE, 1, TEMPLATE, notification);
        when(repository.recoverExpiredAndClaim(
                        FIRST_LEASE, NOW, LEASE_DURATION, BATCH_SIZE))
                .thenReturn(List.of(leased));
        when(repository.renewLease(
                        OUTBOX_ID, FIRST_LEASE, 1, NOW.plus(LEASE_DURATION)))
                .thenReturn(true);
        when(repository.markSent(OUTBOX_ID, FIRST_LEASE, 1, NOW)).thenReturn(true);
        doAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return null;
                })
                .when(sender)
                .send(notification);
        EmailOutboxWorker worker = worker(FIRST_CYCLE_ID);

        worker.runOnce();

        InOrder order = inOrder(repository, sender);
        order.verify(repository).recoverExpiredAndClaim(
                FIRST_LEASE, NOW, LEASE_DURATION, BATCH_SIZE);
        order.verify(repository).renewLease(
                OUTBOX_ID, FIRST_LEASE, 1, NOW.plus(LEASE_DURATION));
        order.verify(sender).send(notification);
        order.verify(repository).markSent(OUTBOX_ID, FIRST_LEASE, 1, NOW);
    }

    @Test
    void lostLeaseFenceSkipsSmtpAndCannotWriteACompletion() {
        LeasedEmail leased = leased(OUTBOX_ID, FIRST_LEASE, 1);
        when(repository.recoverExpiredAndClaim(
                        FIRST_LEASE, NOW, LEASE_DURATION, BATCH_SIZE))
                .thenReturn(List.of(leased));
        when(repository.renewLease(
                        OUTBOX_ID, FIRST_LEASE, 1, NOW.plus(LEASE_DURATION)))
                .thenReturn(false);

        worker(FIRST_CYCLE_ID).runOnce();

        verifyNoInteractions(sender);
        verify(repository, never()).markSent(any(), any(), eq(1), any());
        verify(repository, never()).markFailed(any(), any(), eq(1), any(), any());
    }

    @ParameterizedTest(name = "attempt {0} fails with next-attempt delay {1}")
    @MethodSource("retrySchedule")
    void failureUsesTheCompleteRetrySchedule(
            int attempts, Duration delayUntilNextAttempt) {
        LeasedEmail leased = leased(OUTBOX_ID, FIRST_LEASE, attempts);
        when(repository.recoverExpiredAndClaim(
                        FIRST_LEASE, NOW, LEASE_DURATION, BATCH_SIZE))
                .thenReturn(List.of(leased));
        when(repository.renewLease(
                        OUTBOX_ID, FIRST_LEASE, attempts, NOW.plus(LEASE_DURATION)))
                .thenReturn(true);
        when(repository.markFailed(
                        eq(OUTBOX_ID),
                        eq(FIRST_LEASE),
                        eq(attempts),
                        eq(NOW.plus(delayUntilNextAttempt)),
                        any()))
                .thenReturn(true);
        doThrow(new MailSendException("mailbox unavailable"))
                .when(sender)
                .send(leased.notification());

        worker(FIRST_CYCLE_ID).runOnce();

        verify(repository).markFailed(
                eq(OUTBOX_ID),
                eq(FIRST_LEASE),
                eq(attempts),
                eq(NOW.plus(delayUntilNextAttempt)),
                any());
        verify(repository, never()).markSent(any(), any(), eq(attempts), any());
    }

    @Test
    void persistedFailureSummaryAndLogsContainOnlySafeMetadata(CapturedOutput output) {
        ContactNotification notification = notification(OUTBOX_ID);
        LeasedEmail leased = new LeasedEmail(FIRST_LEASE, 1, TEMPLATE, notification);
        when(repository.recoverExpiredAndClaim(
                        FIRST_LEASE, NOW, LEASE_DURATION, BATCH_SIZE))
                .thenReturn(List.of(leased));
        when(repository.renewLease(
                        OUTBOX_ID, FIRST_LEASE, 1, NOW.plus(LEASE_DURATION)))
                .thenReturn(true);
        when(repository.markFailed(
                        eq(OUTBOX_ID),
                        eq(FIRST_LEASE),
                        eq(1),
                        eq(NOW.plus(Duration.ofMinutes(1))),
                        any()))
                .thenReturn(true);
        doThrow(new MailSendException(
                        "SMTP rejected " + VISITOR_NAME + ' ' + VISITOR_EMAIL + ' ' + VISITOR_BODY))
                .when(sender)
                .send(notification);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);

        worker(FIRST_CYCLE_ID).runOnce();

        verify(repository).markFailed(
                eq(OUTBOX_ID),
                eq(FIRST_LEASE),
                eq(1),
                eq(NOW.plus(Duration.ofMinutes(1))),
                summary.capture());
        assertThat(summary.getValue())
                .contains("MailSendException")
                .matches("[A-Za-z0-9_$.-]{1,160}\\|[A-Z][A-Z0-9_]{0,79}")
                .doesNotContain(
                        VISITOR_NAME,
                        VISITOR_EMAIL,
                        VISITOR_SUBJECT,
                        VISITOR_BODY,
                        "SMTP rejected",
                        "mailbox unavailable");
        assertThat(output)
                .doesNotContain(
                        VISITOR_NAME,
                        VISITOR_EMAIL,
                        VISITOR_SUBJECT,
                        VISITOR_BODY,
                        "SMTP rejected",
                        "mailbox unavailable")
                .contains("category=SMTP_DELIVERY_FAILED");
    }

    @Test
    void oneFailedEmailDoesNotPreventTheRestOfTheClaimedBatchFromSending() {
        LeasedEmail first = leased(OUTBOX_ID, FIRST_LEASE, 1);
        LeasedEmail second = leased(SECOND_OUTBOX_ID, FIRST_LEASE, 1);
        when(repository.recoverExpiredAndClaim(
                        FIRST_LEASE, NOW, LEASE_DURATION, BATCH_SIZE))
                .thenReturn(List.of(first, second));
        when(repository.renewLease(
                        any(), eq(FIRST_LEASE), eq(1), eq(NOW.plus(LEASE_DURATION))))
                .thenReturn(true);
        when(repository.markFailed(
                        eq(OUTBOX_ID),
                        eq(FIRST_LEASE),
                        eq(1),
                        eq(NOW.plus(Duration.ofMinutes(1))),
                        any()))
                .thenReturn(true);
        when(repository.markSent(SECOND_OUTBOX_ID, FIRST_LEASE, 1, NOW)).thenReturn(true);
        doThrow(new MailSendException("first transport failed"))
                .when(sender)
                .send(first.notification());

        worker(FIRST_CYCLE_ID).runOnce();

        verify(sender).send(first.notification());
        verify(sender).send(second.notification());
        verify(repository).markFailed(
                eq(OUTBOX_ID),
                eq(FIRST_LEASE),
                eq(1),
                eq(NOW.plus(Duration.ofMinutes(1))),
                any());
        verify(repository).markSent(SECOND_OUTBOX_ID, FIRST_LEASE, 1, NOW);
    }

    @Test
    void everyClaimCycleUsesANewLeaseFenceToken() {
        when(repository.recoverExpiredAndClaim(
                        FIRST_LEASE, NOW, LEASE_DURATION, BATCH_SIZE))
                .thenReturn(List.of());
        when(repository.recoverExpiredAndClaim(
                        SECOND_LEASE, NOW, LEASE_DURATION, BATCH_SIZE))
                .thenReturn(List.of());
        EmailOutboxWorker worker = worker(FIRST_CYCLE_ID, SECOND_CYCLE_ID);

        worker.runOnce();
        worker.runOnce();

        verify(repository).recoverExpiredAndClaim(
                FIRST_LEASE, NOW, LEASE_DURATION, BATCH_SIZE);
        verify(repository).recoverExpiredAndClaim(
                SECOND_LEASE, NOW, LEASE_DURATION, BATCH_SIZE);
        assertThat(FIRST_LEASE).isNotEqualTo(SECOND_LEASE);
    }

    @Test
    void retryPassesTheSameStableMessageIdThroughToSmtp() {
        ContactNotification notification = notification(OUTBOX_ID);
        LeasedEmail firstAttempt = new LeasedEmail(FIRST_LEASE, 1, TEMPLATE, notification);
        LeasedEmail secondAttempt = new LeasedEmail(SECOND_LEASE, 2, TEMPLATE, notification);
        when(repository.recoverExpiredAndClaim(
                        FIRST_LEASE, NOW, LEASE_DURATION, BATCH_SIZE))
                .thenReturn(List.of(firstAttempt));
        when(repository.recoverExpiredAndClaim(
                        SECOND_LEASE, NOW, LEASE_DURATION, BATCH_SIZE))
                .thenReturn(List.of(secondAttempt));
        when(repository.renewLease(
                        OUTBOX_ID, FIRST_LEASE, 1, NOW.plus(LEASE_DURATION)))
                .thenReturn(true);
        when(repository.renewLease(
                        OUTBOX_ID, SECOND_LEASE, 2, NOW.plus(LEASE_DURATION)))
                .thenReturn(true);
        doThrow(new MailSendException("temporary failure"))
                .doNothing()
                .when(sender)
                .send(notification);
        EmailOutboxWorker worker = worker(FIRST_CYCLE_ID, SECOND_CYCLE_ID);

        worker.runOnce();
        worker.runOnce();

        ArgumentCaptor<ContactNotification> deliveries =
                ArgumentCaptor.forClass(ContactNotification.class);
        verify(sender, org.mockito.Mockito.times(2)).send(deliveries.capture());
        assertThat(deliveries.getAllValues())
                .extracting(ContactNotification::stableMessageId)
                .containsExactly(stableMessageId(OUTBOX_ID), stableMessageId(OUTBOX_ID));
    }

    @Test
    void workerMethodCannotOpenATransactionAroundSmtpIo() throws Exception {
        Transactional classBoundary = AnnotatedElementUtils.findMergedAnnotation(
                EmailOutboxWorker.class, Transactional.class);
        Transactional methodBoundary = AnnotatedElementUtils.findMergedAnnotation(
                EmailOutboxWorker.class.getDeclaredMethod("runOnce"), Transactional.class);

        assertThat(isAbsentOrNever(classBoundary)).isTrue();
        assertThat(isAbsentOrNever(methodBoundary)).isTrue();
    }

    @Test
    void smtpBuildsOneUtf8PlainTextMessageWithExactStableHeaders() throws Exception {
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        MimeMessage message = mimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);
        ContactNotification notification = notification(OUTBOX_ID);

        smtpSender(mailSender).send(notification);

        verify(mailSender).send(message);
        assertThat(message.getHeader("Message-ID"))
                .containsExactly(notification.stableMessageId());
        assertThat(message.getRecipients(Message.RecipientType.TO))
                .extracting(Address::toString)
                .containsExactly(OWNER);
        assertThat(message.getAllRecipients())
                .extracting(Address::toString)
                .containsExactly(OWNER);
        assertThat(message.getReplyTo())
                .extracting(Address::toString)
                .containsExactly(VISITOR_EMAIL);
        assertThat(message.getFrom())
                .extracting(Address::toString)
                .containsExactly(FROM);
        assertThat(message.getSubject())
                .startsWith("[Portfolio Contact] ")
                .endsWith(VISITOR_SUBJECT);
        assertThat(message.getSentDate().toInstant()).isEqualTo(notification.receivedAt());
        assertThat(message.isMimeType("text/plain")).isTrue();
        assertThat(message.getContentType()).containsIgnoringCase("charset=UTF-8");
        assertThat(message.getContent())
                .isInstanceOf(String.class)
                .asString()
                .contains(
                        VISITOR_NAME,
                        VISITOR_EMAIL,
                        VISITOR_SUBJECT,
                        VISITOR_BODY,
                        notification.receivedAt().toString());
        assertThat(message.getHeader("Cc")).isNull();
        assertThat(message.getHeader("Bcc")).isNull();
    }

    @Test
    void smtpAlwaysTargetsTheConfiguredOwnerInsteadOfAnOutboxRecipient() throws Exception {
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        MimeMessage message = mimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);
        ContactNotification tampered = new ContactNotification(
                OUTBOX_ID,
                stableMessageId(OUTBOX_ID),
                "attacker@example.net",
                VISITOR_EMAIL,
                VISITOR_NAME,
                VISITOR_SUBJECT,
                VISITOR_BODY,
                NOW.minusSeconds(30));

        smtpSender(mailSender).send(tampered);

        assertThat(message.getRecipients(Message.RecipientType.TO))
                .extracting(Address::toString)
                .containsExactly(OWNER);
    }

    @Test
    void smtpRetryKeepsOneVerbatimStableMessageIdOnEveryMimeMessage() throws Exception {
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        MimeMessage first = mimeMessage();
        MimeMessage second = mimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(first, second);
        ContactNotification notification = notification(OUTBOX_ID);
        SmtpEmailSender smtp = smtpSender(mailSender);

        smtp.send(notification);
        smtp.send(notification);

        assertThat(first.getHeader("Message-ID"))
                .containsExactly(notification.stableMessageId());
        assertThat(second.getHeader("Message-ID"))
                .containsExactly(notification.stableMessageId());
        assertThat(first.getHeader("Message-ID")).hasSize(1);
        assertThat(second.getHeader("Message-ID")).hasSize(1);
    }

    @Test
    void smtpSanitizesSubjectBreaksWithoutCreatingInjectedHeaders() throws Exception {
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        MimeMessage message = mimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);
        ContactNotification injected = new ContactNotification(
                OUTBOX_ID,
                stableMessageId(OUTBOX_ID),
                OWNER,
                VISITOR_EMAIL,
                VISITOR_NAME,
                "Hello\r\nBcc: injected@example.com\u2028X-Evil: yes",
                VISITOR_BODY,
                NOW.minusSeconds(30));

        smtpSender(mailSender).send(injected);

        assertThat(message.getHeader("Subject", null))
                .isEqualTo("[Portfolio Contact] Hello  Bcc: injected@example.com X-Evil: yes")
                .doesNotContain("\r", "\n", "\u2028", "\u2029");
        assertThat(message.getHeader("Bcc")).isNull();
        assertThat(message.getHeader("X-Evil")).isNull();
    }

    @Test
    void smtpRejectsReplyToHeaderInjectionBeforeTransport() {
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        MimeMessage message = mimeMessage();
        when(mailSender.createMimeMessage()).thenReturn(message);
        ContactNotification injected = new ContactNotification(
                OUTBOX_ID,
                stableMessageId(OUTBOX_ID),
                OWNER,
                "visitor@example.net\r\nBcc: injected@example.com",
                VISITOR_NAME,
                VISITOR_SUBJECT,
                VISITOR_BODY,
                NOW.minusSeconds(30));

        assertThatThrownBy(() -> smtpSender(mailSender).send(injected))
                .isInstanceOf(MailPreparationException.class)
                .hasMessage("contact notification preparation failed")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void piiBearingEmailTypesRedactTheirStringRepresentations() {
        ContactNotification notification = notification(OUTBOX_ID);
        LeasedEmail leased = new LeasedEmail(FIRST_LEASE, 1, TEMPLATE, notification);
        EmailOutboxProperties properties = properties(true);
        JavaMailSender mailSender = org.mockito.Mockito.mock(JavaMailSender.class);
        SmtpEmailSender smtp = smtpSender(mailSender);

        assertThat(notification.toString())
                .contains("<redacted>")
                .doesNotContain(
                        notification.stableMessageId(),
                        OWNER,
                        VISITOR_EMAIL,
                        VISITOR_NAME,
                        VISITOR_SUBJECT,
                        VISITOR_BODY);
        assertThat(leased.toString())
                .contains("<redacted>")
                .doesNotContain(FIRST_LEASE, notification.stableMessageId(), VISITOR_EMAIL, VISITOR_BODY);
        assertThat(properties.toString()).contains("<redacted>").doesNotContain(FROM);
        assertThat(smtp.toString()).contains("<redacted>").doesNotContain(FROM, OWNER);
    }

    private static Stream<Arguments> retrySchedule() {
        return Stream.of(
                Arguments.of(1, Duration.ofMinutes(1)),
                Arguments.of(2, Duration.ofMinutes(5)),
                Arguments.of(3, Duration.ofMinutes(15)),
                Arguments.of(4, Duration.ofMinutes(60)),
                Arguments.of(5, Duration.ofMinutes(240)),
                Arguments.of(6, Duration.ofMinutes(720)),
                Arguments.of(7, Duration.ofHours(24)),
                Arguments.of(8, Duration.ofHours(24)),
                Arguments.of(9, Duration.ofHours(24)),
                Arguments.of(10, Duration.ZERO),
                Arguments.of(11, Duration.ZERO));
    }

    private EmailOutboxWorker worker(UUID... leaseTokens) {
        Queue<UUID> tokens = new ArrayDeque<>(List.of(leaseTokens));
        Supplier<UUID> supplier = () -> {
            UUID token = tokens.poll();
            if (token == null) {
                throw new AssertionError("worker requested an unexpected lease token");
            }
            return token;
        };
        return new EmailOutboxWorker(repository, sender, properties(true), CLOCK, supplier);
    }

    private static LeasedEmail leased(UUID outboxId, String leaseOwner, int attempts) {
        return new LeasedEmail(leaseOwner, attempts, TEMPLATE, notification(outboxId));
    }

    private static ContactNotification notification(UUID outboxId) {
        return new ContactNotification(
                outboxId,
                stableMessageId(outboxId),
                OWNER,
                VISITOR_EMAIL,
                VISITOR_NAME,
                VISITOR_SUBJECT,
                VISITOR_BODY,
                NOW.minusSeconds(30));
    }

    private static String stableMessageId(UUID outboxId) {
        return "<portfolio-contact-" + outboxId + '@' + MAIL_ID_DOMAIN + '>';
    }

    private static EmailOutboxProperties properties(boolean enabled) {
        return new EmailOutboxProperties(
                enabled,
                enabled ? FROM : "",
                POLL_INTERVAL,
                LEASE_DURATION,
                BATCH_SIZE);
    }

    private static SmtpEmailSender smtpSender(JavaMailSender mailSender) {
        return new SmtpEmailSender(
                mailSender,
                properties(true),
                new ContactProperties(CONTACT_SECRET, OWNER, MAIL_ID_DOMAIN),
                secureMailProperties());
    }

    private static MailProperties secureMailProperties() {
        MailProperties properties = new MailProperties();
        properties.setProtocol("smtp");
        properties.setHost("smtp.example.com");
        properties.setPort(587);
        properties.setUsername("smtp-user");
        properties.setPassword("smtp-password");
        properties.getProperties().put("mail.smtp.auth", "true");
        properties.getProperties().put("mail.smtp.starttls.enable", "true");
        properties.getProperties().put("mail.smtp.starttls.required", "true");
        properties.getProperties().put("mail.smtp.ssl.checkserveridentity", "true");
        properties.getProperties().put("mail.smtp.connectiontimeout", "1000");
        properties.getProperties().put("mail.smtp.timeout", "1000");
        properties.getProperties().put("mail.smtp.writetimeout", "1000");
        return properties;
    }

    private static MimeMessage mimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    private static boolean isAbsentOrNever(Transactional boundary) {
        return boundary == null
                || boundary.propagation().value()
                        == TransactionDefinition.PROPAGATION_NEVER;
    }
}
