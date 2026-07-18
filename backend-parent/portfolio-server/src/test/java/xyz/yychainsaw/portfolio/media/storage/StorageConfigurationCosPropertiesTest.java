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
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

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

    @Test
    void explicitLocalOnlyProductionDoesNotCreateOrRequireCosBeans() {
        localOnlyRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LocalStorageService.class);
            assertThat(context).doesNotHaveBean(TencentCosProperties.class);
            assertThat(context).doesNotHaveBean(QcloudCosClientAdapter.class);
            assertThat(context).doesNotHaveBean(TencentCosStorageService.class);
            assertThat(context).doesNotHaveBean(CosSdkLogSilencer.class);

            StorageRouter router = context.getBean(StorageRouter.class);
            assertThat(router.defaultWriter().provider()).isEqualTo(StorageProvider.LOCAL);
        });
    }

    @Test
    void explicitCosAdapterKeepsMixedProductionAvailableWithLocalDefault() {
        AtomicReference<TencentCosProperties> adapterProperties = new AtomicReference<>();

        canonicalRunner(adapterProperties)
                .withPropertyValues(
                        "portfolio.storage.default-provider=LOCAL",
                        "portfolio.storage.cos.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalStorageService.class);
                    assertThat(context).hasSingleBean(TencentCosStorageService.class);
                    assertThat(adapterProperties).doesNotHaveNullValue();

                    StorageRouter router = context.getBean(StorageRouter.class);
                    assertThat(router.defaultWriter().provider())
                            .isEqualTo(StorageProvider.LOCAL);
                    assertThat(router.require(StorageProvider.TENCENT_COS).provider())
                            .isEqualTo(StorageProvider.TENCENT_COS);
                });
    }

    @Test
    void cosDefaultCreatesItsRequiredAdapterEvenWhenOptionalFlagIsFalse() {
        AtomicReference<TencentCosProperties> adapterProperties = new AtomicReference<>();

        canonicalRunner(adapterProperties)
                .withPropertyValues("portfolio.storage.cos.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TencentCosStorageService.class);
                    assertThat(adapterProperties).doesNotHaveNullValue();
                    assertThat(context.getBean(StorageRouter.class)
                                    .defaultWriter()
                                    .provider())
                            .isEqualTo(StorageProvider.TENCENT_COS);
                });
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

    private ApplicationContextRunner localOnlyRunner() {
        Path boundary = safeBoundary();
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(StorageConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "PORTFOLIO_RELEASE_ID=local-only-test",
                        "PORTFOLIO_STORAGE_DEFAULT_PROVIDER=LOCAL",
                        "PORTFOLIO_COS_ENABLED=false",
                        "PORTFOLIO_LOCAL_STORAGE=" + boundary.resolve("local-media"),
                        "PORTFOLIO_JOBS_WORKER_ENABLED=false",
                        "PORTFOLIO_STAGING_CLEANUP_ENABLED=false",
                        "PORTFOLIO_MEDIA_CLEANUP_ENABLED=false");
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
