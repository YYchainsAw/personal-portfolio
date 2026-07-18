package xyz.yychainsaw.portfolio.analytics.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import xyz.yychainsaw.portfolio.analytics.domain.DeviceClass;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventRecord;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;
import xyz.yychainsaw.portfolio.publishing.application.CurrentPublicationQuery;
import xyz.yychainsaw.portfolio.publishing.config.PublicRenderProperties;

@Service
public final class AnalyticsCollector {
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final Pattern RATE_SUBJECT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BROWSER_ID = Pattern.compile("[A-Za-z0-9_-]{22,43}");
    private static final String RATE_POLICY = "public-events";
    private static final int MINIMUM_BROWSER_ID_BYTES = 16;
    private static final int MAXIMUM_BROWSER_ID_BYTES = 32;
    private static final int MAXIMUM_BATCH_SIZE = 20;
    private static final int MAXIMUM_REFERRER_UNITS = 2_048;
    private static final long MAXIMUM_RETRY_AFTER_SECONDS = 9_999_999_999L;

    private final RateLimiter rateLimiter;
    private final AnalyticsPrivacyService privacy;
    private final AnalyticsRules rules;
    private final CurrentPublicationQuery publications;
    private final AnalyticsEventDeduplicator deduplicator;
    private final String siteHost;
    private final Clock clock;
    private final Supplier<UUID> uuidGenerator;

    @Autowired
    public AnalyticsCollector(
            RateLimiter rateLimiter,
            AnalyticsPrivacyService privacy,
            AnalyticsRules rules,
            CurrentPublicationQuery publications,
            AnalyticsEventDeduplicator deduplicator,
            PublicRenderProperties renderProperties,
            Clock clock) {
        this(
                rateLimiter,
                privacy,
                rules,
                publications,
                deduplicator,
                canonicalSiteHost(renderProperties),
                clock,
                UUID::randomUUID);
    }

