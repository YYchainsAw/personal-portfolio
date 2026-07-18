package xyz.yychainsaw.portfolio.message.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.message.config.ContactProperties;
import xyz.yychainsaw.portfolio.message.config.EmailOutboxProperties;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(
        prefix = "portfolio.email",
        name = "enabled",
        havingValue = "true")
public final class SmtpEmailSender implements EmailSenderPort {
    private static final String SUBJECT_PREFIX = "[Portfolio Contact] ";
    private static final String SMTP_PROTOCOL = "smtp";
    private static final Pattern STABLE_MESSAGE_ID = Pattern.compile(
            "<portfolio-contact-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
                    + "@[a-z0-9](?:[a-z0-9.-]{0,196}[a-z0-9])?>");
    private static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final int MAXIMUM_CREDENTIAL_LENGTH = 4_096;
    private static final int MAXIMUM_TIMEOUT_MILLIS = 60_000;
    private static final Duration LEASE_SAFETY_MARGIN = Duration.ofSeconds(5);

    private final JavaMailSender mailSender;
    private final String from;
    private final String recipient;

    public SmtpEmailSender(
            JavaMailSender mailSender,
            EmailOutboxProperties outboxProperties,
            ContactProperties contactProperties,
            MailProperties mailProperties) {
        this.mailSender = Objects.requireNonNull(mailSender, "JavaMailSender is required");
        Objects.requireNonNull(outboxProperties, "email outbox properties are required");
        Objects.requireNonNull(contactProperties, "contact properties are required");
        Objects.requireNonNull(mailProperties, "mail properties are required");
        if (!outboxProperties.enabled()) {
            throw new IllegalArgumentException("SMTP sender requires enabled delivery");
        }
        this.from = requireAddress(outboxProperties.from(), "email sender address is invalid");
        this.recipient = requireAddress(
                contactProperties.ownerEmail(), "contact owner address is invalid");
        validateTransport(mailProperties, outboxProperties.leaseDuration());
    }

    @Override
    public void send(ContactNotification notification) {
        Objects.requireNonNull(notification, "contact notification is required");
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(from, true));
            helper.setTo(new InternetAddress(recipient, true));
            helper.setReplyTo(new InternetAddress(
                    requireAddress(notification.replyTo(), "notification reply-to is invalid"),
                    true));
            helper.setSubject(SUBJECT_PREFIX + sanitizeSubject(notification.subject()));
            helper.setSentDate(Date.from(notification.receivedAt()));
            helper.setText(renderPlainText(notification), false);

