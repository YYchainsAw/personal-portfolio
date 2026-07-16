package xyz.yychainsaw.portfolio.media.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public interface StorageService {
    StorageProvider provider();

    StorageLocation location();

    StoredObject put(String objectKey, InputStream input, long contentLength, String contentType);

    StorageRead open(String objectKey, Optional<ByteRange> range);

    URI signedGet(String objectKey, Duration ttl);

    boolean exists(String objectKey);

    void copy(String sourceKey, String targetKey);

    void delete(String objectKey);
}
