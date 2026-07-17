package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;

class HttpEtagTest {
    private static final String CHECKSUM =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void apiEtagUsesTheExactUtf8ChecksumNewlineLocaleVector() {
        assertThat(HttpEtag.api(CHECKSUM, LocaleCode.EN))
                .isEqualTo("\"61b813898c02a97e7b8b3372ee431fbc8bbece6760430547c78a5ba864761ce8\"");
        assertThat(HttpEtag.api(CHECKSUM, LocaleCode.ZH_CN))
                .isEqualTo("\"b0e76b4e2492ca9b10e72e66f9b04197c33015b08fafe24573e9bf08f261a50b\"");
    }

    @Test
    void apiEtagRejectsNonCanonicalRevisionChecksumBoundaries() {
        for (String invalid : new String[] {
                "A".repeat(64),
                "a".repeat(63),
                "a".repeat(65),
                "a".repeat(62) + "\r\n"
        }) {
            assertThatThrownBy(() -> HttpEtag.api(invalid, LocaleCode.EN))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> HttpEtag.api(null, LocaleCode.EN))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void apiEtagRejectsNullLocaleWithoutDependingOnExceptionImplementation() {
        assertThatThrownBy(() -> HttpEtag.api(CHECKSUM, null))
                .isInstanceOf(RuntimeException.class);
    }
}