            String stableMessageId = requireStableMessageId(notification.stableMessageId());
            message.saveChanges();
            message.setHeader("Message-ID", stableMessageId);
            mailSender.send(message);
        } catch (MessagingException | IllegalArgumentException failure) {
            throw new MailPreparationException("contact notification preparation failed", failure);
        }
    }

    @Override
    public String toString() {
        return "SmtpEmailSender[from=<redacted>, transport=smtp]";
    }

    private static void validateTransport(
            MailProperties properties, Duration leaseDuration) {
        String protocol = properties.getProtocol();
        if (protocol != null
                && !protocol.isBlank()
                && !SMTP_PROTOCOL.equals(protocol.toLowerCase(Locale.ROOT))) {
            throw invalidTransport();
        }
        if ((properties.getJndiName() != null && !properties.getJndiName().isBlank())
                || properties.getSsl().isEnabled()
                || (properties.getSsl().getBundle() != null
                        && !properties.getSsl().getBundle().isBlank())) {
            throw invalidTransport();
        }
        String host = properties.getHost();
        if (host == null
                || host.isBlank()
                || !host.equals(host.trim())
                || host.length() > 253
                || !SAFE_HOST.matcher(host).matches()) {
            throw invalidTransport();
        }
        Integer port = properties.getPort();
        if (port == null || port < 1 || port > 65_535) {
            throw invalidTransport();
        }
        requireCredential(properties.getUsername());
        requireCredential(properties.getPassword());

        Map<String, String> transport = properties.getProperties();
        requireTrue(transport, "mail.smtp.auth");
        requireTrue(transport, "mail.smtp.starttls.enable");
        requireTrue(transport, "mail.smtp.starttls.required");
        requireTrue(transport, "mail.smtp.ssl.checkserveridentity");
        if (Boolean.parseBoolean(transport.get("mail.smtp.ssl.enable"))
                || hasText(transport.get("mail.smtp.ssl.trust"))
                || transport.keySet().stream().anyMatch(SmtpEmailSender::isUnsafeSocketOverride)) {
            throw invalidTransport();
        }
        long timeoutBudget = 0;
        timeoutBudget = Math.addExact(
                timeoutBudget, requireTimeout(transport, "mail.smtp.connectiontimeout"));
        timeoutBudget = Math.addExact(
                timeoutBudget, requireTimeout(transport, "mail.smtp.timeout"));
        timeoutBudget = Math.addExact(
                timeoutBudget, requireTimeout(transport, "mail.smtp.writetimeout"));
        long minimumLease = Math.addExact(timeoutBudget, LEASE_SAFETY_MARGIN.toMillis());
        if (leaseDuration.toMillis() <= minimumLease) {
            throw invalidTransport();
        }
    }

    private static void requireCredential(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > MAXIMUM_CREDENTIAL_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalidTransport();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isUnsafeSocketOverride(String key) {
        return key != null
                && (key.startsWith("mail.smtp.socketFactory")
                        || key.startsWith("mail.smtp.ssl.socketFactory")
                        || key.equals("mail.smtp.ssl.protocols")
                        || key.equals("mail.smtp.ssl.ciphersuites")
                        || key.startsWith("mail.debug"));
    }

    private static void requireTrue(Map<String, String> properties, String key) {
        if (!"true".equalsIgnoreCase(properties.get(key))) {
            throw invalidTransport();
        }
    }

    private static int requireTimeout(Map<String, String> properties, String key) {
        String configured = properties.get(key);
        int timeout;
        try {
            timeout = Integer.parseInt(configured);
        } catch (NumberFormatException failure) {
            throw invalidTransport();
        }
        if (timeout < 1 || timeout > MAXIMUM_TIMEOUT_MILLIS) {
            throw invalidTransport();
        }
        return timeout;
    }

    private static String requireAddress(String value, String failureMessage) {
        if (value == null
                || value.isBlank()
                || value.length() > 320
                || !value.equals(stripHeaderBreaks(value))
                || value.chars().anyMatch(character ->
                        Character.isISOControl(character) || Character.isWhitespace(character))) {
            throw new IllegalArgumentException(failureMessage);
        }
        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            if (!value.equals(address.getAddress())) {
                throw new IllegalArgumentException(failureMessage);
            }
            return value;
        } catch (AddressException failure) {
            throw new IllegalArgumentException(failureMessage);
        }
    }

    private static String requireStableMessageId(String value) {
        if (value == null
                || !value.equals(stripHeaderBreaks(value))
                || !STABLE_MESSAGE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("stable message ID is invalid");
        }
        return value;
    }

    private static String sanitizeSubject(String value) {
        String withoutBreaks = stripHeaderBreaks(Objects.requireNonNull(value));
        StringBuilder safe = new StringBuilder(withoutBreaks.length());
        withoutBreaks.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString().trim();
    }

    private static String stripHeaderBreaks(String value) {
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\u2028', ' ')
                .replace('\u2029', ' ');
    }

    private static String renderPlainText(ContactNotification notification) {
        return "A new portfolio contact message was received.\n\n"
                + "Received: " + DateTimeFormatter.ISO_INSTANT.format(notification.receivedAt())
                + "\nName: " + notification.visitorName()
                + "\nEmail: " + notification.replyTo()
                + "\nSubject: " + notification.subject()
                + "\n\nMessage:\n" + notification.body();
    }

    private static IllegalArgumentException invalidTransport() {
        return new IllegalArgumentException("secure SMTP configuration is invalid");
    }
}
