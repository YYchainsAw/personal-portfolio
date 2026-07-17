package xyz.yychainsaw.portfolio.media.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.junit.jupiter.api.Test;

abstract class AbstractStorageContractTest {

    abstract StorageService storage();

    String objectKey(String relativeKey) {
        return relativeKey;
    }

    @Test
    void putOpenCopyDeleteRoundTrips() throws Exception {
        StorageService adapter = storage();
        byte[] bytes = "contract".getBytes(UTF_8);
        String sourceKey = objectKey("contract/source.txt");
        String copyKey = objectKey("contract/copy.txt");

        StoredObject stored = adapter.put(
                sourceKey,
                new ByteArrayInputStream(bytes),
                bytes.length,
                "text/plain");

        assertThat(stored.objectKey()).isEqualTo(sourceKey);
        assertThat(stored.contentLength()).isEqualTo(bytes.length);
        assertThat(stored.contentType()).isEqualTo("text/plain");
        assertThat(adapter.exists(sourceKey)).isTrue();

        adapter.copy(sourceKey, copyKey);
        assertThat(adapter.exists(copyKey)).isTrue();
        try (StorageRead read = adapter.open(copyKey, Optional.empty())) {
            assertThat(read.inputStream().readAllBytes()).isEqualTo(bytes);
            assertThat(read.totalLength()).isEqualTo(bytes.length);
            assertThat(read.range()).isEmpty();
            assertThat(read.contentLength()).isEqualTo(bytes.length);
            assertThat(read.contentType()).isEqualTo("text/plain");
            assertThat(read.etag()).isNotBlank();
        }

        adapter.delete(sourceKey);
        adapter.delete(copyKey);
        assertThat(adapter.exists(sourceKey)).isFalse();
        assertThat(adapter.exists(copyKey)).isFalse();
    }
}