    AnalyticsCollector(
            RateLimiter rateLimiter,
            AnalyticsPrivacyService privacy,
            AnalyticsRules rules,
            CurrentPublicationQuery publications,
            AnalyticsEventDeduplicator deduplicator,
            String siteHost,
            Clock clock,
            Supplier<UUID> uuidGenerator) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rate limiter is required");
        this.privacy = Objects.requireNonNull(privacy, "analytics privacy service is required");
        this.rules = Objects.requireNonNull(rules, "analytics rules are required");
        this.publications = Objects.requireNonNull(
                publications, "current publication query is required");
        this.deduplicator = Objects.requireNonNull(
                deduplicator, "analytics event deduplicator is required");
        this.siteHost = requireSiteHost(siteHost);
        this.clock = Objects.requireNonNull(clock, "analytics clock is required");
        this.uuidGenerator = Objects.requireNonNull(
                uuidGenerator, "analytics UUID generator is required");
    }

    public void collect(CollectAnalyticsCommand command) {
        Objects.requireNonNull(command, "analytics command is required");
        String subject = requireRateLimitSubject(command.rateLimitSubject());
        consumeRateLimit(subject);
        if (rules.isCrawler(command.userAgent())) {
            return;
        }

        Instant receivedAt = Objects.requireNonNull(
                        clock.instant(), "analytics clock returned no instant")
                .truncatedTo(ChronoUnit.MICROS);
        String visitorId = requireBrowserId(command.visitorId());
        String sessionId = requireBrowserId(command.sessionId());
        if (visitorId.equals(sessionId)) {
            throw invalidEvent();
        }
        List<NormalizedEvent> normalized = normalizeEvents(
                command.events(), visitorId, sessionId);

        LocalDate siteDate = receivedAt.atZone(SITE_ZONE).toLocalDate();
        String visitorDayKey = privacy.visitorDayKey(siteDate, visitorId);
        String sessionDayKey = privacy.sessionDayKey(siteDate, sessionId);
        DeviceClass device = rules.classifyDevice(command.userAgent());

        for (NormalizedEvent event : normalized) {
            AnalyticsEventRecord record = new AnalyticsEventRecord(
                    nextUuid(),
                    event.eventId(),
                    siteDate,
                    receivedAt,
                    visitorDayKey,
                    sessionDayKey,
                    event.type(),
                    event.pageKey(),
                    event.projectId(),
                    event.referrerDomain(),
                    device,
                    event.locale(),
                    rules.version(),
                    receivedAt);
            deduplicator.persist(record);
        }
    }

    private List<NormalizedEvent> normalizeEvents(
            List<CollectAnalyticsCommand.Event> events,
            String visitorId,
            String sessionId) {
        if (events == null || events.isEmpty() || events.size() > MAXIMUM_BATCH_SIZE) {
            throw invalidEvent();
        }
        List<NormalizedEvent> normalized = new ArrayList<>(events.size());
        Set<UUID> eventIds = new HashSet<>();
        for (CollectAnalyticsCommand.Event event : events) {
            if (event == null
                    || event.eventId() == null
                    || !eventIds.add(event.eventId())
                    || event.eventId().version() != 4
                    || event.eventId().variant() != 2
                    || event.type() == null
                    || event.locale() == null
                    || event.referrer() != null
                            && event.referrer().length() > MAXIMUM_REFERRER_UNITS
                    || !rules.isAllowedPageKey(event.pageKey())) {
                throw invalidEvent();
            }
            if (rules.projectRequired(event.type()) && event.projectId() == null) {
                throw invalidEvent();
            }
            if (!validEventShape(event)) {
                throw invalidEvent();
            }
            if (event.projectId() != null
                    && !publications.isCurrentPublishedProject(event.projectId())) {
                throw invalidEvent();
            }
            String referrerDomain;
            try {
                referrerDomain = rules.normalizeReferrer(
                        event.referrer(), siteHost, visitorId, sessionId);
            } catch (IllegalArgumentException invalid) {
                throw invalidEvent();
            }
            normalized.add(new NormalizedEvent(
                    event.eventId(),
                    event.type(),
                    event.pageKey(),
                    event.projectId(),
                    referrerDomain,
                    event.locale()));
        }
        return List.copyOf(normalized);
    }

    private static boolean validEventShape(CollectAnalyticsCommand.Event event) {
        boolean projectDetail = "PROJECT_DETAIL".equals(event.pageKey());
        return switch (event.type()) {
            case PROJECT_VIEW, DEMO_DOWNLOAD ->
                    projectDetail && event.projectId() != null;
            case RESUME_DOWNLOAD -> event.projectId() == null;
            case PAGE_VIEW, OUTBOUND_CLICK ->
                    projectDetail == (event.projectId() != null);
        };
    }

    private void consumeRateLimit(String subject) {
        RateLimitDecision decision;
        try {
            decision = rateLimiter.consume(RATE_POLICY, subject);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("analytics rate limiting failed");
        }
        if (decision == null) {
            throw new IllegalStateException("analytics rate limiting failed");
        }
        if (decision.allowed()) {
            return;
        }
        long retryAfter = Math.max(
                1,
                Math.min(decision.retryAfterSeconds(), MAXIMUM_RETRY_AFTER_SECONDS));
        throw new DomainException(
                "ANALYTICS_RATE_LIMITED",
                HttpStatus.TOO_MANY_REQUESTS,
                Map.of("retryAfterSeconds", Long.toString(retryAfter)));
    }

    private static String requireBrowserId(String value) {
        if (value == null || !BROWSER_ID.matcher(value).matches()) {
            throw invalidEvent();
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException invalid) {
            throw invalidEvent();
        }
        try {
            if (decoded.length < MINIMUM_BROWSER_ID_BYTES
                    || decoded.length > MAXIMUM_BROWSER_ID_BYTES
                    || allZero(decoded)
                    || !Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(decoded).equals(value)) {
                throw invalidEvent();
            }
            return value;
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private static boolean allZero(byte[] value) {
        int aggregate = 0;
        for (byte current : value) {
            aggregate |= current;
        }
        return aggregate == 0;
    }

    private UUID nextUuid() {
        UUID generated = Objects.requireNonNull(
                uuidGenerator.get(), "analytics UUID generator returned null");
        if (generated.variant() != 2 || generated.version() != 4) {
            throw new IllegalStateException("analytics UUID generator returned an invalid UUID");
        }
        return generated;
    }

    private static String requireRateLimitSubject(String value) {
        if (value == null || !RATE_SUBJECT.matcher(value).matches()) {
            throw new IllegalStateException("analytics rate-limit subject is invalid");
        }
        return value;
    }

    private static String canonicalSiteHost(PublicRenderProperties properties) {
        PublicRenderProperties render = Objects.requireNonNull(
                properties, "public render properties are required");
        return requireSiteHost(render.publicBaseUrl().getHost());
    }

    private static String requireSiteHost(String value) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("analytics site host is invalid");
        }
        return value;
    }

    private static DomainException invalidEvent() {
        return new DomainException(
                "ANALYTICS_EVENT_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, Map.of());
    }

    private record NormalizedEvent(
            UUID eventId,
            xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType type,
            String pageKey,
            UUID projectId,
            String referrerDomain,
            xyz.yychainsaw.portfolio.content.api.LocaleCode locale) {
    }
}
