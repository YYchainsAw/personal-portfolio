package xyz.yychainsaw.portfolio.media.storage;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

class LocalStorageContractTest extends AbstractStorageContractTest {
    @TempDir
    Path temporaryDirectory;

    private LocalStorageService storage;
    private Path externalBoundary;

    @BeforeEach
    void createStorage() throws IOException {
        storage = new LocalStorageService(
                new LocalStorageProperties(storageRoot()));
    }

    @AfterEach
    void closeStorage() throws Exception {
        try {
            if (storage != null) {
                storage.close();
            }
        } finally {
            deleteExternalBoundary();
        }
    }

    @Override
    StorageService storage() {
        return storage;
    }

    private Path storageRoot() throws IOException {
        FileStore store = Files.getFileStore(temporaryDirectory);
        if (store.supportsFileAttributeView(AclFileAttributeView.class)
                && !store.supportsFileAttributeView("posix")) {
            Path home = Path.of(System.getProperty("user.home"))
                    .toAbsolutePath()
                    .normalize();
            externalBoundary = Files.createTempDirectory(home, ".portfolio-media-contract-");
            return externalBoundary.resolve("media");
        }
        return temporaryDirectory.resolve("media");
    }

    private void deleteExternalBoundary() throws IOException {
        if (externalBoundary == null || !Files.exists(externalBoundary)) {
            return;
        }
        try (var paths = Files.walk(externalBoundary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
