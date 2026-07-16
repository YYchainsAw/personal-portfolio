package xyz.yychainsaw.portfolio.media.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.storage.local")
public record LocalStorageProperties(Path root) {
    private static final Path DEFAULT_ROOT = Path.of("../runtime/media");

    public LocalStorageProperties {
        if (root == null) {
            root = DEFAULT_ROOT;
        } else {
            Path normalized = root.normalize();
            Path absolute = normalized.toAbsolutePath().normalize();
            if (root.toString().isBlank()
                    || normalized.toString().isBlank()
                    || absolute.equals(absolute.getRoot())) {
                throw new IllegalArgumentException("Invalid local storage root");
            }
        }
    }
}
