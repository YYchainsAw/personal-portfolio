package xyz.yychainsaw.portfolio.api.admin.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StrictHttpsSourceUrlTest {

    @Test
    void acceptsNullAndAWellFormedHttpsUrlWithoutChangingIt() {
        String sourceUrl = "https://example.com:443/path?from=portfolio";

        assertThat(StrictHttpsSourceUrl.requireValidNullable(null)).isNull();
        assertThat(StrictHttpsSourceUrl.requireValidNullable(sourceUrl))
                .isEqualTo(sourceUrl);
        assertThat(StrictHttpsSourceUrl.requireValidNullable("https://1.2.3.4/"))
                .isEqualTo("https://1.2.3.4/");
        assertThat(StrictHttpsSourceUrl.requireValidNullable("https://example.com./source"))
                .isEqualTo("https://example.com./source");
    }

    @Test
    void rejectsUnsafeOrAmbiguousHttpsUrlsWithOneStableError() {
        for (String sourceUrl : List.of(
                "http://example.com/path",
                "https:///missing-host",
                "https://user:secret@example.com/path#private",
                "https://example.com:0/path",
                "https://example.com:65536/path",
                "https://example.com:/path",
                "https://example.com:000443/",
                "https://example.com:000001/",
                "https://[fe80::1%25eth0]/",
                "https://01.02.03.04/",
                "https://" + "a".repeat(64) + ".example/",
                "https://" + ("a".repeat(63) + ".").repeat(3)
                        + "a".repeat(63) + "/",
                "https://example.com/path#private",
                "https://example.com/#",
                "https://example.com#",
                " https://example.com/path")) {
            assertThatThrownBy(
                            () -> StrictHttpsSourceUrl.requireValidNullable(sourceUrl))
                    .as("source URL %s", sourceUrl)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("media source URL is invalid")
                    .hasNoCause();
        }
    }
}
