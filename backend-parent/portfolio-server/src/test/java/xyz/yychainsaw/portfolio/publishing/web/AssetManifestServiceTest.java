package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import xyz.yychainsaw.portfolio.publishing.config.PublicRenderProperties;

class AssetManifestServiceTest {
    private static final String ENTRY = "src/main.ts";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsExactEntryOnceAndPreservesImmutableCssOrder() throws Exception {
        LinkedHashMap<String, Object> entry = validEntry();
        entry.put("name", "main");
        entry.put("assets", List.of("assets/font-test.woff2"));
        CountingResource manifest = new CountingResource(
                mapper.writeValueAsBytes(Map.of(
                        "_shared.js", Map.of("file", "assets/shared.js"),
                        ENTRY, entry)));

        AssetManifestService service = service(manifest);

        assertThat(service.entryJs()).isEqualTo("/assets/index-test123.js");
        assertThat(service.css()).containsExactly(
                "/assets/first-test123.css",
                "/assets/second-test123.css");
        assertThatThrownBy(() -> service.css().add("/assets/late.css"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(service.entryJs()).isEqualTo("/assets/index-test123.js");
        assertThat(manifest.openCount()).isOne();
    }

    @Test
    void registersTheReaderOnlyForServletApplications() {
        ConditionalOnWebApplication condition =
                ViteAssetManifestService.class.getAnnotation(
                        ConditionalOnWebApplication.class);

        assertThat(condition).isNotNull();
        assertThat(condition.type()).isEqualTo(Type.SERVLET);
    }

    @Test
    void rejectsMissingUnreadableAndMalformedResources() {
        assertThatThrownBy(() -> service(
                new ClassPathResource("missing-vite-manifest.json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Vite manifest resource is missing or unreadable");

        assertThatThrownBy(() -> service(new ByteArrayResource(
                "not-json".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Vite manifest cannot be read")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void rejectsMissingEntryAndMalformedClosedFields() throws Exception {
        assertInvalidDocument(Map.of());
        assertInvalidDocument(Map.of(ENTRY, "not-an-object"));

        LinkedHashMap<String, Object> missingFile = validEntry();
        missingFile.remove("file");
        assertInvalidEntry(missingFile);

        LinkedHashMap<String, Object> wrongFile = validEntry();
        wrongFile.put("file", 42);
        assertInvalidEntry(wrongFile);

        LinkedHashMap<String, Object> wrongSource = validEntry();
        wrongSource.put("src", "index.html");
        assertInvalidEntry(wrongSource);

        LinkedHashMap<String, Object> wrongEntryFlag = validEntry();
        wrongEntryFlag.put("isEntry", "true");
        assertInvalidEntry(wrongEntryFlag);

        LinkedHashMap<String, Object> falseEntryFlag = validEntry();
        falseEntryFlag.put("isEntry", false);
        assertInvalidEntry(falseEntryFlag);

        LinkedHashMap<String, Object> missingCss = validEntry();
        missingCss.remove("css");
        assertInvalidEntry(missingCss);

        LinkedHashMap<String, Object> wrongCss = validEntry();
        wrongCss.put("css", "assets/index.css");
        assertInvalidEntry(wrongCss);

        LinkedHashMap<String, Object> wrongCssElement = validEntry();
        wrongCssElement.put("css", List.of(42));
        assertInvalidEntry(wrongCssElement);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/assets/index.js",
            "//cdn.example.test/index.js",
            "https://cdn.example.test/index.js",
            "C:/assets/index.js",
            "assets/../index.js",
            "assets/%2e%2e/index.js",
            "assets/%252e%252e/index.js",
            "assets/%2Findex.js",
            "assets/%20/index.js",
            "assets/%3A/index.js",
            "assets\\index.js",
            "assets/./index.js",
            "assets//index.js",
            "assets/index.js?query=yes",
            "assets/index.js#fragment",
            "assets/index.css"
    })
    void rejectsUnsafeOrNonJavascriptEntryPaths(String path) throws Exception {
        LinkedHashMap<String, Object> entry = validEntry();
        entry.put("file", path);

        assertInvalidEntry(entry);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/assets/index.css",
            "//cdn.example.test/index.css",
            "https://cdn.example.test/index.css",
            "C:/assets/index.css",
            "assets/../index.css",
            "assets/.%2e/index.css",
            "assets/%5cindex.css",
            "assets\\index.css",
            "assets/./index.css",
            "assets//index.css",
            "assets/index.css?query=yes",
            "assets/index.css#fragment",
            "assets/index.js"
    })
    void rejectsUnsafeOrNonCssPaths(String path) throws Exception {
        LinkedHashMap<String, Object> entry = validEntry();
        entry.put("css", List.of(path));

        assertInvalidEntry(entry);
    }

    @Test
    void acceptsAnEmptyButPresentCssList() throws Exception {
        LinkedHashMap<String, Object> entry = validEntry();
        entry.put("css", List.of());

        assertThat(service(document(entry)).css()).isEmpty();
    }

    private AssetManifestService service(Resource resource) {
        return new ViteAssetManifestService(
                properties(), new FixedResourceLoader(resource), mapper);
    }

    private Resource document(Map<String, Object> entry) throws Exception {
        return new ByteArrayResource(mapper.writeValueAsBytes(Map.of(ENTRY, entry)));
    }

    private void assertInvalidEntry(Map<String, Object> entry) throws Exception {
        assertInvalidDocument(Map.of(ENTRY, entry));
    }

    private void assertInvalidDocument(Map<String, Object> document) throws Exception {
        assertThatThrownBy(() -> service(new ByteArrayResource(
                mapper.writeValueAsBytes(document))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Vite manifest ");
    }

    private static LinkedHashMap<String, Object> validEntry() {
        LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
        entry.put("file", "assets/index-test123.js");
        entry.put("src", ENTRY);
        entry.put("isEntry", true);
        entry.put("css", List.of(
                "assets/first-test123.css",
                "assets/second-test123.css"));
        return entry;
    }

    private static PublicRenderProperties properties() {
        return new PublicRenderProperties(
                "test-release",
                "classpath:/vite/manifest.json",
                1,
                URI.create("https://example.test"));
    }

    private record FixedResourceLoader(Resource resource) implements ResourceLoader {
        @Override
        public Resource getResource(String location) {
            return resource;
        }

        @Override
        public ClassLoader getClassLoader() {
            return AssetManifestServiceTest.class.getClassLoader();
        }
    }

    private static final class CountingResource extends AbstractResource {
        private final byte[] bytes;
        private final AtomicInteger openCount = new AtomicInteger();

        private CountingResource(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public String getDescription() {
            return "counting Vite manifest";
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public boolean isReadable() {
            return true;
        }

        @Override
        public InputStream getInputStream() {
            openCount.incrementAndGet();
            return new ByteArrayInputStream(bytes);
        }

        private int openCount() {
            return openCount.get();
        }
    }
}
