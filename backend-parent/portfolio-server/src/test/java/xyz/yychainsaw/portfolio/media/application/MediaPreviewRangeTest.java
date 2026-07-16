package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import xyz.yychainsaw.portfolio.media.storage.ByteRange;

class MediaPreviewRangeTest {
    private static final String SHA256 = "a".repeat(64);

    @Test
    void noRangeHeaderSelectsTheFullRepresentationAndQuotesTheStrongEtag() {
        MediaPreviewRange selection = MediaPreviewRange.parse(
                new HttpHeaders(), 10, SHA256);

        assertThat(selection.rangeHeaderPresent()).isFalse();
        assertThat(selection.byteRange()).isEmpty();
        assertThat(selection.strongEtag()).isEqualTo('"' + SHA256 + '"');
    }

    @Test
    void closedRangeIsSelectedAndAnOversizedEndIsClamped() {
        assertThat(parse("bytes=2-5", 10).byteRange())
                .contains(new ByteRange(2, 5));
        assertThat(parse("bytes=8-99", 10).byteRange())
                .contains(new ByteRange(8, 9));
    }

    @Test
    void openEndedRangeRunsThroughTheLastAvailableByte() {
        MediaPreviewRange selection = parse("bytes=3-", 10);

        assertThat(selection.rangeHeaderPresent()).isTrue();
        assertThat(selection.byteRange()).contains(new ByteRange(3, 9));
    }

    @Test
    void suffixRangeSelectsTheRequestedTailAndClampsToTheWholeObject() {
        assertThat(parse("bytes=-3", 10).byteRange())
                .contains(new ByteRange(7, 9));
        assertThat(parse("bytes=-20", 10).byteRange())
                .contains(new ByteRange(0, 9));
    }

    @Test
    void exactStrongIfRangeAppliesTheRange() {
        HttpHeaders headers = rangeHeaders("bytes=1-2");
        headers.set(HttpHeaders.IF_RANGE, '"' + SHA256 + '"');

        assertThat(MediaPreviewRange.parse(headers, 10, SHA256).byteRange())
                .contains(new ByteRange(1, 2));
    }

    @Test
    void weakDateMismatchedOrAmbiguousIfRangeSelectsTheFullRepresentation() {
        List<String> validators = List.of(
                "W/\"" + SHA256 + "\"",
                '"' + "b".repeat(64) + '"',
                "Wed, 21 Oct 2015 07:28:00 GMT");

        for (String validator : validators) {
            HttpHeaders headers = rangeHeaders("bytes=1-2");
            headers.set(HttpHeaders.IF_RANGE, validator);

            MediaPreviewRange selection = MediaPreviewRange.parse(headers, 10, SHA256);

            assertThat(selection.rangeHeaderPresent()).isTrue();
            assertThat(selection.byteRange()).isEmpty();
        }

        HttpHeaders ambiguous = rangeHeaders("bytes=1-2");
        ambiguous.add(HttpHeaders.IF_RANGE, '"' + SHA256 + '"');
        ambiguous.add(HttpHeaders.IF_RANGE, '"' + SHA256 + '"');
        assertThat(MediaPreviewRange.parse(ambiguous, 10, SHA256).byteRange())
                .isEmpty();
    }

    @Test
    void malformedMultipleOverflowingAndUnsatisfiableRangesUseOneFixedException() {
        List<String> rejected = List.of(
                "bytes=0-1,2-3",
                "items=0-1",
                "bytes=",
                "bytes=-",
                "bytes= 0-1",
                "bytes=+1-2",
                "bytes=5-4",
                "bytes=-0",
                "bytes=10-",
                "bytes=9223372036854775808-",
                "bytes=0-9223372036854775808",
                "bytes=-9223372036854775808");

        for (String value : rejected) {
            assertRangeRejected(rangeHeaders(value), 10);
        }

        HttpHeaders repeated = new HttpHeaders();
        repeated.add(HttpHeaders.RANGE, "bytes=0-1");
        repeated.add(HttpHeaders.RANGE, "bytes=2-3");
        assertRangeRejected(repeated, 10);
    }

    @Test
    void anEmptyRepresentationCannotSatisfyAnyRange() {
        assertRangeRejected(rangeHeaders("bytes=0-0"), 0);
        assertRangeRejected(rangeHeaders("bytes=-1"), 0);
    }

    @Test
    void invalidObjectMetadataIsRejectedBeforeParsingHeaders() {
        assertThatThrownBy(() -> MediaPreviewRange.parse(new HttpHeaders(), -1, SHA256))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid media total length");
        assertThatThrownBy(() -> MediaPreviewRange.parse(
                        new HttpHeaders(), 10, "A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid media SHA-256");
        assertThatThrownBy(() -> MediaPreviewRange.parse(
                        new HttpHeaders(), 10, "a".repeat(63) + "\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid media SHA-256");
    }

    private static MediaPreviewRange parse(String range, long totalLength) {
        return MediaPreviewRange.parse(rangeHeaders(range), totalLength, SHA256);
    }

    private static HttpHeaders rangeHeaders(String range) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RANGE, range);
        return headers;
    }

    private static void assertRangeRejected(HttpHeaders headers, long totalLength) {
        assertThatThrownBy(() -> MediaPreviewRange.parse(headers, totalLength, SHA256))
                .isInstanceOf(MediaRangeNotSatisfiableException.class)
                .hasMessage("Media byte range is not satisfiable")
                .satisfies(exception -> assertThat(
                                ((MediaRangeNotSatisfiableException) exception).totalLength())
                        .isEqualTo(totalLength));
    }
}
