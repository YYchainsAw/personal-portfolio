package xyz.yychainsaw.portfolio.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StorageConfigurationCosPropertiesTest {
    private static final String REGION = "ap-guangzhou";
    private static final String BUCKET = "portfolio-1234567890";

    private Path safeBoundary;

    @AfterEach
    void removeSafeBoundary() throws IOException {
        if (safeBoundary == null || !Files.exists(safeBoundary)) {
            return;
        }
        try (var entries = Files.walk(safeBoundary)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    @Test
    void productionCosBeanConsumesCanonicalPropertiesIncludingSessionToken() {
        AtomicReference<TencentCosProperties> adapterProperties = new AtomicReference<>();

        canonicalRunner(adapterProperties).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TencentCosProperties.class);

            TencentCosProperties properties = context.getBean(TencentCosProperties.class);
            assertThat(properties.region()).isEqualTo(REGION);
            assertThat(properties.bucket()).isEqualTo(BUCKET);
            assertThat(properties.secretId()).isEqualTo("canonical-secret-id");
            assertThat(properties.secretKey()).isEqualTo("canonical-secret-key");
            assertThat(properties.sessionToken()).isEqualTo("canonical-session-token");
            assertThat(adapterProperties.get()).isSameAs(properties);
        });
    }

    @Test
    void rawEnvironmentStyleKeysCannotBypassCanonicalCosProperties() {
        AtomicReference<TencentCosProperties> adapterProperties = new AtomicReference<>();

        baseRunner(adapterProperties)
                .withPropertyValues(
                        "COS_REGION=" + REGION,
                        "COS_BUCKET=" + BUCKET,
                        "COS_SECRET_ID=legacy-secret-id",
                        "COS_SECRET_KEY=legacy-secret-key",
                        "COS_SESSION_TOKEN=legacy-session-token")
                .run(context -> assertThat(context).hasFailed());

        assertThat(adapterProperties).hasNullValue();
    }

    private ApplicationContextRunner canonicalRunner(
            AtomicReference<TencentCosProperties> adapterProperties) {
        return baseRunner(adapterProperties).withPropertyValues(
                "portfolio.storage.cos.region=" + REGION,
                "portfolio.storage.cos.bucket=" + BUCKET,
                "portfolio.storage.cos.secret-id=canonical-secret-id",
                "portfolio.storage.cos.secret-key=canonical-secret-key",
                "portfolio.storage.cos.session-token=canonical-session-token");
    }

    private ApplicationContextRunner baseRunner(
            AtomicReference<TencentCosProperties> adapterProperties) {
        Path boundary = safeBoundary();
        Path localRoot = boundary.resolve("local-media");
        Path scratchRoot = boundary.resolve("cos-scratch");
        QcloudCosClientAdapter adapter = mock(QcloudCosClientAdapter.class);
        CosAdapterFactory adapterFactory = properties -> {
            adapterProperties.set(properties);
            return adapter;
        };
        return new ApplicationContextRunner()
                .withUserConfiguration(StorageConfiguration.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(
                        CosSdkLogSilencer.class,
                        () -> new CosSdkLogSilencer((loggerName, level) -> {}))
                .withBean(CosAdapterFactory.class, () -> adapterFactory)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "portfolio.storage.default-provider=TENCENT_COS",
                        "portfolio.storage.local.root=" + localRoot,
                        "portfolio.storage.cos.staging-root=" + scratchRoot);
    }

    private Path safeBoundary() {
        if (safeBoundary != null) {
            return safeBoundary;
        }
        try {
            Path home = Path.of(System.getProperty("user.home"))
                    .toAbsolutePath()
                    .normalize();
            safeBoundary = Files.createTempDirectory(home, ".portfolio-cos-config-test-");
            return safeBoundary;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
