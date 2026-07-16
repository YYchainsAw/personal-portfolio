package xyz.yychainsaw.portfolio.media.storage;

import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;

final class StorageObjectContract {
    private static final long MAX_CONTENT_LENGTH = 5L * 1024 * 1024 * 1024;
    private static final String OCTET_STREAM = MediaType.APPLICATION_OCTET_STREAM_VALUE;

    private StorageObjectContract() {}

    static void validateContentLength(long contentLength) {
        if (contentLength <= 0 || contentLength > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("Invalid storage content length");
        }
    }

    static String normalizeContentType(ObjectKey key, String contentType) {
        if (contentType == null
                || contentType.isBlank()
                || contentType.indexOf('\r') >= 0
                || contentType.indexOf('\n') >= 0) {
            throw invalidContentType();
        }
        try {
            MediaType declared = MediaType.parseMediaType(contentType.trim());
            MediaType expected = MediaType.parseMediaType(contentType(key));
            if (declared.isWildcardType()
                    || declared.isWildcardSubtype()
                    || !declared.equals(expected)) {
                throw invalidContentType();
            }
            return declared.toString();
        } catch (IllegalArgumentException exception) {
            throw invalidContentType();
        }
    }

    static String contentType(ObjectKey key) {
        String[] segments = key.segments();
        return MediaTypeFactory.getMediaType(segments[segments.length - 1])
                .map(MediaType::toString)
                .orElse(OCTET_STREAM);
    }

    static void validateRange(Optional<ByteRange> range, long totalLength) {
        if (range.isEmpty()) {
            return;
        }
        ByteRange requested = range.orElseThrow();
        if (requested.startInclusive() >= totalLength || requested.endInclusive() >= totalLength) {
            throw new StorageRangeNotSatisfiableException(totalLength);
        }
    }

    private static IllegalArgumentException invalidContentType() {
        return new IllegalArgumentException("Invalid storage content type");
    }
}
