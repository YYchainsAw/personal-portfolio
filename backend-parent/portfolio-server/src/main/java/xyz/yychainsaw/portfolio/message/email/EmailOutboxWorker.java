package xyz.yychainsaw.portfolio.message.email;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import xyz.yychainsaw.portfolio.message.config.EmailOutboxProperties;

@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(
        prefix = "portfolio.email",
        name = "enabled",
        havingValue = "true")
public final class EmailOutboxWorker {
    static final String SCHEDULER_BEAN = "emailOutboxTaskScheduler";
    static final String TEMPLATE_NAME = "contact-notification-v1";

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailOutboxWorker.class);
    private static final Duration[] RETRY_DELAYS = {
        Duration.ofMinutes(1),
        Duration.ofMinutes(5),
        Duration.ofMinutes(15),
        Duration.ofMinutes(60),
        Duration.ofMinutes(240),
        Duration.ofMinutes(720)
    };
    private static final Duration DAILY_RETRY = Duration.ofHours(24);

    private final EmailOutboxRepository repository;
    private final EmailSenderPort sender;
    private final EmailOutboxProperties properties;
    private final Clock clock;
    private final Supplier<UUID> leaseTokens;

    @Autowired
    public EmailOutboxWorker(
            EmailOutboxRepository repository,
            EmailSenderPort sender,
            EmailOutboxProperties properties,
            Clock clock) {
        this(repository, sender, properties, clock, UUID::randomUUID);
    }

    EmailOutboxWorker(
            EmailOutboxRepository repository,
            EmailSenderPort sender,
            EmailOutboxProperties properties,
            Clock clock,
            Supplier<UUID> leaseTokens) {
        this.repository = Objects.requireNonNull(repository, "email outbox repository is required");
        this.sender = Objects.requireNonNull(sender, "email sender is required");
        this.properties = Objects.requireNonNull(properties, "email outbox properties are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.leaseTokens = Objects.requireNonNull(leaseTokens, "lease token supplier is required");
    }

    @Scheduled(
            initialDelayString = "${portfolio.email.poll-interval:PT10S}",
            fixedDelayString = "${portfolio.email.poll-interval:PT10S}",
            scheduler = SCHEDULER_BEAN)
    public void runOnce() {
        if (!properties.enabled()) {
            return;
        }
        requireNoTransaction();

        Instant claimTime = databaseTime(clock.instant());
        String leaseOwner = newLeaseOwner();
        List<LeasedEmail> leased;
        try {
            leased = repository.recoverExpiredAndClaim(
                    leaseOwner,
                    claimTime,
                    properties.leaseDuration(),
                    properties.batchSize());
        } catch (RuntimeException failure) {
            LOGGER.error("Email outbox claim failed");
            return;
        }

        if (leased == null || leased.isEmpty()) {
            return;
        }
        for (LeasedEmail email : List.copyOf(leased)) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            deliver(email);
        }
    }

    static Duration retryDelay(int attempts) {
        if (attempts < 1 || attempts >= 10) {
            throw new IllegalArgumentException("email retry attempt is invalid");
        }
        if (attempts <= RETRY_DELAYS.length) {
            return RETRY_DELAYS[attempts - 1];
        }
        return DAILY_RETRY;
    }

    private void deliver(LeasedEmail email) {
        if (email == null) {
            LOGGER.error("Email outbox returned an invalid lease");
            return;
        }
        ContactNotification notification = email.notification();
        UUID outboxId = notification.outboxId();
        Instant sendStartedAt = databaseTime(clock.instant());
        Instant renewedUntil;
        try {
            renewedUntil = databaseTime(sendStartedAt.plus(properties.leaseDuration()));
        } catch (RuntimeException failure) {
            LOGGER.error("Email outbox lease renewal timestamp failed for id={}", outboxId);
            return;
        }

        boolean renewed;
        try {
            renewed = repository.renewLease(
                    outboxId, email.leaseOwner(), email.attempts(), renewedUntil);
        } catch (RuntimeException failure) {
            LOGGER.error("Email outbox lease renewal failed for id={}", outboxId);
            return;
        }
        if (!renewed) {
            LOGGER.warn("Email outbox lease was lost before delivery for id={}", outboxId);
            return;
        }

        if (!TEMPLATE_NAME.equals(email.templateName())) {
            fail(email, new MailPreparationException("unsupported email template"));
            return;
        }

        try {
            requireNoTransaction();
            sender.send(notification);
            requireNoTransaction();
        } catch (RuntimeException failure) {
            fail(email, failure);
            return;
        }

        try {
            boolean marked = repository.markSent(
                    outboxId,
                    email.leaseOwner(),
                    email.attempts(),
                    databaseTime(clock.instant()));
            if (!marked) {
                LOGGER.warn("Email outbox sent acknowledgement lost its lease for id={}", outboxId);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Email outbox sent acknowledgement failed for id={}", outboxId);
        }
    }

    private void fail(LeasedEmail email, RuntimeException failure) {
        FailureCategory category = FailureCategory.from(failure);
        Instant failedAt = databaseTime(clock.instant());
        Instant nextAttemptAt = email.attempts() >= 10
                ? failedAt
                : databaseTime(failedAt.plus(retryDelay(email.attempts())));
        boolean marked;
        try {
            marked = repository.markFailed(
                    email.notification().outboxId(),
                    email.leaseOwner(),
                    email.attempts(),
                    nextAttemptAt,
                    category.summary());
        } catch (RuntimeException persistenceFailure) {
            LOGGER.error(
                    "Email outbox failure transition failed for id={}",
                    email.notification().outboxId());
            return;
        }
        if (!marked) {
            LOGGER.warn(
                    "Email outbox failure transition lost its lease for id={}",
                    email.notification().outboxId());
            return;
        }
        LOGGER.warn(
                "Email outbox delivery failed for id={} category={}",
                email.notification().outboxId(),
                category.code());
    }

    private String newLeaseOwner() {
        UUID token = Objects.requireNonNull(leaseTokens.get(), "lease token is required");
        return "portfolio-email-" + token;
    }

    private static Instant databaseTime(Instant value) {
        return Objects.requireNonNull(value, "email worker timestamp is required")
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("email delivery requires no ambient transaction");
        }
    }

    private enum FailureCategory {
        AUTHENTICATION("MailAuthenticationException", "SMTP_AUTHENTICATION_FAILED"),
        CONNECTION("MailConnectException", "SMTP_CONNECTION_FAILED"),
        PREPARATION("MailPreparationException", "MESSAGE_PREPARATION_FAILED"),
        DELIVERY("MailSendException", "SMTP_DELIVERY_FAILED"),
        MAIL("MailException", "SMTP_DELIVERY_FAILED"),
        UNEXPECTED("RuntimeException", "UNEXPECTED_DELIVERY_FAILURE");

        private final String exceptionClass;
        private final String code;

        FailureCategory(String exceptionClass, String code) {
            this.exceptionClass = exceptionClass;
            this.code = code;
        }

        String code() {
            return code;
        }

        String summary() {
            return exceptionClass + '|' + code;
        }

        static FailureCategory from(RuntimeException failure) {
            Objects.requireNonNull(failure);
            if (failure instanceof MailAuthenticationException) {
                return AUTHENTICATION;
            }
            if ("MailConnectException".equals(failure.getClass().getSimpleName())) {
                return CONNECTION;
            }
            if (failure instanceof MailPreparationException) {
                return PREPARATION;
            }
            if (failure instanceof MailSendException) {
                return DELIVERY;
            }
            if (failure instanceof MailException) {
                return MAIL;
            }
            return UNEXPECTED;
        }
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(
        prefix = "portfolio.email",
        name = "enabled",
        havingValue = "true")
class EmailOutboxSchedulingConfiguration {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(EmailOutboxSchedulingConfiguration.class);

    @Bean(name = EmailOutboxWorker.SCHEDULER_BEAN)
    ThreadPoolTaskScheduler emailOutboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("portfolio-email-outbox-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationMillis(Duration.ofSeconds(30).toMillis());
        scheduler.setErrorHandler(ignored ->
                LOGGER.error("Email outbox scheduler task failed"));
        return scheduler;
    }
}
