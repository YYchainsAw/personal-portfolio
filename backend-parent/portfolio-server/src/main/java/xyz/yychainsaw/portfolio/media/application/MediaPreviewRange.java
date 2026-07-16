package xyz.yychainsaw.portfolio.media.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import xyz.yychainsaw.portfolio.media.storage.ByteRange;

public record MediaPreviewRange(
        boolean rangeHeaderPresent,
        Optional<ByteRange> byteRange,
        String strongEtag) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SINGLE_BYTES_RANGE =
            Pattern.compile("bytes=([0-9]*)-([0-9]*)");

    public MediaPreviewRange {
        Objects.requireNonNull(byteRange, "byteRange");
        Objects.requireNonNull(strongEtag, "strongEtag");
        if (!rangeHeaderPresent && byteRange.isPresent()) {
            throw new IllegalArgumentException("A byte range requires a Range header");
        }
        if (!strongEtag.matches("\"[0-9a-f]{64}\"")) {
            throw new IllegalArgumentException("Invalid strong ETag");
        }
    }

    public static MediaPreviewRange parse(
            HttpHeaders headers,
            long totalLength,
            String sha256) {
        Objects.requireNonNull(headers, "headers");
        if (totalLength < 0) {
            throw new IllegalArgumentException("Invalid media total length");
        }
        String strongEtag = quoteStrongEtag(sha256);
        List<String> rangeValues = headers.get(HttpHeaders.RANGE);
        if (rangeValues == null) {
            return new MediaPreviewRange(false, Optional.empty(), strongEtag);
        }
        if (rangeValues.size() != 1 || rangeValues.get(0) == null) {
            throw notSatisfiable(totalLength);
        }

        ByteRange parsed = parseSingleRange(rangeValues.get(0), totalLength);
        if (!ifRangeAllowsPartialResponse(headers, strongEtag)) {
            return new MediaPreviewRange(true, Optional.empty(), strongEtag);
        }
        return new MediaPreviewRange(true, Optional.of(parsed), strongEtag);
    }

    private static String quoteStrongEtag(String sha256) {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Invalid media SHA-256");
        }
        return '"' + sha256 + '"';
    }

    private static ByteRange parseSingleRange(String value, long totalLength) {
        Matcher matcher = SINGLE_BYTES_RANGE.matcher(value);
        if (!matcher.matches()) {
            throw notSatisfiable(totalLength);
        }
        String startText = matcher.group(1);
        String endText = matcher.group(2);
        if (startText.isEmpty() && endText.isEmpty()) {
            throw notSatisfiable(totalLength);
        }

        if (startText.isEmpty()) {
            long suffixLength = parseNumber(endText, totalLength);
            if (suffixLength == 0 || totalLength == 0) {
                throw notSatisfiable(totalLength);
            }
            long start = suffixLength >= totalLength ? 0 : totalLength - suffixLength;
            return new ByteRange(start, totalLength - 1);
        }

        long start = parseNumber(startText, totalLength);
        if (start >= totalLength) {
            throw notSatisfiable(totalLength);
        }
        long end = totalLength - 1;
        if (!endText.isEmpty()) {
            long requestedEnd = parseNumber(endText, totalLength);
            if (requestedEnd < start) {
                throw notSatisfiable(totalLength);
            }
            end = Math.min(requestedEnd, end);
        }
        return new ByteRange(start, end);
    }

    private static long parseNumber(String value, long totalLength) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw notSatisfiable(totalLength);
        }
    }

    private static boolean ifRangeAllowsPartialResponse(
            HttpHeaders headers,
            String strongEtag) {
        List<String> values = headers.get(HttpHeaders.IF_RANGE);
        return values == null
                || values.size() == 1 && strongEtag.equals(values.get(0));
    }

    private static MediaRangeNotSatisfiableException notSatisfiable(long totalLength) {
        return new MediaRangeNotSatisfiableException(totalLength);
    }
}
