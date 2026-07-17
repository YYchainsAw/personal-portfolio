package xyz.yychainsaw.portfolio.media.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;

public interface CosClientPort {
    StoredObject putCreateOnly(
            String bucket,
            String key,
            InputStream input,
            long contentLength,
            String contentType);

    StorageRead open(String bucket, String key, Optional<ByteRange> range);

    URI signGet(String bucket, String key, Instant expiresAt);

    boolean exists(String bucket, String key);

    void copyCreateOnly(String bucket, String sourceKey, String targetKey);

    void delete(String bucket, String key);
}
