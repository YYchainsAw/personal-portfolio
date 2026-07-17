package xyz.yychainsaw.portfolio.media.storage;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.LoggingSystem;

@Tag("cos-live")
class TencentCosLiveSmokeTest extends AbstractStorageContractTest {
    private final Set<String> knownKeys = new LinkedHashSet<>();

    @TempDir
    Path temporaryDirectory;

    private String prefix;
    private TencentCosStorageService storage;
    private QcloudCosClientAdapter client;
    private CosSdkLogSilencer logSilencer;
    private Path externalStagingBoundary;

    @BeforeEach
    void connectToDisposablePrefix() throws IOException {
        prefix = "smoke/" + UUID.randomUUID() + "/";
        String region = requiredEnvironment("COS_REGION");
        String bucket = requiredEnvironment("COS_BUCKET");
        String secretId = requiredEnvironment("COS_SECRET_ID");
        String secretKey = requiredEnvironment("COS_SECRET_KEY");
        String sessionToken = optionalEnvironment("COS_SESSION_TOKEN");

        TencentCosProperties properties = new TencentCosProperties(
                region, bucket, secretId, secretKey, sessionToken);
        logSilencer = new CosSdkLogSilencer(LoggingSystem.get(
                TencentCosLiveSmokeTest.class.getClassLoader()));
        logSilencer.silence();
        try {
            client = QcloudCosClientAdapter.create(properties);
            client.onShutdownSuccess(logSilencer::clientStopped);
            logSilencer.blockRestoreUntilClientStops();
            storage = new TencentCosStorageService(
                    client,
                    properties,
                    Clock.systemUTC(),
                    stagingRoot());
        } catch (IOException | RuntimeException failure) {
            closeResources(failure);
            deleteExternalStagingBoundary(failure);
            throw failure;
        }
    }

    @AfterEach
    void deleteKnownKeysAndCloseResources() throws Exception {
        Throwable cleanupFailure = null;
        if (storage != null) {
            List<String> cleanupOrder = new ArrayList<>(knownKeys);
            Collections.reverse(cleanupOrder);
            for (String key : cleanupOrder) {
                try {
                    storage.delete(key);
                    if (storage.exists(key)) {
                        throw new AssertionError(
                                "COS smoke object cleanup was not confirmed");
                    }
                } catch (RuntimeException | AssertionError failure) {
                    cleanupFailure = merge(cleanupFailure, failure);
                }
            }
        }
        cleanupFailure = closeResources(cleanupFailure);
        cleanupFailure = deleteExternalStagingBoundary(cleanupFailure);
        knownKeys.clear();
        rethrow(cleanupFailure);
    }

    @Override
    StorageService storage() {
        return storage;
    }

    @Override
    String objectKey(String relativeKey) {
        String key = prefix + relativeKey;
        knownKeys.add(key);
        return key;
    }

    private Throwable closeResources(Throwable failure) {
        if (storage != null) {
            try {
                storage.close();
            } catch (IOException | RuntimeException closeFailure) {
                failure = merge(failure, closeFailure);
            }
            storage = null;
        }
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException closeFailure) {
                failure = merge(failure, closeFailure);
            }
            client = null;
        }
        if (logSilencer != null) {
            try {
                logSilencer.close();
            } catch (RuntimeException closeFailure) {
                failure = merge(failure, closeFailure);
            }
            logSilencer = null;
        }
        return failure;
    }

    private Path stagingRoot() throws IOException {
        FileStore store = Files.getFileStore(temporaryDirectory);
        if (store.supportsFileAttributeView(AclFileAttributeView.class)
                && !store.supportsFileAttributeView("posix")) {
            Path home = Path.of(System.getProperty("user.home"))
                    .toAbsolutePath()
                    .normalize();
            externalStagingBoundary =
                    Files.createTempDirectory(home, ".portfolio-cos-contract-");
            return externalStagingBoundary.resolve("staging");
        }
        return temporaryDirectory.resolve("cos-staging");
    }

    private Throwable deleteExternalStagingBoundary(Throwable failure) {
        if (externalStagingBoundary == null
                || !Files.exists(externalStagingBoundary)) {
            return failure;
        }
        boolean deleted = false;
        try (var paths = Files.walk(externalStagingBoundary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            deleted = true;
        } catch (IOException | RuntimeException deleteFailure) {
            failure = merge(failure, deleteFailure);
        }
        if (deleted) {
            externalStagingBoundary = null;
        }
        return failure;
    }

    private static Throwable merge(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (primary != additional) {
            primary.addSuppressed(additional);
        }
        return primary;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure == null) {
            return;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        throw new AssertionError("COS smoke cleanup failed", failure);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assumeTrue(
                value != null && !value.isBlank(),
                name + " is required for COS live smoke tests");
        return value;
    }

    private static String optionalEnvironment(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }
}
