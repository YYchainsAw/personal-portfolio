package xyz.yychainsaw.portfolio.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

class StorageRouterTest {
    @Test
    void routesTheDefaultWriterAndExplicitProvidersIndependently() {
        StubStorageService local = new StubStorageService(StorageProvider.LOCAL);
        StubStorageService cos = new StubStorageService(StorageProvider.TENCENT_COS);
        DefaultStorageRouter localDefault = new DefaultStorageRouter(
                List.of(local, cos), new StorageDefaults(StorageProvider.LOCAL));
        DefaultStorageRouter cosDefault = new DefaultStorageRouter(
                List.of(local, cos), new StorageDefaults(StorageProvider.TENCENT_COS));

        assertThat(localDefault.defaultWriter()).isSameAs(local);
        assertThat(cosDefault.defaultWriter()).isSameAs(cos);
        assertThat(localDefault.require(StorageProvider.TENCENT_COS)).isSameAs(cos);
        assertThat(cosDefault.require(StorageProvider.LOCAL)).isSameAs(local);
    }

    @Test
    void rejectsDuplicateProvidersAtConstruction() {
        assertThatThrownBy(() -> new DefaultStorageRouter(
                List.of(
                        new StubStorageService(StorageProvider.LOCAL),
                        new StubStorageService(StorageProvider.LOCAL)),
                new StorageDefaults(StorageProvider.LOCAL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate storage provider");
    }

    @Test
    void rejectsAMissingConfiguredDefaultAtConstruction() {
        assertThatThrownBy(() -> new DefaultStorageRouter(
                List.of(new StubStorageService(StorageProvider.LOCAL)),
                new StorageDefaults(StorageProvider.TENCENT_COS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Default storage provider is not configured");
    }

    @Test
    void rejectsNullCollectionsServicesProvidersAndDefaultsWithFixedMessages() {
        assertThatThrownBy(() -> new DefaultStorageRouter(
                null, new StorageDefaults(StorageProvider.LOCAL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Storage services are required");
        List<StorageService> nullService = new ArrayList<>();
        nullService.add(null);
        assertThatThrownBy(() -> new DefaultStorageRouter(
                nullService, new StorageDefaults(StorageProvider.LOCAL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Storage service is required");
        assertThatThrownBy(() -> new DefaultStorageRouter(
                List.of(new StubStorageService(null)), new StorageDefaults(StorageProvider.LOCAL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Storage service provider is required");
        assertThatThrownBy(() -> new DefaultStorageRouter(
                List.of(new StubStorageService(StorageProvider.LOCAL)), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Storage defaults are required");
    }

    @Test
    void rejectsMissingOrNullExplicitProviderWithFixedMessages() {
        DefaultStorageRouter router = new DefaultStorageRouter(
                List.of(new StubStorageService(StorageProvider.LOCAL)),
                new StorageDefaults(StorageProvider.LOCAL));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> router.require(null))
                .withMessage("Storage provider is required");
        assertThatThrownBy(() -> router.require(StorageProvider.TENCENT_COS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Storage provider is not configured");
    }

    @Test
    void routerDefensivelyCopiesTheServiceCollection() {
        StubStorageService local = new StubStorageService(StorageProvider.LOCAL);
        List<StorageService> services = new ArrayList<>();
        services.add(local);
        DefaultStorageRouter router = new DefaultStorageRouter(
                services, new StorageDefaults(StorageProvider.LOCAL));

        services.clear();

        assertThat(router.defaultWriter()).isSameAs(local);
    }

    @Test
    void storageDefaultsRejectNullWhileLocalPropertiesMaterializeTheirSafeDefault() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StorageDefaults(null))
                .withMessage("Default storage provider is required");
        assertThat(new LocalStorageProperties(null).root())
                .isEqualTo(Path.of("../runtime/media"));
    }

    @Test
    void localStoragePropertiesBindTheSafeDefaultWithoutExternalConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(LocalStoragePropertiesConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalStorageProperties.class);
                    assertThat(context.getBean(LocalStorageProperties.class).root())
                            .isEqualTo(Path.of("../runtime/media"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LocalStorageProperties.class)
    private static class LocalStoragePropertiesConfiguration {}

    private static final class StubStorageService implements StorageService {
        private final StorageProvider provider;

        private StubStorageService(StorageProvider provider) {
            this.provider = provider;
        }

        @Override
        public StorageProvider provider() {
            return provider;
        }

        @Override
        public StorageLocation location() {
            if (provider == StorageProvider.LOCAL) {
                return new StorageLocation(StorageProvider.LOCAL, null, null);
            }
            if (provider == StorageProvider.TENCENT_COS) {
                return new StorageLocation(
                        StorageProvider.TENCENT_COS,
                        "portfolio-1234567890",
                        "ap-guangzhou");
            }
            return null;
        }

        @Override
        public StoredObject put(
                String objectKey, InputStream input, long contentLength, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StorageRead open(String objectKey, Optional<ByteRange> range) {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI signedGet(String objectKey, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copy(String sourceKey, String targetKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String objectKey) {
            throw new UnsupportedOperationException();
        }
    }
}
