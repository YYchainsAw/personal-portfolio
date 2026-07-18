package xyz.yychainsaw.portfolio.analytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class AnalyticsRateLimitSubjectHasherTest {
    private static final String ADDRESS = "203.0.113.9";
    private static final String EXPECTED =
            "ff7c4dbe21ac73b67779fe416dd4c4f32b7da6ce839d295d54a2f471a8d663d3";

    @Test
    void hashesCanonicalAddressesWithThePublicEventsNamespace() {
        byte[] key = key();
        AnalyticsRateLimitSubjectHasher hasher = new AnalyticsRateLimitSubjectHasher(key);
        key[0] ^= 0x7f;

        assertThat(hasher.hash(ADDRESS))
                .isEqualTo(EXPECTED)
                .matches("[0-9a-f]{64}")
                .doesNotContain(ADDRESS);
        assertThat(hasher.hash(ADDRESS)).isEqualTo(EXPECTED);
        assertThat(hasher.hash("203.0.113.10")).isNotEqualTo(EXPECTED);
    }

    @Test
    void independentDefaultInstancesUseIndependentEphemeralKeys() {
        AnalyticsRateLimitSubjectHasher first = new AnalyticsRateLimitSubjectHasher();
        AnalyticsRateLimitSubjectHasher second = new AnalyticsRateLimitSubjectHasher();

        assertThat(first.hash(ADDRESS)).isNotEqualTo(second.hash(ADDRESS));
    }

    @Test
    void rejectsInvalidKeysAndAddressesWithoutEchoingAddressMaterial() {
        assertThatThrownBy(() -> new AnalyticsRateLimitSubjectHasher(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rate-limit HMAC key must contain 256 bits");

        AnalyticsRateLimitSubjectHasher hasher =
                new AnalyticsRateLimitSubjectHasher(key());
        String overlong = "a".repeat(65);
        String malformedUtf16 = "\ud800";
        assertThatThrownBy(() -> hasher.hash(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("analytics rate-limit subject hashing failed");
        for (String invalid : new String[] {overlong, malformedUtf16}) {
            assertThatThrownBy(() -> hasher.hash(invalid))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("analytics rate-limit subject hashing failed")
                    .hasMessageNotContaining(invalid);
        }
        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("analytics rate-limit subject hashing failed");
    }

    @Test
    void lifecycleClearsTheKeyAndToStringIsRedacted() throws Exception {
        AnalyticsRateLimitSubjectHasher hasher =
                new AnalyticsRateLimitSubjectHasher(key());

        assertThat(hasher.toString())
                .isEqualTo("AnalyticsRateLimitSubjectHasher[key=<redacted>]")
                .doesNotContain(ADDRESS, EXPECTED);

        Method destroy = AnalyticsRateLimitSubjectHasher.class.getDeclaredMethod("destroyKey");
        destroy.setAccessible(true);
        destroy.invoke(hasher);

        assertThat(hasher.hash(ADDRESS)).isNotEqualTo(EXPECTED);
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        return key;
    }
}
