package xyz.yychainsaw.portfolio.media.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public record StorageRead(
        InputStream inputStream,
        long totalLength,
        Optional<ByteRange> range,
        long contentLength,
        String contentType,
        String etag) implements AutoCloseable {

    public StorageRead {
        if (inputStream == null) {
            throw new IllegalArgumentException("Storage input is required");
        }
        StorageObjectContract.validateContentLength(totalLength);
        if (range == null) {
            throw new IllegalArgumentException("Storage range is required");
        }
        if (contentLength <= 0 || contentLength > totalLength) {
            throw new IllegalArgumentException("Invalid storage response length");
        }
        if (range.isEmpty()) {
            if (contentLength != totalLength) {
                throw new IllegalArgumentException("Invalid storage response length");
            }
        } else {
            ByteRange served = range.orElseThrow();
            if (served.endInclusive() >= totalLength || served.length() != contentLength) {
                throw new IllegalArgumentException("Invalid storage response range");
            }
        }
        contentType = StorageObjectContract.normalizeContentType(contentType);
        etag = requireText(etag, "Storage ETag is required");
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
