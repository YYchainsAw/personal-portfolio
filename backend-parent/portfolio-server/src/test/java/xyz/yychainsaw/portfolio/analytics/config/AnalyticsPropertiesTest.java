package xyz.yychainsaw.portfolio.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AnalyticsPropertiesTest {
    @Test
    void decodesCanonicalStandardBase64AndReturnsDefensiveCopies() {
        byte[] secret = secretBytes();
        String encoded = Base64.getEncoder().encodeToString(secret);
        AnalyticsProperties properties = new AnalyticsProperties(encoded);

        byte[] first = properties.hmacSecret();
        first[0] ^= 0x7f;

        assertThat(properties.configured()).isTrue();
        assertThat(properties.hmacSecret()).containsExactly(secret);
        assertThat(properties.hmacSecret()).isNotSameAs(properties.hmacSecret());
    }

    @Test
    void permitsAnAbsentSecretForNonProductionContexts() {
        AnalyticsProperties absent = new AnalyticsProperties(null);
        AnalyticsProperties empty = new AnalyticsProperties("");
        AnalyticsProperties blank = new AnalyticsProperties("   ");

        assertThat(absent.configured()).isFalse();
        assertThat(empty.configured()).isFalse();
        assertThat(blank.configured()).isFalse();
        assertThat(absent.hmacSecret()).isEmpty();
        assertThat(empty.hmacSecret()).isEmpty();
        assertThat(blank.hmacSecret()).isEmpty();
    }

    @Test
    void rejectsMalformedNonCanonicalAndShortConfiguredSecrets() {
        byte[] secret = secretBytes();
        String canonical = Base64.getEncoder().encodeToString(secret);
        String unpadded = canonical.substring(0, canonical.length() - 1);
        String urlEncoded = Base64.getUrlEncoder().encodeToString(secret);
        String shortSecret = Base64.getEncoder()
                .encodeToString(Arrays.copyOf(secret, secret.length - 1));

        for (String invalid : new String[] {
            "not-base64!", unpadded, urlEncoded, shortSecret, canonical + "="
        }) {
            assertThatThrownBy(() -> new AnalyticsProperties(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("analytics HMAC secret is invalid")
                    .hasMessageNotContaining(invalid);
        }
    }

    @Test
    void lifecycleClearsTheOwnedSecretAndToStringIsRedacted() throws Exception {
        byte[] secret = secretBytes();
        String encoded = Base64.getEncoder().encodeToString(secret);
        AnalyticsProperties properties = new AnalyticsProperties(encoded);

        assertThat(properties.toString())
                .isEqualTo("AnalyticsProperties[hmacSecret=<redacted>]")
                .doesNotContain(encoded);

        Method destroy = AnalyticsProperties.class.getDeclaredMethod("destroySecret");
        destroy.setAccessible(true);
        destroy.invoke(properties);

        assertThat(properties.hmacSecret()).containsOnly((byte) 0);
    }

    @Test
    void productionValidatorFailsClosedWithoutPrintingSecretMaterial() {
        String encoded = Base64.getEncoder().encodeToString(secretBytes());

        new AnalyticsProductionConfigurationValidator(new AnalyticsProperties(encoded));
        assertThatThrownBy(() -> new AnalyticsProductionConfigurationValidator(
                        new AnalyticsProperties("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("analytics HMAC secret must be configured in production")
                .hasMessageNotContaining(encoded);
    }

    private static byte[] secretBytes() {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0xfb);
        return secret;
    }
}
