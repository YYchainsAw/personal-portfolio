package xyz.yychainsaw.portfolio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import xyz.yychainsaw.portfolio.analytics.config.AnalyticsProperties;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.analytics.domain.DeviceClass;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventRecord;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitDecision;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimiter;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.publishing.application.CurrentPublicationQuery;

class AnalyticsCollectorTest {
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-14T16:30:00.123456Z");
    private static final UUID EVENT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STORED_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final String VISITOR_ID = browserId(0x10);
    private static final String SESSION_ID = browserId(0x40);
    private static final String RAW_ADDRESS = "203.0.113.9";
    private static final String RATE_SUBJECT = "a".repeat(64);
    private static final String USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)";

    private RateLimiter limiter;
    private CurrentPublicationQuery publications;
    private AnalyticsEventDeduplicator deduplicator;
    private AnalyticsPrivacyService privacy;
    private AnalyticsCollector collector;

    @BeforeEach
    void setUp() {
        limiter = mock(RateLimiter.class);
        publications = mock(CurrentPublicationQuery.class);
        deduplicator = mock(AnalyticsEventDeduplicator.class);
        AnalyticsProperties properties = new AnalyticsProperties(testSecret());
        privacy = new AnalyticsPrivacyService(properties);
        when(limiter.consume(anyString(), anyString())).thenReturn(RateLimitDecision.allow());
        when(deduplicator.persist(any())).thenReturn(true);
        when(publications.isCurrentPublishedProject(PROJECT_ID)).thenReturn(true);
        collector = new AnalyticsCollector(
                limiter,
                privacy,
                new AnalyticsRules(),
                publications,
                deduplicator,
                "yychainsaw.xyz",
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC),
                () -> STORED_ID);
    }

    @Test
    void storesDailyKeysAndCoarseDeviceButNoRequestIdentity() {
        String subject = RATE_SUBJECT;

        collector.collect(command(
                subject,
                new CollectAnalyticsCommand.Event(
                        EVENT_ID,
                        AnalyticsEventType.PAGE_VIEW,
                        "HOME",
                        null,
                        "https://YYCHAINSAW.XYZ/work?private=value#fragment",
                        LocaleCode.ZH_CN)));

        ArgumentCaptor<AnalyticsEventRecord> stored =
                ArgumentCaptor.forClass(AnalyticsEventRecord.class);
        verify(deduplicator).persist(stored.capture());
        AnalyticsEventRecord row = stored.getValue();
        assertThat(row.id()).isEqualTo(STORED_ID);
        assertThat(row.clientEventId()).isEqualTo(EVENT_ID);
        assertThat(row.siteDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(row.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(row.visitorDayKey())
                .matches("[0-9a-f]{64}")
                .doesNotContain(VISITOR_ID);
        assertThat(row.sessionDayKey())
                .matches("[0-9a-f]{64}")
                .doesNotContain(SESSION_ID);
        assertThat(row.visitorDayKey()).isNotEqualTo(row.sessionDayKey());
        assertThat(row.deviceClass()).isEqualTo(DeviceClass.MOBILE);
        assertThat(row.referrerDomain()).isEqualTo("(direct)");
        assertThat(row.rulesVersion()).isEqualTo("analytics-rules-v1");
        assertThat(row.toString())
                .doesNotContain(VISITOR_ID, SESSION_ID, RAW_ADDRESS, USER_AGENT)
                .contains("<redacted>");
        verify(limiter).consume("public-events", subject);
    }

    @Test
    void normalizesExternalReferrerToAsciiHostnameOnly() {
        collector.collect(command(
                RATE_SUBJECT,
                new CollectAnalyticsCommand.Event(
                        EVENT_ID,
                        AnalyticsEventType.OUTBOUND_CLICK,
                        "WORK",
                        null,
                        "https://WWW.GitHub.COM:8443/path?q=identity#part",
                        LocaleCode.EN)));

        ArgumentCaptor<AnalyticsEventRecord> stored =
                ArgumentCaptor.forClass(AnalyticsEventRecord.class);
        verify(deduplicator).persist(stored.capture());
        assertThat(stored.getValue().referrerDomain()).isEqualTo("github.com");
    }

    @Test
    void rejectsUnpublishedProjectAndUnknownPageKeys() {
        when(publications.isCurrentPublishedProject(PROJECT_ID)).thenReturn(false);

        assertInvalid(command(
                RATE_SUBJECT,
                new CollectAnalyticsCommand.Event(
                        EVENT_ID,
                        AnalyticsEventType.PROJECT_VIEW,
                        "PROJECT_DETAIL",
                        PROJECT_ID,
                        null,
                        LocaleCode.EN)));
        assertInvalid(command(
                RATE_SUBJECT,
                new CollectAnalyticsCommand.Event(
                        EVENT_ID,
                        AnalyticsEventType.PAGE_VIEW,
                        "PRIVATE_QUERY_VALUE",
                        null,
                        null,
                        LocaleCode.EN)));

        verify(deduplicator, never()).persist(any());
    }

    @Test
    void requiredProjectsMustExistAndEveryProvidedProjectMustBePublished() {
        assertInvalid(command(
                RATE_SUBJECT,
                new CollectAnalyticsCommand.Event(
                        EVENT_ID,
                        AnalyticsEventType.DEMO_DOWNLOAD,
                        "PROJECT_DETAIL",
                        null,
                        null,
                        LocaleCode.EN)));
        when(publications.isCurrentPublishedProject(PROJECT_ID)).thenReturn(false);
        assertInvalid(command(
                RATE_SUBJECT,
                new CollectAnalyticsCommand.Event(
                        EVENT_ID,
                        AnalyticsEventType.PAGE_VIEW,
                        "HOME",
                        PROJECT_ID,
                        null,
                        LocaleCode.EN)));

        verify(deduplicator, never()).persist(any());
    }

    @Test
    void eventPageAndProjectMatrixIsEnforcedServerSide() {
        assertInvalid(command(
                RATE_SUBJECT,
                new CollectAnalyticsCommand.Event(
                        EVENT_ID,
                        AnalyticsEventType.PROJECT_VIEW,
                        "HOME",
                        PROJECT_ID,
                        null,
                        LocaleCode.EN)));
        assertInvalid(command(
                RATE_SUBJECT,
                new CollectAnalyticsCommand.Event(
                        EVENT_ID,
                        AnalyticsEventType.RESUME_DOWNLOAD,
                        "ABOUT",
                        PROJECT_ID,
                        null,
                        LocaleCode.EN)));
        assertInvalid(command(
                RATE_SUBJECT,
                new CollectAnalyticsCommand.Event(
                        EVENT_ID,
                        AnalyticsEventType.PAGE_VIEW,
                        "PROJECT_DETAIL",
                        null,
                        null,
                        LocaleCode.EN)));
        assertInvalid(command(
                RATE_SUBJECT,
                new CollectAnalyticsCommand.Event(
                        EVENT_ID,
                        AnalyticsEventType.OUTBOUND_CLICK,
                        "PROJECT_DETAIL",
                        null,
                        null,
                        LocaleCode.EN)));

        verify(deduplicator, never()).persist(any());
    }

    @Test
    void projectDetailPageViewMayReferenceOnlyTheCurrentPublishedProject() {
        collector.collect(command(
                RATE_SUBJECT,
                new CollectAnalyticsCommand.Event(
                        EVENT_ID,
                        AnalyticsEventType.PAGE_VIEW,
                        "PROJECT_DETAIL",
                        PROJECT_ID,
                        null,
                        LocaleCode.EN)));

        verify(publications).isCurrentPublishedProject(PROJECT_ID);
        verify(deduplicator).persist(any());
    }

    @Test
    void rejectsNonCanonicalIdentifiersAndOutOfBoundsBatches() {
        CollectAnalyticsCommand.Event event = new CollectAnalyticsCommand.Event(
                EVENT_ID,
                AnalyticsEventType.PAGE_VIEW,
                "HOME",
                null,
                null,
                LocaleCode.EN);

        assertInvalid(new CollectAnalyticsCommand(
                "not-base64url", SESSION_ID, USER_AGENT,
                RATE_SUBJECT, List.of(event)));
        assertInvalid(new CollectAnalyticsCommand(
                VISITOR_ID + "=", SESSION_ID, USER_AGENT,
                RATE_SUBJECT, List.of(event)));
        assertInvalid(new CollectAnalyticsCommand(
                browserIdBytes(15), SESSION_ID, USER_AGENT,
                RATE_SUBJECT, List.of(event)));
        assertInvalid(new CollectAnalyticsCommand(
                browserIdBytes(33), SESSION_ID, USER_AGENT,
                RATE_SUBJECT, List.of(event)));
        assertInvalid(new CollectAnalyticsCommand(
                nonCanonical(VISITOR_ID), SESSION_ID, USER_AGENT,
                RATE_SUBJECT, List.of(event)));
        assertInvalid(new CollectAnalyticsCommand(
                browserIdBytes(16, (byte) 0), SESSION_ID, USER_AGENT,
                RATE_SUBJECT, List.of(event)));
        assertInvalid(new CollectAnalyticsCommand(
                VISITOR_ID, VISITOR_ID, USER_AGENT,
                RATE_SUBJECT, List.of(event)));
        assertInvalid(new CollectAnalyticsCommand(
                VISITOR_ID, SESSION_ID, USER_AGENT,
                RATE_SUBJECT, List.of()));
        assertInvalid(new CollectAnalyticsCommand(
                VISITOR_ID, SESSION_ID, USER_AGENT,
                RATE_SUBJECT,
                java.util.Collections.nCopies(21, event)));

        verify(deduplicator, never()).persist(any());
    }

    @Test
    void rejectsNonV4AndBatchDuplicateEventIdsBeforeAnyWrite() {
        CollectAnalyticsCommand.Event nonV4 = new CollectAnalyticsCommand.Event(
                UUID.fromString("20000000-0000-1000-8000-000000000001"),
                AnalyticsEventType.PAGE_VIEW,
                "HOME",
                null,
                null,
                LocaleCode.EN);
        CollectAnalyticsCommand.Event valid = new CollectAnalyticsCommand.Event(
                EVENT_ID,
                AnalyticsEventType.PAGE_VIEW,
                "HOME",
                null,
                null,
                LocaleCode.EN);
        CollectAnalyticsCommand.Event invalidSecond = new CollectAnalyticsCommand.Event(
                UUID.fromString("20000000-0000-4000-8000-000000000002"),
                AnalyticsEventType.PAGE_VIEW,
                "PRIVATE",
                null,
                null,
                LocaleCode.EN);

        assertInvalid(new CollectAnalyticsCommand(
                VISITOR_ID, SESSION_ID, USER_AGENT, RATE_SUBJECT, List.of(nonV4)));
        assertInvalid(new CollectAnalyticsCommand(
                VISITOR_ID, SESSION_ID, USER_AGENT, RATE_SUBJECT,
                List.of(valid, valid)));
        assertInvalid(new CollectAnalyticsCommand(
                VISITOR_ID, SESSION_ID, USER_AGENT, RATE_SUBJECT,
                List.of(valid, invalidSecond)));

        verify(deduplicator, never()).persist(any());
    }

    @Test
    void commandAndNestedEventNeverRenderRawAnalyticsInputs() {
        CollectAnalyticsCommand.Event event = new CollectAnalyticsCommand.Event(
                EVENT_ID,
                AnalyticsEventType.PAGE_VIEW,
                "HOME",
                null,
                "https://github.com/private",
                LocaleCode.EN);
        CollectAnalyticsCommand command = new CollectAnalyticsCommand(
                VISITOR_ID, SESSION_ID, USER_AGENT, RATE_SUBJECT, List.of(event));

        assertThat(command.toString())
                .doesNotContain(VISITOR_ID, SESSION_ID, USER_AGENT, RATE_SUBJECT)
                .contains("<redacted>");
        assertThat(event.toString())
                .doesNotContain(EVENT_ID.toString(), "github.com/private")
                .contains("<redacted>");
    }

    @Test
    void knownCrawlerConsumesOnlyRateLimitAndStoresNothing() {
        String subject = RATE_SUBJECT;
        CollectAnalyticsCommand crawler = new CollectAnalyticsCommand(
                null,
                null,
                "Example preview crawler/1.0",
                subject,
                List.of());

        collector.collect(crawler);

        verify(limiter).consume("public-events", subject);
        verifyNoInteractions(deduplicator);
    }

    @Test
    void deniedRateLimitDoesNotValidateOrPersistAndUsesBoundedRetry() {
        String subject = RATE_SUBJECT;
        when(limiter.consume("public-events", subject))
                .thenReturn(RateLimitDecision.deny(Long.MAX_VALUE));

        assertThatThrownBy(() -> collector.collect(new CollectAnalyticsCommand(
                        null, null, USER_AGENT, subject, null)))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("ANALYTICS_RATE_LIMITED");
                    assertThat(failure.fieldErrors())
                            .containsEntry("retryAfterSeconds", "9999999999");
                });

        verifyNoInteractions(deduplicator);
    }

    private CollectAnalyticsCommand command(
            String rateLimitSubject, CollectAnalyticsCommand.Event event) {
        return new CollectAnalyticsCommand(
                VISITOR_ID,
                SESSION_ID,
                USER_AGENT,
                rateLimitSubject,
                List.of(event));
    }

    private void assertInvalid(CollectAnalyticsCommand command) {
        assertThatThrownBy(() -> collector.collect(command))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("ANALYTICS_EVENT_INVALID"));
    }

    private static String browserId(int firstByte) {
        byte[] value = new byte[16];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (firstByte + index);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String browserIdBytes(int length) {
        return browserIdBytes(length, (byte) 1);
    }

    private static String browserIdBytes(int length, byte fill) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String nonCanonical(String canonical) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int last = alphabet.indexOf(canonical.charAt(canonical.length() - 1));
        return canonical.substring(0, canonical.length() - 1)
                + alphabet.charAt(last + 1);
    }

    private static String testSecret() {
        byte[] value = new byte[32];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) index;
        }
        return Base64.getEncoder().encodeToString(value);
    }
}
