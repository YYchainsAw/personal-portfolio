package xyz.yychainsaw.portfolio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.analytics.config.AnalyticsProperties;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;

class AnalyticsPrivacyServiceTest {
    private static final LocalDate SITE_DATE = LocalDate.of(2026, 7, 14);
    private static final String VISITOR_ID = "visitor-random-128-bit";
    private static final String SESSION_ID = "session-random-128-bit";
    private static final String EXPECTED_VISITOR_KEY =
            "27528edcda0addcc29e87b61ca776eca885c01edff7913b14f48ae8ea8a6bb4b";
    private static final String EXPECTED_SESSION_KEY =
            "6ff64265dbdbe1a56b2fee43aa95d80ff1a85a8fb70ff8e0054846b19db214d5";
    private static final UUID PROJECT_ID =
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void derivesTheExactPlannedHongKongDayHmacMaterial() {
        AnalyticsPrivacyService privacy = service();

        assertThat(privacy.visitorDayKey(SITE_DATE, VISITOR_ID))
                .isEqualTo(EXPECTED_VISITOR_KEY)
                .matches("[0-9a-f]{64}")
                .doesNotContain(VISITOR_ID);
        assertThat(privacy.sessionDayKey(SITE_DATE, SESSION_ID))
                .isEqualTo(EXPECTED_SESSION_KEY)
                .matches("[0-9a-f]{64}")
                .doesNotContain(SESSION_ID);
    }

    @Test
    void dailyKeysAreSeparatedByDateDomainAndIdentifier() {
        AnalyticsPrivacyService privacy = service();
        String visitor = privacy.visitorDayKey(SITE_DATE, VISITOR_ID);

        assertThat(privacy.visitorDayKey(SITE_DATE.plusDays(1), VISITOR_ID))
                .isNotEqualTo(visitor);
        assertThat(privacy.visitorDayKey(SITE_DATE, SESSION_ID)).isNotEqualTo(visitor);
        assertThat(privacy.sessionDayKey(SITE_DATE, VISITOR_ID)).isNotEqualTo(visitor);
    }

    @Test
    void derivesStableLengthFramedTupleLockKeys() {
        AnalyticsPrivacyService privacy = service();

        assertThat(privacy.dedupeLockKey(
                        EXPECTED_SESSION_KEY, AnalyticsEventType.PAGE_VIEW, "HOME", null))
                .isEqualTo(-4_089_070_885_838_710_128L);
        assertThat(privacy.dedupeLockKey(
                        EXPECTED_SESSION_KEY,
                        AnalyticsEventType.PAGE_VIEW,
                        "HOME",
                        PROJECT_ID))
                .isEqualTo(5_104_633_798_954_087_353L);

        long baseline = privacy.dedupeLockKey(
                EXPECTED_SESSION_KEY, AnalyticsEventType.PAGE_VIEW, "HOME", null);
        assertThat(privacy.dedupeLockKey(
                        EXPECTED_SESSION_KEY,
                        AnalyticsEventType.PROJECT_VIEW,
                        "HOME",
                        null))
                .isNotEqualTo(baseline);
        assertThat(privacy.dedupeLockKey(
                        EXPECTED_SESSION_KEY,
                        AnalyticsEventType.PAGE_VIEW,
                        "ABOUT",
                        null))
                .isNotEqualTo(baseline);
    }

    @Test
    void rejectsInvalidInputsWithoutEchoingTheirValues() {
        AnalyticsPrivacyService privacy = service();
        String raw = "visitor-secret-that-must-not-appear";

        assertThatThrownBy(() -> privacy.visitorDayKey(SITE_DATE, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("analytics browser identifier is invalid")
                .hasMessageNotContaining(raw);
        assertThatThrownBy(() -> privacy.dedupeLockKey(
                        raw, AnalyticsEventType.PAGE_VIEW, "HOME", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("analytics session day key is invalid")
                .hasMessageNotContaining(raw);
        assertThatThrownBy(() -> privacy.dedupeLockKey(
                        EXPECTED_SESSION_KEY, AnalyticsEventType.PAGE_VIEW, raw.repeat(7), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("analytics page key is invalid")
                .hasMessageNotContaining(raw);
    }

    @Test
    void absentDevelopmentSecretFailsOnlyWhenHashingIsRequested() {
        AnalyticsPrivacyService privacy =
                new AnalyticsPrivacyService(new AnalyticsProperties(""));

        assertThat(privacy.toString())
                .isEqualTo("AnalyticsPrivacyService[key=<redacted>]")
                .doesNotContain(VISITOR_ID, SESSION_ID);
        assertThatThrownBy(() -> privacy.visitorDayKey(SITE_DATE, VISITOR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("analytics HMAC secret is not configured")
                .hasMessageNotContaining(VISITOR_ID);
    }

    private static AnalyticsPrivacyService service() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        String encoded = Base64.getEncoder().encodeToString(key);
        return new AnalyticsPrivacyService(new AnalyticsProperties(encoded));
    }
}
