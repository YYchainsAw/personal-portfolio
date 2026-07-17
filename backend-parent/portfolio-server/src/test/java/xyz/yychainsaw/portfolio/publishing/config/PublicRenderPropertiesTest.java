package xyz.yychainsaw.portfolio.publishing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PublicRenderPropertiesTest {
    private static final String MANIFEST =
            "classpath:/public-assets/.vite/manifest.json";

    @Test
    void exposesValidatedImmutableRenderConfiguration() {
        PublicRenderProperties properties = new PublicRenderProperties(
                "abc123-manifest456",
                MANIFEST,
                3,
                URI.create("HTTPS://portfolio.example.test:8443/"));

        assertThat(properties.releaseId()).isEqualTo("abc123-manifest456");
        assertThat(properties.viteManifest()).isEqualTo(MANIFEST);
        assertThat(properties.templateSchemaVersion()).isEqualTo(3);
        assertThat(properties.publicBaseUrl())
                .isEqualTo(URI.create("https://portfolio.example.test:8443"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "UPPERCASE", "-release", "release/one", "release\none"})
    void rejectsInvalidReleaseIds(String releaseId) {
        assertThatThrownBy(() -> new PublicRenderProperties(
                releaseId, MANIFEST, 1, URI.create("https://example.test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("public render release id is invalid");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            " ",
            "vite/manifest.json",
            "classpath:vite/manifest.json",
            "classpath:/vite/../manifest.json",
            "classpath:/vite/%252e%252e/manifest.json",
            "classpath:/vite/%20/manifest.json",
            "classpath:/vite/my manifest.json",
            "https://example.test/manifest.json",
            "file:manifest.json",
            "file://server/share/manifest.json",
            "file:/opt/portfolio/../manifest.json"
    })
    void rejectsUnsafeManifestLocations(String location) {
        assertThatThrownBy(() -> new PublicRenderProperties(
                "release-1", location, 1, URI.create("https://example.test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vite manifest location is invalid");
    }

    @Test
    void permitsAbsoluteLocalManifestOverride() {
        PublicRenderProperties properties = new PublicRenderProperties(
                "release-1",
                "file:/opt/portfolio/public-assets/.vite/manifest.json",
                1,
                URI.create("https://example.test"));

        assertThat(properties.viteManifest())
                .isEqualTo("file:/opt/portfolio/public-assets/.vite/manifest.json");
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0})
    void rejectsNonPositiveTemplateSchemaVersions(int version) {
        assertThatThrownBy(() -> new PublicRenderProperties(
                "release-1", MANIFEST, version, URI.create("https://example.test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("public render template schema version must be positive");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.test",
            "https://user@example.test",
            "https://example.test/path",
            "https://example.test?query=yes",
            "https://example.test#fragment",
            "https://example.test:0",
            "https://example.test:65536",
            "//example.test",
            "mailto:owner@example.test"
    })
    void rejectsValuesThatAreNotHttpsOrigins(String value) {
        assertThatThrownBy(() -> new PublicRenderProperties(
                "release-1", MANIFEST, 1, URI.create(value)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("public base URL must be an HTTPS origin");
    }

    @Test
    void requiresPublicBaseUrl() {
        assertThatThrownBy(() -> new PublicRenderProperties(
                "release-1", MANIFEST, 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("public base URL is required");
    }
}
