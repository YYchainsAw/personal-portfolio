package xyz.yychainsaw.portfolio.media.storage;

import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.endpoint.DefaultEndpointResolver;
import com.qcloud.cos.endpoint.RegionEndpointBuilder;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.CopyObjectRequest;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

class TencentCosStorageServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final List<String> PROTECTED_LOGGERS = List.of(
            "com.qcloud.cos",
            "com.qcloud.cos.http.DefaultCosHttpClient",
            "com.qcloud.cos.COSClient",
            "org.apache.http",
            "org.apache.http.headers",
            "org.apache.http.wire",
            "org.apache.http.impl.execchain.MainClientExec",
            "org.apache.http.impl.execchain.ProtocolExec");

    @TempDir
    Path temporaryDirectory;
    private final List<Path> externalCleanup = new ArrayList<>();
    private Path stagingBoundary;

    @AfterEach
    void removeExternalWindowsTestDirectories() throws Exception {
        for (int index = externalCleanup.size() - 1; index >= 0; index--) {
            deleteTree(externalCleanup.get(index));
        }
    }

    @Test
    void signsOfficialReadUrlForRequestedTtlAndKeepsProviderMetadata() throws Exception {
        FakeCosClient client = new FakeCosClient();
        try (TencentCosStorageService service = new TencentCosStorageService(
                client,
                properties(),
                CLOCK,
                stagingRoot())) {
            URI uri = service.signedGet("variants/a/1280.jpg", Duration.ofMinutes(5));

            assertThat(uri).isEqualTo(URI.create(
                    "https://portfolio-1234567890.cos.ap-guangzhou.myqcloud.com/"
                            + "variants/a/1280.jpg?ttl=300"));
            assertThat(client.lastExpiration).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
            assertThat(service.provider()).isEqualTo(StorageProvider.TENCENT_COS);
            assertThat(service.location()).isEqualTo(new StorageLocation(
                    StorageProvider.TENCENT_COS,
                    "portfolio-1234567890",
                    "ap-guangzhou"));
            assertThat(service.location().provider()).isEqualTo(service.provider());
        }
    }

    @Test
    void rejectsOutOfRangeSignedGetTtlsBeforeCallingThePort() throws Exception {
        FakeCosClient client = new FakeCosClient();
        try (TencentCosStorageService service = service(client, CLOCK)) {
            for (Duration ttl : new Duration[] {
                    null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(301)
            }) {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> service.signedGet("asset.jpg", ttl))
                        .withMessage("COS signed GET TTL must be within five minutes");
            }
        }

        assertThat(client.signCalls).isZero();
    }

    @Test
    void validatesSignedGetObjectKeyBeforeCallingThePort() throws Exception {
        FakeCosClient client = new FakeCosClient();
        try (TencentCosStorageService service = service(client, CLOCK)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.signedGet("../secret.jpg", Duration.ofMinutes(1)))
                    .withMessage("Invalid storage object key");
        }

        assertThat(client.signCalls).isZero();
    }

    @Test
    void mapsSignedGetInstantOverflowToAFixedKeyFreeFailure() throws Exception {
        FakeCosClient client = new FakeCosClient();
        try (TencentCosStorageService service = service(
                client, Clock.fixed(Instant.MAX, ZoneOffset.UTC))) {
            assertStorageFailure(
                    () -> service.signedGet("asset.jpg", Duration.ofSeconds(1)),
                    "COS_SIGN_FAILED");
        }

        assertThat(client.signCalls).isZero();
    }

    @Test
    void rejectsUnexpectedSignedUrlOriginsWithoutExposingTheUri() throws Exception {
        FakeCosClient client = new FakeCosClient();
        URI[] unexpected = {
                URI.create("http://portfolio-1234567890.cos.ap-guangzhou.myqcloud.com/asset.jpg"),
                URI.create("https://other.cos.ap-guangzhou.myqcloud.com/asset.jpg"),
                URI.create("https://user@portfolio-1234567890.cos.ap-guangzhou.myqcloud.com/asset.jpg"),
                URI.create("https://portfolio-1234567890.cos.ap-guangzhou.myqcloud.com:443/asset.jpg"),
                URI.create("https://portfolio-1234567890.cos.ap-guangzhou.myqcloud.com/asset.jpg")
        };
        try (TencentCosStorageService service = service(client, CLOCK)) {
            for (URI uri : unexpected) {
                client.signedUri = uri;
                assertStorageFailure(
                        () -> service.signedGet("asset.jpg", Duration.ofMinutes(1)),
                        "COS_INVALID_RESPONSE");
            }
        }
    }

    @Test
    void propertiesValidateOfficialRegionBucketAndCompleteCredentialsWithoutEchoingValues() {
        String[] invalidRegions = {
                null, "", "AP-guangzhou", "guangzhou", "cn-east", "cos.ap-guangzhou",
                "ap--guangzhou", "ap_guangzhou", "ap-guangzhou\n"
        };
        for (String region : invalidRegions) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TencentCosProperties(
                            region, "portfolio-1234567890", "id", "key", null))
                    .withMessage("COS region is invalid");
        }

        String[] invalidBuckets = {
                null, "", "portfolio", "Portfolio-1234567890", "-portfolio-1234567890",
                "portfolio--1234567890", "portfolio-0123456789", "portfolio-1234",
                "portfolio-1234567890123"
        };
        for (String bucket : invalidBuckets) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TencentCosProperties(
                            "ap-guangzhou", bucket, "id", "key", null))
                    .withMessage("COS bucket is invalid");
        }

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TencentCosProperties(
                        "ap-guangzhou", "portfolio-1234567890", null, "key", null))
                .withMessage("COS credentials are invalid");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TencentCosProperties(
                        "ap-guangzhou", "portfolio-1234567890", "id", "", null))
                .withMessage("COS credentials are invalid");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TencentCosProperties(
                        "ap-guangzhou", "portfolio-1234567890", "id\n", "key", null))
                .withMessage("COS credentials are invalid");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TencentCosProperties(
                        "ap-guangzhou", "portfolio-1234567890", "id", "key", "token\r"))
                .withMessage("COS session token is invalid");
    }

    @Test
    void propertiesRedactEveryCredentialAndNormalizeBlankOptionalToken() {
        TencentCosProperties permanent = new TencentCosProperties(
                "ap-guangzhou", "portfolio-1234567890", "secret-id", "secret-key", "   ");
        TencentCosProperties temporary = new TencentCosProperties(
                "ap-guangzhou", "portfolio-1234567890", "temporary-id", "temporary-key",
                "temporary-token");

        assertThat(TencentCosProperties.class.isRecord()).isFalse();
        assertThat(permanent.sessionToken()).isNull();
        assertThat(temporary.sessionToken()).isEqualTo("temporary-token");
        assertThat(temporary.toString())
                .contains("region=ap-guangzhou", "bucket=portfolio-1234567890")
                .contains("secretId=<redacted>", "secretKey=<redacted>",
                        "sessionToken=<redacted>")
                .doesNotContain("temporary-id", "temporary-key", "temporary-token");
        assertThat(temporary).isNotEqualTo(new TencentCosProperties(
                "ap-guangzhou", "portfolio-1234567890", "temporary-id", "temporary-key",
                "temporary-token"));
    }

    @Test
    void stagesExactBytesClosesCallerBeforePutAndCleansThePrivateAlias() throws Exception {
        FakeCosClient client = new FakeCosClient();
        CloseTrackingInputStream input = tracking("portfolio".getBytes(StandardCharsets.UTF_8));
        client.callerInput = input;

        StoredObject stored;
        try (TencentCosStorageService service = service(client, CLOCK)) {
            stored = service.put("originals/asset.bin", input, 9, "application/octet-stream");
        }

        assertThat(client.putCalls).isOne();
        assertThat(client.callerCloseCountAtPut).isOne();
        assertThat(input.closeCount()).isOne();
        assertThat(client.lastPutBytes).isEqualTo("portfolio".getBytes(StandardCharsets.UTF_8));
        assertThat(stored.provider()).isEqualTo(StorageProvider.TENCENT_COS);
        assertThat(stored.bucket()).isEqualTo("portfolio-1234567890");
        assertThat(stored.region()).isEqualTo("ap-guangzhou");
        assertThat(stored.objectKey()).isEqualTo("originals/asset.bin");
        assertThat(stored.contentLength()).isEqualTo(9);
        assertThat(stored.contentType()).isEqualTo("application/octet-stream");
        assertNoStagingAliases(stagingRoot());
    }

    @Test
    void rejectsShortAndLongCallerStreamsBeforeThePortAndProbesOneExtraByte()
            throws Exception {
        FakeCosClient client = new FakeCosClient();
        CloseTrackingInputStream shortInput = tracking(new byte[] {1, 2});
        ProbeTrackingInputStream longInput = new ProbeTrackingInputStream(new byte[] {1, 2, 3, 4});

        try (TencentCosStorageService service = service(client, CLOCK)) {
            assertStorageFailure(
                    () -> service.put("short.bin", shortInput, 3, "application/octet-stream"),
                    "COS_CONTENT_LENGTH_MISMATCH");
            assertStorageFailure(
                    () -> service.put("long.bin", longInput, 3, "application/octet-stream"),
                    "COS_CONTENT_LENGTH_MISMATCH");
        }

        assertThat(client.putCalls).isZero();
        assertThat(shortInput.closeCount()).isOne();
        assertThat(longInput.closeCount()).isOne();
        assertThat(longInput.bytesReturned()).isEqualTo(4);
        assertNoStagingAliases(stagingRoot());
    }

    @Test
    void callerCloseFailurePreventsPublicationAndUsesAFixedFailure() throws Exception {
        FakeCosClient client = new FakeCosClient();
        CloseFailingInputStream input = new CloseFailingInputStream(new byte[] {1});

        try (TencentCosStorageService service = service(client, CLOCK)) {
            assertStorageFailure(
                    () -> service.put("asset.bin", input, 1, "application/octet-stream"),
                    "COS_WRITE_FAILED");
        }

        assertThat(client.putCalls).isZero();
        assertThat(input.closeCount()).isOne();
        assertNoStagingAliases(stagingRoot());
    }

    @Test
    void uncheckedCallerFailuresAreSanitizedClosedAndCleanedBeforePublication()
            throws Exception {
        FakeCosClient client = new FakeCosClient();
        AtomicInteger readFailureCloses = new AtomicInteger();
        InputStream readFailure = new InputStream() {
            @Override
            public int read() {
                throw new IllegalStateException("asset.bin Authorization secret-key");
            }

            @Override
            public void close() {
                readFailureCloses.incrementAndGet();
            }
        };
        AtomicInteger closeFailureCloses = new AtomicInteger();
        InputStream closeFailure = new java.io.ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() {
                closeFailureCloses.incrementAndGet();
                throw new IllegalStateException("asset.bin Authorization secret-key");
            }
        };
        AtomicInteger combinedFailureCloses = new AtomicInteger();
        InputStream shortAndCloseFailure = new java.io.ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() {
                combinedFailureCloses.incrementAndGet();
                throw new IllegalStateException("asset.bin Authorization secret-key");
            }
        };
        AtomicInteger invalidKeyCloses = new AtomicInteger();
        InputStream invalidKey = new java.io.ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() {
                invalidKeyCloses.incrementAndGet();
                throw new IllegalStateException("asset.bin Authorization secret-key");
            }
        };

        try (TencentCosStorageService service = service(client, CLOCK)) {
            assertStorageFailure(
                    () -> service.put(
                            "asset.bin", readFailure, 1, "application/octet-stream"),
                    "COS_WRITE_FAILED");
            assertStorageFailure(
                    () -> service.put(
                            "asset.bin", closeFailure, 1, "application/octet-stream"),
                    "COS_WRITE_FAILED");
            assertStorageFailure(
                    () -> service.put(
                            "asset.bin", shortAndCloseFailure, 2,
                            "application/octet-stream"),
                    "COS_CONTENT_LENGTH_MISMATCH");
            assertThatThrownBy(() -> service.put(
                            "../asset.bin", invalidKey, 1, "application/octet-stream"))
                    .isInstanceOfSatisfying(IllegalArgumentException.class, exception -> {
                        assertThat(exception.getCause()).isNull();
                        assertThat(exception.getSuppressed()).isEmpty();
                    })
                    .hasMessage("Invalid storage object key")
                    .hasMessageNotContaining("Authorization")
                    .hasMessageNotContaining("secret-key");
        }

        assertThat(client.putCalls).isZero();
        assertThat(readFailureCloses).hasValue(1);
        assertThat(closeFailureCloses).hasValue(1);
        assertThat(combinedFailureCloses).hasValue(1);
        assertThat(invalidKeyCloses).hasValue(1);
        assertNoStagingAliases(stagingRoot());
    }

    @Test
    void putValidationStillClosesTheOwnedInputBeforeAnyPortCall() throws Exception {
        FakeCosClient client = new FakeCosClient();
        CloseTrackingInputStream invalidKey = tracking(new byte[] {1});
        CloseTrackingInputStream invalidLength = tracking(new byte[] {1});
        CloseTrackingInputStream invalidType = tracking(new byte[] {1});

        try (TencentCosStorageService service = service(client, CLOCK)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.put(
                            "../asset.bin", invalidKey, 1, "application/octet-stream"))
                    .withMessage("Invalid storage object key");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.put(
                            "asset.bin", invalidLength, 0, "application/octet-stream"))
                    .withMessage("Invalid storage content length");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.put("asset.bin", invalidType, 1, "application/pdf"))
                    .withMessage("Invalid storage content type");
        }

        assertThat(client.putCalls).isZero();
        assertThat(invalidKey.closeCount()).isOne();
        assertThat(invalidLength.closeCount()).isOne();
        assertThat(invalidType.closeCount()).isOne();
    }

    @Test
    void stagingFilesAreOwnerOnlyAndRevalidatedAfterTheCallerCloses() throws Exception {
        FakeCosClient client = new FakeCosClient();
        AtomicInteger inspections = new AtomicInteger();
        TencentCosStorageService.StagingObserver observer = new TencentCosStorageService.StagingObserver() {
            @Override
            public void afterStageClosed(Path path) throws IOException {
                FileStore store = Files.getFileStore(path);
                if (store.supportsFileAttributeView("posix")) {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                            path, LinkOption.NOFOLLOW_LINKS);
                    assertThat(permissions)
                            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
                } else {
                    AclFileAttributeView view = Files.getFileAttributeView(
                            path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                    assertThat(view).isNotNull();
                    assertThat(view.getAcl()).isNotEmpty().allSatisfy(entry -> {
                        assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
                        assertThat(entry.principal()).isEqualTo(view.getOwner());
                    });
                }
                inspections.incrementAndGet();
            }

            @Override
            public void beforeCleanup(Path path) {}
        };

        try (TencentCosStorageService service = service(client, CLOCK, observer)) {
            service.put(
                    "asset.bin", tracking(new byte[] {1}), 1, "application/octet-stream");
        }

        assertThat(inspections).hasValue(1);
        assertThat(client.putCalls).isOne();
    }

    @Test
    void rejectsAChangedStagingFileBeforeCallingThePort() throws Exception {
        FakeCosClient client = new FakeCosClient();
        TencentCosStorageService.StagingObserver observer = new TencentCosStorageService.StagingObserver() {
            @Override
            public void afterStageClosed(Path path) throws IOException {
                try (var channel = Files.newByteChannel(
                        path, java.nio.file.StandardOpenOption.WRITE)) {
                    channel.truncate(0);
                }
            }

            @Override
            public void beforeCleanup(Path path) {}
        };

        try (TencentCosStorageService service = service(client, CLOCK, observer)) {
            assertStorageFailure(
                    () -> service.put(
                            "asset.bin", tracking(new byte[] {1}), 1,
                            "application/octet-stream"),
                    "COS_WRITE_FAILED");
        }

        assertThat(client.putCalls).isZero();
        assertNoStagingAliases(stagingRoot());
    }

    @Test
    void publicationCleanupFailureIsNonRetryableAndTakesPrecedenceOverResponseValidation()
            throws Exception {
        FakeCosClient client = new FakeCosClient();
        client.overridePutResult = true;
        client.putResult = null;
        TencentCosStorageService.StagingObserver observer = new TencentCosStorageService.StagingObserver() {
            @Override
            public void afterStageClosed(Path path) {}

            @Override
            public void beforeCleanup(Path path) throws IOException {
                throw new IOException("synthetic cleanup failure");
            }
        };

        try (TencentCosStorageService service = service(client, CLOCK, observer)) {
            assertStorageFailure(
                    () -> service.put(
                            "asset.bin", tracking(new byte[] {1}), 1,
                            "application/octet-stream"),
                    "COS_STAGING_CLEANUP_FAILED");
        }

        assertThat(client.putCalls).isOne();
        assertThat(stagingAliases(stagingRoot())).isOne();
    }

    @Test
    void rejectsEveryInconsistentPortPutResultAndCleansStaging() throws Exception {
        FakeCosClient client = new FakeCosClient();
        client.overridePutResult = true;
        List<StoredObject> invalid = java.util.Arrays.asList(
                null,
                new StoredObject(StorageProvider.TENCENT_COS, "other-12345", "ap-guangzhou",
                        "asset.bin", 1, "application/octet-stream", "etag"),
                new StoredObject(StorageProvider.TENCENT_COS, "portfolio-1234567890", "ap-beijing",
                        "asset.bin", 1, "application/octet-stream", "etag"),
                new StoredObject(StorageProvider.TENCENT_COS, "portfolio-1234567890", "ap-guangzhou",
                        "other.bin", 1, "application/octet-stream", "etag"),
                new StoredObject(StorageProvider.TENCENT_COS, "portfolio-1234567890", "ap-guangzhou",
                        "asset.bin", 2, "application/octet-stream", "etag"),
                new StoredObject(StorageProvider.TENCENT_COS, "portfolio-1234567890", "ap-guangzhou",
                        "asset.pdf", 1, "application/pdf", "etag"),
                new StoredObject(StorageProvider.LOCAL, null, null,
                        "asset.bin", 1, "application/octet-stream", "etag"));

        try (TencentCosStorageService service = service(client, CLOCK)) {
            for (StoredObject result : invalid) {
                client.putResult = result;
                assertStorageFailure(
                        () -> service.put(
                                "asset.bin", tracking(new byte[] {1}), 1,
                                "application/octet-stream"),
                        "COS_INVALID_RESPONSE");
            }
        }

        assertThat(client.putCalls).isEqualTo(invalid.size());
        assertNoStagingAliases(stagingRoot());
    }

    @Test
    void mapsArbitraryPortPutFailuresButPreservesTheCreateOnlyConflict() throws Exception {
        FakeCosClient client = new FakeCosClient();

        try (TencentCosStorageService service = service(client, CLOCK)) {
            client.putFailure = new StorageException(
                    "STORAGE_OBJECT_ALREADY_EXISTS",
                    new IllegalStateException("asset.bin Authorization secret-key"));
            assertStorageFailure(
                    () -> service.put(
                            "asset.bin", tracking(new byte[] {1}), 1,
                            "application/octet-stream"),
                    "STORAGE_OBJECT_ALREADY_EXISTS");

            client.putFailure = new IllegalStateException("asset.bin secret-key");
            assertStorageFailure(
                    () -> service.put(
                            "asset.bin", tracking(new byte[] {1}), 1,
                            "application/octet-stream"),
                    "COS_WRITE_FAILED");
        }

        assertThat(client.putCalls).isEqualTo(2);
        assertNoStagingAliases(stagingRoot());
    }

    @Test
    void routesFullAndRangeReadsExistsCreateOnlyCopyAndDeleteThroughTheValidatedPort()
            throws Exception {
        FakeCosClient client = new FakeCosClient();
        client.existsResult = true;

        try (TencentCosStorageService service = service(client, CLOCK)) {
            try (StorageRead full = service.open("asset.bin", Optional.empty())) {
                assertThat(full.inputStream().readAllBytes())
                        .isEqualTo("portfolio".getBytes(StandardCharsets.UTF_8));
                assertThat(full.totalLength()).isEqualTo(9);
                assertThat(full.range()).isEmpty();
                assertThat(full.contentLength()).isEqualTo(9);
                assertThat(full.contentType()).isEqualTo("application/octet-stream");
                assertThat(full.etag()).isEqualTo("etag");
            }
            ByteRange requested = new ByteRange(1, 3);
            try (StorageRead ranged = service.open("asset.bin", Optional.of(requested))) {
                assertThat(ranged.inputStream().readAllBytes())
                        .isEqualTo("ort".getBytes(StandardCharsets.UTF_8));
                assertThat(ranged.totalLength()).isEqualTo(9);
                assertThat(ranged.range()).contains(requested);
                assertThat(ranged.contentLength()).isEqualTo(3);
            }
            assertThat(service.exists("asset.bin")).isTrue();
            service.copy("source.bin", "target.bin");
            service.delete("asset.bin");
        }

        assertThat(client.openCalls).isEqualTo(2);
        assertThat(client.lastOpenRange).contains(new ByteRange(1, 3));
        assertThat(client.existsCalls).isOne();
        assertThat(client.copyCalls).isOne();
        assertThat(client.lastCopySource).isEqualTo("source.bin");
        assertThat(client.lastCopyTarget).isEqualTo("target.bin");
        assertThat(client.deleteCalls).isOne();
    }

    @Test
    void validatesEveryCrudKeyAndCopyContentTypeBeforeCallingThePort() throws Exception {
        FakeCosClient client = new FakeCosClient();

        try (TencentCosStorageService service = service(client, CLOCK)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.open("../asset.bin", Optional.empty()))
                    .withMessage("Invalid storage object key");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.open("asset.bin", null))
                    .withMessage("Storage range is required");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.exists("../asset.bin"))
                    .withMessage("Invalid storage object key");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.copy("source.pdf", "target.bin"))
                    .withMessage("Invalid storage content type");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.delete("../asset.bin"))
                    .withMessage("Invalid storage object key");
        }

        assertThat(client.openCalls).isZero();
        assertThat(client.existsCalls).isZero();
        assertThat(client.copyCalls).isZero();
        assertThat(client.deleteCalls).isZero();
    }

    @Test
    void closesAndRejectsAnInconsistentPortReadResponse() throws Exception {
        FakeCosClient client = new FakeCosClient();
        CloseTrackingInputStream response = tracking(new byte[] {1});
        client.overrideOpenResult = true;
        client.openResult = new StorageRead(
                response, 1, Optional.empty(), 1, "text/plain", "etag");

        try (TencentCosStorageService service = service(client, CLOCK)) {
            assertStorageFailure(
                    () -> service.open("asset.bin", Optional.empty()),
                    "COS_INVALID_RESPONSE");
        }

        assertThat(response.closeCount()).isOne();
        assertThat(client.openCalls).isOne();
    }

    @Test
    void preservesInvalidReadFailureWhenTheRejectedResponseCloseThrowsRuntime()
            throws Exception {
        FakeCosClient client = new FakeCosClient();
        AtomicInteger closes = new AtomicInteger();
        InputStream response = new java.io.ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() {
                closes.incrementAndGet();
                throw new IllegalStateException("asset.bin Authorization secret-key");
            }
        };
        client.overrideOpenResult = true;
        client.openResult = new StorageRead(
                response, 1, Optional.empty(), 1, "text/plain", "etag");

        try (TencentCosStorageService service = service(client, CLOCK)) {
            assertStorageFailure(
                    () -> service.open("asset.bin", Optional.empty()),
                    "COS_INVALID_RESPONSE");
        }

        assertThat(closes).hasValue(1);
        assertThat(client.openCalls).isOne();
    }

    @Test
    void mapsCrudAndSignPortFailuresToFreshFixedCodes() throws Exception {
        FakeCosClient client = new FakeCosClient();

        try (TencentCosStorageService service = service(client, CLOCK)) {
            client.openFailure = new IllegalStateException("asset.bin secret");
            assertStorageFailure(
                    () -> service.open("asset.bin", Optional.empty()), "COS_READ_FAILED");
            client.openFailure = new StorageRangeNotSatisfiableException(9);
            assertThatThrownBy(() -> service.open(
                            "asset.bin", Optional.of(new ByteRange(9, 9))))
                    .isInstanceOfSatisfying(StorageRangeNotSatisfiableException.class,
                            exception -> assertThat(exception.totalLength()).isEqualTo(9));

            client.existsFailure = new IllegalStateException("asset.bin secret");
            assertStorageFailure(() -> service.exists("asset.bin"), "COS_EXISTS_FAILED");

            client.copyFailure = new StorageException(
                    "STORAGE_OBJECT_ALREADY_EXISTS",
                    new IllegalStateException("target.bin Authorization secret-key"));
            assertStorageFailure(
                    () -> service.copy("source.bin", "target.bin"),
                    "STORAGE_OBJECT_ALREADY_EXISTS");
            client.copyFailure = new IllegalStateException("target.bin secret");
            assertStorageFailure(
                    () -> service.copy("source.bin", "target.bin"), "COS_COPY_FAILED");

            client.deleteFailure = new IllegalStateException("asset.bin secret");
            assertStorageFailure(() -> service.delete("asset.bin"), "COS_DELETE_FAILED");

            client.signFailure = new IllegalStateException("signed URI secret");
            assertStorageFailure(
                    () -> service.signedGet("asset.jpg", Duration.ofMinutes(1)),
                    "COS_SIGN_FAILED");
        }
    }

    @Test
    void sdkAdapterBuildsPrivateCreateOnlyPutRequestsAndOnlyPdfIsAttachment() {
        CapturingSdk sdk = new CapturingSdk();
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());

        adapter.putCreateOnly(
                "portfolio-1234567890", "image.jpg", new java.io.ByteArrayInputStream(new byte[2]),
                2, "image/jpeg");
        assertPrivateCreateOnly(sdk.putRequest);
        assertThat(sdk.putRequest.getMetadata().getContentLength()).isEqualTo(2);
        assertThat(sdk.putRequest.getMetadata().getContentType()).isEqualTo("image/jpeg");
        assertThat(sdk.putRequest.getMetadata().getContentDisposition()).isNull();

        adapter.putCreateOnly(
                "portfolio-1234567890", "image.png", new java.io.ByteArrayInputStream(new byte[3]),
                3, "image/png");
        assertPrivateCreateOnly(sdk.putRequest);
        assertThat(sdk.putRequest.getMetadata().getContentDisposition()).isNull();

        StoredObject pdf = adapter.putCreateOnly(
                "portfolio-1234567890", "document.pdf",
                new java.io.ByteArrayInputStream(new byte[4]), 4, "application/pdf");
        assertPrivateCreateOnly(sdk.putRequest);
        assertThat(sdk.putRequest.getMetadata().getContentDisposition())
                .isEqualTo("attachment; filename=\"document.pdf\"");
        assertThat(sdk.putRequest.getMetadata().getUserMetadata()).isEmpty();
        assertThat(pdf.provider()).isEqualTo(StorageProvider.TENCENT_COS);
        assertThat(pdf.etag()).isEqualTo("etag");
    }

    @Test
    void sdkAdapterMapsExplicitPutPreconditionFailureToConflict() {
        CapturingSdk sdk = new CapturingSdk();
        CosServiceException precondition = new CosServiceException(
                "asset key Authorization secret");
        precondition.setStatusCode(412);
        sdk.putFailure = precondition;
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());

        assertStorageFailure(
                () -> adapter.putCreateOnly(
                        "portfolio-1234567890", "asset.bin",
                        new java.io.ByteArrayInputStream(new byte[1]),
                        1, "application/octet-stream"),
                "STORAGE_OBJECT_ALREADY_EXISTS");
        assertPrivateCreateOnly(sdk.putRequest);
    }

    @Test
    void sdkAdapterBuildsPrivateCreateOnlyCopyAndMapsNullOrExplicit412ToConflict() {
        CapturingSdk sdk = new CapturingSdk();
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());

        adapter.copyCreateOnly("portfolio-1234567890", "source.bin", "target.bin");
        assertThat(sdk.copyRequest.getSourceBucketName()).isEqualTo("portfolio-1234567890");
        assertThat(sdk.copyRequest.getSourceKey()).isEqualTo("source.bin");
        assertThat(sdk.copyRequest.getDestinationBucketName()).isEqualTo("portfolio-1234567890");
        assertThat(sdk.copyRequest.getDestinationKey()).isEqualTo("target.bin");
        assertThat(sdk.copyRequest.getCannedAccessControlList()).isNull();
        assertThat(sdk.copyRequest.getAccessControlList()).isNull();
        assertThat(sdk.copyRequest.getCustomRequestHeaders())
                .containsEntry("x-cos-forbid-overwrite", "true")
                .containsEntry("x-cos-acl", "private");

        sdk.copyEtag = null;
        assertStorageFailure(
                () -> adapter.copyCreateOnly(
                        "portfolio-1234567890", "source.bin", "target.bin"),
                "STORAGE_OBJECT_ALREADY_EXISTS");
        CosServiceException precondition = new CosServiceException("target.bin authorization");
        precondition.setStatusCode(412);
        sdk.copyFailure = precondition;
        assertStorageFailure(
                () -> adapter.copyCreateOnly(
                        "portfolio-1234567890", "source.bin", "target.bin"),
                "STORAGE_OBJECT_ALREADY_EXISTS");
        sdk.copyFailure = new StorageException(
                "STORAGE_OBJECT_ALREADY_EXISTS",
                new IllegalStateException("target.bin Authorization secret-key"));
        assertStorageFailure(
                () -> adapter.copyCreateOnly(
                        "portfolio-1234567890", "source.bin", "target.bin"),
                "STORAGE_OBJECT_ALREADY_EXISTS");
    }

    @Test
    void sdkAdapterUsesHeadThenInclusiveRangeAndClosesResponseExactlyOnce() throws Exception {
        CapturingSdk sdk = new CapturingSdk();
        CloseTrackingInputStream response = tracking("ort".getBytes(StandardCharsets.UTF_8));
        AtomicInteger responseCloses = new AtomicInteger();
        sdk.head = new SdkHeadMetadata(9, "application/octet-stream", "etag");
        sdk.read = new SdkReadResponse(
                response, 3, 9, "application/octet-stream", "etag", () -> {
                    responseCloses.incrementAndGet();
                    response.close();
                });
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());

        StorageRead read = adapter.open(
                "portfolio-1234567890", "asset.bin", Optional.of(new ByteRange(1, 3)));

        assertThat(sdk.headCalls).isOne();
        assertThat(sdk.getRequest.getRange()).containsExactly(1, 3);
        assertThat(sdk.getRequest.getMatchingETagConstraints()).containsExactly("etag");
        assertThat(read.inputStream().readAllBytes())
                .isEqualTo("ort".getBytes(StandardCharsets.UTF_8));
        assertThat(read.totalLength()).isEqualTo(9);
        assertThat(read.range()).contains(new ByteRange(1, 3));
        read.close();
        read.close();
        assertThat(responseCloses).hasValue(1);
        assertThat(response.closeCount()).isOne();
    }

    @Test
    void sdkAdapterUsesSingleGetForFullObjectAndValidatesItsSnapshot() throws Exception {
        CapturingSdk sdk = new CapturingSdk();
        byte[] bytes = "full".getBytes(StandardCharsets.UTF_8);
        CloseTrackingInputStream response = tracking(bytes);
        AtomicInteger responseCloses = new AtomicInteger();
        sdk.read = new SdkReadResponse(
                response, bytes.length, bytes.length, "application/octet-stream", "etag", () -> {
                    responseCloses.incrementAndGet();
                    response.close();
                });
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());

        try (StorageRead read = adapter.open(
                "portfolio-1234567890", "asset.bin", Optional.empty())) {
            assertThat(sdk.headCalls).isZero();
            assertThat(sdk.getCalls).isOne();
            assertThat(sdk.getRequest.getRange()).isNull();
            assertThat(sdk.getRequest.getMatchingETagConstraints()).isEmpty();
            assertThat(read.inputStream().readAllBytes()).isEqualTo(bytes);
            assertThat(read.totalLength()).isEqualTo(bytes.length);
            assertThat(read.contentLength()).isEqualTo(bytes.length);
            assertThat(read.range()).isEmpty();
            assertThat(read.contentType()).isEqualTo("application/octet-stream");
            assertThat(read.etag()).isEqualTo("etag");
        }
        assertThat(responseCloses).hasValue(1);
        assertThat(response.closeCount()).isOne();
    }

    @Test
    void sdkAdapterRejectsUnsatisfiableRangeBeforeGetAndClosesInvalidResponses() throws Exception {
        CapturingSdk sdk = new CapturingSdk();
        sdk.head = new SdkHeadMetadata(3, "application/octet-stream", "etag");
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());

        assertThatThrownBy(() -> adapter.open(
                        "portfolio-1234567890", "asset.bin",
                        Optional.of(new ByteRange(3, 3))))
                .isInstanceOfSatisfying(StorageRangeNotSatisfiableException.class,
                        exception -> assertThat(exception.totalLength()).isEqualTo(3));
        assertThat(sdk.getCalls).isZero();

        CloseTrackingInputStream invalid = tracking(new byte[] {1});
        AtomicInteger closes = new AtomicInteger();
        sdk.read = new SdkReadResponse(
                invalid, 1, 4, "application/octet-stream", "different", () -> {
                    closes.incrementAndGet();
                    invalid.close();
                });
        assertStorageFailure(
                () -> adapter.open(
                        "portfolio-1234567890", "asset.bin",
                        Optional.of(new ByteRange(0, 0))),
                "COS_INVALID_RESPONSE");
        assertThat(closes).hasValue(1);
        assertThat(invalid.closeCount()).isOne();
    }

    @Test
    void sdkAdapterPreservesInvalidResponseCodeWhenCleanupThrowsRuntime() {
        CapturingSdk sdk = new CapturingSdk();
        AtomicInteger closeAttempts = new AtomicInteger();
        sdk.head = new SdkHeadMetadata(3, "application/octet-stream", "etag");
        sdk.read = new SdkReadResponse(
                InputStream.nullInputStream(), 1, 3,
                "application/octet-stream", "different", () -> {
                    closeAttempts.incrementAndGet();
                    throw new IllegalStateException("asset key Authorization secret");
                });
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());

        assertStorageFailure(
                () -> adapter.open(
                        "portfolio-1234567890", "asset.bin",
                        Optional.of(new ByteRange(0, 0))),
                "COS_INVALID_RESPONSE");
        assertThat(closeAttempts).hasValue(1);
    }

    @Test
    void sdkResponseStreamSanitizesEverySdkFailureAndRequiresCloseAction() throws Exception {
        InputStream leaking = new InputStream() {
            @Override
            public int read() {
                throw new IllegalStateException("asset key Authorization secret");
            }

            @Override
            public int read(byte[] destination, int offset, int length) throws IOException {
                throw new IOException("asset key Authorization secret");
            }

            @Override
            public long skip(long count) {
                throw new IllegalStateException("asset key Authorization secret");
            }

            @Override
            public int available() throws IOException {
                throw new IOException("asset key Authorization secret");
            }
        };
        SdkResponseInputStream stream = new SdkResponseInputStream(new SdkReadResponse(
                leaking, 1, 1, "application/octet-stream", "etag",
                () -> { throw new IOException("asset key Authorization secret"); }));

        IOException first = assertSanitizedReadFailure(stream::read);
        IOException second = assertSanitizedReadFailure(stream::read);
        assertThat(second).isNotSameAs(first);
        assertSanitizedReadFailure(() -> stream.read(new byte[1], 0, 1));
        assertSanitizedReadFailure(() -> stream.skip(1));
        assertSanitizedReadFailure(stream::available);
        assertSanitizedReadFailure(stream::close);

        SdkResponseInputStream runtimeClose = new SdkResponseInputStream(new SdkReadResponse(
                InputStream.nullInputStream(), 0, 0, "application/octet-stream", "etag",
                () -> { throw new IllegalStateException("Authorization secret"); }));
        assertSanitizedReadFailure(runtimeClose::close);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SdkReadResponse(
                        InputStream.nullInputStream(), 0, 0,
                        "application/octet-stream", "etag", null))
                .withMessage("COS response close action is required");
    }

    @Test
    void realSdkClosesObjectAndSanitizesMetadataExtractionFailures() {
        AtomicInteger closes = new AtomicInteger();
        COSObject object = new COSObject() {
            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        ObjectMetadata leakingMetadata = new ObjectMetadata() {
            @Override
            public long getInstanceLength() {
                throw new IllegalStateException("asset key Authorization secret");
            }
        };
        leakingMetadata.setContentLength(1);
        leakingMetadata.setContentType("application/octet-stream");
        leakingMetadata.setHeader("ETag", "etag");
        object.setObjectMetadata(leakingMetadata);
        object.setObjectContent(InputStream.nullInputStream());

        assertStorageFailure(() -> RealQcloudCosSdk.snapshot(object), "COS_INVALID_RESPONSE");
        assertThat(closes).hasValue(1);
    }

    @Test
    void sdkAdapterBuildsGetOnlyHostSignedRequestAndUsesHardenedHttpsClientConfig()
            throws Exception {
        CapturingSdk sdk = new CapturingSdk();
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());
        Instant expiration = NOW.plusSeconds(300);

        URI signed = adapter.signGet(
                "portfolio-1234567890", "asset.jpg", expiration);

        assertThat(signed).isEqualTo(URI.create(
                "https://portfolio-1234567890.cos.ap-guangzhou.myqcloud.com/asset.jpg?sign=x"));
        assertThat(sdk.signRequest.getMethod()).isEqualTo(HttpMethodName.GET);
        assertThat(sdk.signRequest.getExpiration()).isEqualTo(Date.from(expiration));
        assertThat(sdk.signHost).isTrue();

        ClientConfig config = QcloudCosClientAdapter.clientConfig(properties());
        assertThat(config.getRegion().getRegionName()).isEqualTo("ap-guangzhou");
        assertThat(config.getHttpProtocol()).isEqualTo(HttpProtocol.https);
        assertThat(config.getEndpointBuilder()).isInstanceOf(RegionEndpointBuilder.class);
        assertThat(config.getEndpointResolver()).isInstanceOf(DefaultEndpointResolver.class);
        assertThat(config.getEndPointSuffix()).isNull();
        assertThat(config.getHttpProxyIp()).isNull();
        assertThat(config.getHttpProxyPort()).isZero();
        assertThat(config.getProxyUsername()).isNull();
        assertThat(config.getProxyPassword()).isNull();
        assertThat(config.useBasicAuth()).isFalse();
        assertThat(config.getErrorLogStatusCodeThresh()).isEqualTo(Integer.MAX_VALUE);
        assertThat(config.isPrintShutdownStackTrace()).isFalse();
    }

    @Test
    void sdkAdapterSanitizesDateConversionOverflowBeforeSigning() {
        CapturingSdk sdk = new CapturingSdk();
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());

        assertStorageFailure(
                () -> adapter.signGet("portfolio-1234567890", "asset.jpg", Instant.MAX),
                "COS_SIGN_FAILED");
        assertThat(sdk.signRequest).isNull();
    }

    @Test
    void sdkAdapterSelectsPermanentOrSessionCredentialsAndShutsDownExactlyOnce() {
        assertThat(QcloudCosClientAdapter.credentials(properties()))
                .isInstanceOf(BasicCOSCredentials.class)
                .extracting("COSAccessKeyId", "COSSecretKey")
                .containsExactly("secret-id", "secret-key");
        TencentCosProperties temporary = new TencentCosProperties(
                "ap-guangzhou", "portfolio-1234567890", "temporary-id", "temporary-key",
                "temporary-token");
        assertThat(QcloudCosClientAdapter.credentials(temporary))
                .isInstanceOfSatisfying(BasicSessionCredentials.class, credentials -> {
                    assertThat(credentials.getCOSAccessKeyId()).isEqualTo("temporary-id");
                    assertThat(credentials.getCOSSecretKey()).isEqualTo("temporary-key");
                    assertThat(credentials.getSessionToken()).isEqualTo("temporary-token");
                });

        CapturingSdk sdk = new CapturingSdk();
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());
        adapter.close();
        adapter.close();
        assertThat(sdk.shutdownCalls).isOne();
    }

    @Test
    void sdkAdapterSanitizesShutdownFailureAndNeverRetriesIt() {
        CapturingSdk sdk = new CapturingSdk();
        sdk.shutdownFailure = new IllegalStateException("Authorization secret");
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());

        assertStorageFailure(adapter::close, "COS_SHUTDOWN_FAILED");
        adapter.close();

        assertThat(sdk.shutdownCalls).isOne();
    }

    @Test
    void sdkAdapterRejectsEveryCrossBucketCallBeforeTheSdkBoundary() {
        CapturingSdk sdk = new CapturingSdk();
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());
        String otherBucket = "other-1234567890";

        assertInvalidCosBucket(() -> adapter.putCreateOnly(
                otherBucket, "asset.bin", new java.io.ByteArrayInputStream(new byte[1]),
                1, "application/octet-stream"));
        assertInvalidCosBucket(() -> adapter.open(otherBucket, "asset.bin", Optional.empty()));
        assertInvalidCosBucket(() -> adapter.signGet(
                otherBucket, "asset.jpg", NOW.plusSeconds(60)));
        assertInvalidCosBucket(() -> adapter.exists(otherBucket, "asset.bin"));
        assertInvalidCosBucket(() -> adapter.copyCreateOnly(
                otherBucket, "source.bin", "target.bin"));
        assertInvalidCosBucket(() -> adapter.delete(otherBucket, "asset.bin"));

        assertThat(sdk.putRequest).isNull();
        assertThat(sdk.headCalls).isZero();
        assertThat(sdk.getCalls).isZero();
        assertThat(sdk.getRequest).isNull();
        assertThat(sdk.signRequest).isNull();
        assertThat(sdk.copyRequest).isNull();
    }

    @Test
    void stagingInitializationFailureIsSanitizedBeforeTheServiceCanBeUsed() {
        FakeCosClient client = new FakeCosClient();
        Path unsafeRoot = temporaryDirectory.toAbsolutePath().getRoot();

        assertStorageFailure(
                () -> new TencentCosStorageService(
                        client, properties(), CLOCK, unsafeRoot),
                "COS_WRITE_FAILED");
    }

    @Test
    void scratchCleanupStreamsTheWholeRootButDeletesOnlyTheOldestBoundedCandidates()
            throws Exception {
        Path scratchRoot = stagingRoot();
        FakeCosClient client = new FakeCosClient();
        int limit = TencentCosStorageService.SCRATCH_CLEANUP_DELETE_LIMIT;
        List<Path> eligible = createCrashScratchResidues(
                client, scratchRoot, limit + 3);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        for (int index = 0; index < eligible.size(); index++) {
            Files.setLastModifiedTime(
                    eligible.get(index),
                    FileTime.from(cutoff.minusSeconds(index + 1L)));
        }
        List<Path> young = createCrashScratchResidues(
                client, scratchRoot, limit + 7);
        for (int index = 0; index < young.size(); index++) {
            Files.setLastModifiedTime(
                    young.get(index), FileTime.from(cutoff.plusSeconds(index + 1L)));
        }
        Path unknown = scratchRoot.resolve("000-unknown-residue");
        Files.write(unknown, new byte[] {1});
        assertThat(client.putCalls).isZero();

        try (TencentCosStorageService cleanup = new TencentCosStorageService(
                client, properties(), CLOCK, scratchRoot)) {
            StagingCleanupResult first = cleanup.cleanupScratch(cutoff);
            assertThat(first.deleted()).isEqualTo(limit);
            assertThat(first.scanned()).isEqualTo(
                    2L * limit + 11L + 1L /* root marker */);
            assertThat(first.candidates()).isEqualTo(limit + 3L);
            assertThat(first.elapsed().isNegative()).isFalse();
            assertThat(eligible.subList(0, 3)).allMatch(Files::exists);
            assertThat(eligible.subList(3, eligible.size())).noneMatch(Files::exists);
            assertThat(young).allMatch(Files::exists);
            assertThat(unknown).exists();

            assertThat(cleanup.cleanupScratch(cutoff).deleted()).isEqualTo(3);
            assertThat(eligible).noneMatch(Files::exists);
            assertThat(cleanup.cleanupScratch(cutoff).deleted()).isZero();
        }
        assertThat(client.putCalls).isZero();
        assertThat(client.openCalls).isZero();
        assertThat(client.existsCalls).isZero();
        assertThat(client.copyCalls).isZero();
        assertThat(client.deleteCalls).isZero();
        assertThat(client.signCalls).isZero();
    }

    @Test
    void scratchScanCeilingFailureOccursBeforeAnyLocalOrRemoteDeletion() throws Exception {
        Path scratchRoot = stagingRoot();
        FakeCosClient client = new FakeCosClient();
        List<Path> residue = createCrashScratchResidues(client, scratchRoot, 3);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        for (Path path : residue) {
            Files.setLastModifiedTime(path, FileTime.from(cutoff));
        }

        try (TencentCosStorageService cleanup = new TencentCosStorageService(
                client,
                properties(),
                CLOCK,
                scratchRoot,
                TencentCosStorageService.StagingObserver.NOOP,
                2)) {
            assertStorageFailure(
                    () -> cleanup.cleanupScratch(cutoff),
                    "COS_STAGING_CLEANUP_FAILED");
        }
        assertThat(residue).allMatch(Files::exists);
        assertThat(client.putCalls).isZero();
        assertThat(client.openCalls).isZero();
        assertThat(client.existsCalls).isZero();
        assertThat(client.copyCalls).isZero();
        assertThat(client.deleteCalls).isZero();
        assertThat(client.signCalls).isZero();
    }

    @Test
    void deterministicScratchIdentityGuardIsRecoveredAfterReleaseRefusal()
            throws Exception {
        Path scratchRoot = stagingRoot();
        FakeCosClient client = new FakeCosClient();
        Path residue = createCrashScratchResidues(client, scratchRoot, 1).get(0);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        Files.setLastModifiedTime(residue, FileTime.from(cutoff));
        AtomicInteger releases = new AtomicInteger();
        TencentCosStorageService.StagingObserver observer =
                new TencentCosStorageService.StagingObserver() {
                    @Override
                    public void afterStageClosed(Path path) {}

                    @Override
                    public void beforeCleanup(Path path) {}

                    @Override
                    public void beforeScavengeGuardRelease(
                            Path guard, Path target) throws IOException {
                        if (releases.incrementAndGet() == 1) {
                            throw new IOException("simulated guard release refusal");
                        }
                    }
                };

        try (TencentCosStorageService cleanup = new TencentCosStorageService(
                client, properties(), CLOCK, scratchRoot, observer)) {
            assertStorageFailure(
                    () -> cleanup.cleanupScratch(cutoff),
                    "COS_STAGING_CLEANUP_FAILED");
            assertThat(residue).doesNotExist();
            Path guard = scratchRoot.resolve(
                    "@cleanup-identity-" + residue.getFileName());
            assertThat(guard).exists();

            assertThat(cleanup.cleanupScratch(cutoff).deleted()).isOne();
            assertThat(guard).doesNotExist();
            assertThat(releases).hasValue(1);
        }
    }

    @Test
    void scratchCleanupRejectsAnUnsafeCanonicalResidueWithoutRemoteCalls()
            throws Exception {
        Path scratchRoot = stagingRoot();
        FakeCosClient client = new FakeCosClient();
        try (TencentCosStorageService cleanup = new TencentCosStorageService(
                client, properties(), CLOCK, scratchRoot)) {
            Path unsafe = scratchRoot.resolve(
                    "@part-11111111-1111-4111-8111-111111111111");
            Files.createDirectory(unsafe);

            assertStorageFailure(
                    () -> cleanup.cleanupScratch(
                            Instant.parse("2026-07-15T20:00:00Z")),
                    "COS_STAGING_CLEANUP_FAILED");
            assertThat(unsafe).exists();
        }
        assertThat(client.putCalls).isZero();
        assertThat(client.openCalls).isZero();
        assertThat(client.existsCalls).isZero();
        assertThat(client.copyCalls).isZero();
        assertThat(client.deleteCalls).isZero();
        assertThat(client.signCalls).isZero();
    }

    @Test
    void interruptedScratchCleanupFailsWithoutDeletingOrCallingCos() throws Exception {
        Path scratchRoot = stagingRoot();
        FakeCosClient client = new FakeCosClient();
        List<Path> residue = createCrashScratchResidues(client, scratchRoot, 1);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        Files.setLastModifiedTime(residue.get(0), FileTime.from(cutoff));

        try (TencentCosStorageService cleanup = new TencentCosStorageService(
                client, properties(), CLOCK, scratchRoot)) {
            Thread.currentThread().interrupt();
            try {
                assertStorageFailure(
                        () -> cleanup.cleanupScratch(cutoff),
                        "COS_STAGING_CLEANUP_FAILED");
            } finally {
                assertThat(Thread.interrupted()).isTrue();
            }
        }
        assertThat(residue.get(0)).exists();
        assertThat(client.putCalls).isZero();
        assertThat(client.openCalls).isZero();
        assertThat(client.existsCalls).isZero();
        assertThat(client.copyCalls).isZero();
        assertThat(client.deleteCalls).isZero();
        assertThat(client.signCalls).isZero();
    }

    @Test
    void nonProdConfigurationWiresOnlyLocalStorageWithoutCosCredentials() {
        int index = 0;
        for (String profile : List.of("", "dev", "test")) {
            Path localRoot = stagingRoot()
                    .resolve("non-prod-" + index++)
                    .resolve("media");
            ApplicationContextRunner runner = new ApplicationContextRunner()
                    .withUserConfiguration(StorageConfiguration.class)
                    .withPropertyValues(
                            "portfolio.storage.local.root=" + localRoot,
                            "portfolio.storage.default-provider=LOCAL");
            if (!profile.isEmpty()) {
                runner = runner.withPropertyValues("spring.profiles.active=" + profile);
            }
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(LocalStorageProperties.class);
                assertThat(context).hasSingleBean(LocalStorageService.class);
                assertThat(context).hasSingleBean(StorageDefaults.class);
                assertThat(context).hasSingleBean(StorageRouter.class);
                assertThat(context).doesNotHaveBean(TencentCosProperties.class);
                assertThat(context).doesNotHaveBean(QcloudCosClientAdapter.class);
                assertThat(context).doesNotHaveBean(TencentCosStorageService.class);
                assertThat(context).doesNotHaveBean(CosSdkLogSilencer.class);
                assertThat(context).doesNotHaveBean(CosAdapterFactory.class);
                assertThat(context.getBean(StorageRouter.class).defaultWriter().provider())
                        .isEqualTo(StorageProvider.LOCAL);
            });
            assertThat(localRoot.resolveSibling("cos-scratch")).doesNotExist();
        }
    }

    @Test
    void cosStagingRootUsesTheDedicatedNormalizedConfiguredRoot() {
        Path localRoot = stagingRoot().resolve("media");
        Path configuredScratch = stagingRoot().resolve("scratch/../cos-scratch");

        assertThat(StorageConfiguration.cosStagingRoot(
                        configuredScratch.toString(),
                        new LocalStorageProperties(localRoot)))
                .isEqualTo(configuredScratch.toAbsolutePath().normalize());
    }

    @Test
    void cosStagingRootMustBeDedicatedAndDisjointFromLocalObjectStorage() {
        Path localRoot = stagingRoot().resolve("media");

        for (Path unsafe : List.of(
                localRoot,
                localRoot.resolve("scratch"),
                localRoot.getParent())) {
            assertStorageFailure(
                    () -> StorageConfiguration.cosStagingRoot(
                            unsafe.toString(),
                            new LocalStorageProperties(localRoot)),
                    "COS_WRITE_FAILED");
        }
    }

    @Test
    void prodConfigurationRequiresAnExplicitCosScratchRootBeforeAdapterCreation() {
        AtomicInteger adapterCreations = new AtomicInteger();
        Path localRoot = stagingRoot().resolve("missing-scratch").resolve("media");
        CosAdapterFactory factory = properties -> {
            adapterCreations.incrementAndGet();
            return new QcloudCosClientAdapter(new CapturingSdk(), properties);
        };

        new ApplicationContextRunner()
                .withUserConfiguration(StorageConfiguration.class)
                .withBean(Clock.class, () -> CLOCK)
                .withBean(CosSdkLogSilencer.class,
                        () -> new CosSdkLogSilencer((loggerName, level) -> {}))
                .withBean(CosAdapterFactory.class, () -> factory)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "portfolio.storage.local.root=" + localRoot,
                        "portfolio.storage.default-provider=TENCENT_COS",
                        "portfolio.storage.cos.region=ap-guangzhou",
                        "portfolio.storage.cos.bucket=portfolio-1234567890",
                        "portfolio.storage.cos.secret-id=secret-id",
                        "portfolio.storage.cos.secret-key=secret-key")
                .run(context -> assertThat(context).hasFailed());

        assertThat(adapterCreations).hasValue(0);
    }

    @Test
    void prodConfigurationSilencesExactSdkLoggersBeforeConstructingClient() {
        List<String> events = new ArrayList<>();
        CosSdkLogSilencer silencer = new CosSdkLogSilencer(
                (loggerName, level) -> events.add(loggerName + "=" + level));
        CapturingSdk sdk = new CapturingSdk();
        CosAdapterFactory factory = properties -> {
            events.add("create");
            return new QcloudCosClientAdapter(sdk, properties);
        };

        QcloudCosClientAdapter adapter = new StorageConfiguration().tencentCosClient(
                properties(), silencer, factory, stagingRoot().resolve("dedicated"));

        assertThat(events).containsExactly(
                "com.qcloud.cos=" + LogLevel.OFF,
                "com.qcloud.cos.http.DefaultCosHttpClient=" + LogLevel.OFF,
                "com.qcloud.cos.COSClient=" + LogLevel.OFF,
                "org.apache.http=" + LogLevel.OFF,
                "org.apache.http.headers=" + LogLevel.OFF,
                "org.apache.http.wire=" + LogLevel.OFF,
                "org.apache.http.impl.execchain.MainClientExec=" + LogLevel.OFF,
                "org.apache.http.impl.execchain.ProtocolExec=" + LogLevel.OFF,
                "create");
        adapter.close();
    }

    @Test
    void validProdPermanentAndSessionGraphsUseInjectedAdapterAndCloseItOnce() throws Exception {
        int index = 0;
        for (String sessionToken : List.of("", "temporary-token")) {
            CapturingSdk sdk = new CapturingSdk();
            List<TencentCosProperties> createdWith = new ArrayList<>();
            List<String> events = new ArrayList<>();
            Path localRoot = stagingRoot()
                    .resolve("valid-prod-" + index++)
                    .resolve("media");
            CosSdkLogSilencer silencer = new CosSdkLogSilencer(
                    (loggerName, level) -> events.add(loggerName + "=" + level));
            CosAdapterFactory factory = properties -> {
                createdWith.add(properties);
                events.add("create");
                return new QcloudCosClientAdapter(sdk, properties);
            };

            new ApplicationContextRunner()
                    .withUserConfiguration(StorageConfiguration.class)
                    .withBean(Clock.class, () -> CLOCK)
                    .withBean(CosSdkLogSilencer.class, () -> silencer)
                    .withBean(CosAdapterFactory.class, () -> factory)
                    .withPropertyValues(
                            "spring.profiles.active=prod",
                            "portfolio.storage.local.root=" + localRoot,
                            "portfolio.storage.cos.staging-root="
                                    + localRoot.resolveSibling("cos-scratch"),
                            "portfolio.storage.default-provider=TENCENT_COS",
                            "portfolio.storage.cos.region=ap-guangzhou",
                            "portfolio.storage.cos.bucket=portfolio-1234567890",
                            "portfolio.storage.cos.secret-id=secret-id",
                            "portfolio.storage.cos.secret-key=secret-key",
                            "portfolio.storage.cos.session-token=" + sessionToken)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(LocalStorageService.class);
                        assertThat(context).hasSingleBean(LocalStorageProperties.class);
                        assertThat(context).hasSingleBean(StorageDefaults.class);
                        assertThat(context).hasSingleBean(TencentCosProperties.class);
                        assertThat(context).hasSingleBean(QcloudCosClientAdapter.class);
                        assertThat(context).hasSingleBean(CosClientPort.class);
                        assertThat(context).hasSingleBean(TencentCosStorageService.class);
                        assertThat(context).hasSingleBean(StorageRouter.class);
                        assertThat(context).hasSingleBean(CosSdkLogSilencer.class);
                        assertThat(context).hasSingleBean(CosAdapterFactory.class);
                        assertThat(context.getBeansOfType(StorageService.class)).hasSize(2);
                        StorageRouter router = context.getBean(StorageRouter.class);
                        assertThat(router.defaultWriter().provider())
                                .isEqualTo(StorageProvider.TENCENT_COS);
                        assertThat(router.require(StorageProvider.LOCAL))
                                .isInstanceOf(LocalStorageService.class);
                        assertThat(router.require(StorageProvider.TENCENT_COS))
                                .isInstanceOf(TencentCosStorageService.class);
                    });

            assertThat(createdWith).hasSize(1);
            assertThat(createdWith.get(0).sessionToken())
                    .isEqualTo(sessionToken.isEmpty() ? null : sessionToken);
            assertThat(events).containsExactly(
                    "com.qcloud.cos=" + LogLevel.OFF,
                    "com.qcloud.cos.http.DefaultCosHttpClient=" + LogLevel.OFF,
                    "com.qcloud.cos.COSClient=" + LogLevel.OFF,
                    "org.apache.http=" + LogLevel.OFF,
                    "org.apache.http.headers=" + LogLevel.OFF,
                    "org.apache.http.wire=" + LogLevel.OFF,
                    "org.apache.http.impl.execchain.MainClientExec=" + LogLevel.OFF,
                    "org.apache.http.impl.execchain.ProtocolExec=" + LogLevel.OFF,
                    "create");
            assertThat(sdk.shutdownCalls).isOne();
            assertOwnerOnlyDirectory(StorageConfiguration.cosStagingRoot(
                    localRoot.resolveSibling("cos-scratch").toString(),
                    new LocalStorageProperties(localRoot)));
        }
    }

    @Test
    void prodConfigurationRejectsMissingOrInvalidValuesBeforeClientWithoutEchoingThem() {
        AtomicInteger adapterCreations = new AtomicInteger();
        CosAdapterFactory factory = properties -> {
            adapterCreations.incrementAndGet();
            return new QcloudCosClientAdapter(new CapturingSdk(), properties);
        };
        CosSdkLogSilencer silencer = new CosSdkLogSilencer((loggerName, level) -> {});
        Path localRoot = stagingRoot().resolve("invalid-prod").resolve("media");
        ApplicationContextRunner base = new ApplicationContextRunner()
                .withUserConfiguration(StorageConfiguration.class)
                .withBean(Clock.class, () -> CLOCK)
                .withBean(CosSdkLogSilencer.class, () -> silencer)
                .withBean(CosAdapterFactory.class, () -> factory)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "portfolio.storage.local.root=" + localRoot,
                        "portfolio.storage.cos.staging-root="
                                + localRoot.resolveSibling("cos-scratch"),
                        "portfolio.storage.default-provider=TENCENT_COS");

        base.run(context -> assertThat(context).hasFailed());
        assertThat(adapterCreations).hasValue(0);

        base.withPropertyValues(
                        "portfolio.storage.cos.region=INVALID-region-marker",
                        "portfolio.storage.cos.bucket=portfolio-1234567890",
                        "portfolio.storage.cos.secret-id=secret-id-marker",
                        "portfolio.storage.cos.secret-key=secret-key-marker",
                        "portfolio.storage.cos.session-token=session-token-marker")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .doesNotContain(
                                    "INVALID-region-marker",
                                    "secret-id-marker",
                                    "secret-key-marker",
                                    "session-token-marker");
                });
        assertThat(adapterCreations).hasValue(0);

        base.withPropertyValues(
                        "portfolio.storage.cos.region=ap-guangzhou",
                        "portfolio.storage.cos.bucket=portfolio-1234567890",
                        "portfolio.storage.cos.secret-id= ",
                        "portfolio.storage.cos.secret-key=secret-key-marker")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .doesNotContain("secret-key-marker");
                });
        assertThat(adapterCreations).hasValue(0);
    }

    @Test
    void realLoggingSystemStaysFailClosedAfterTheProdAdapterStops() {
        LoggingSystem loggingSystem = LoggingSystem.get(
                StorageConfiguration.class.getClassLoader());
        List<LogLevel> originalLevels = PROTECTED_LOGGERS.stream()
                .map(name -> configuredLevel(loggingSystem, name))
                .toList();
        CapturingSdk sdk = new CapturingSdk();
        sdk.shutdownObserver = () -> assertConfiguredLevels(loggingSystem, LogLevel.OFF);
        AtomicInteger adapterCreations = new AtomicInteger();
        CosAdapterFactory factory = properties -> {
            adapterCreations.incrementAndGet();
            assertThat(PROTECTED_LOGGERS).allSatisfy(name ->
                    assertThat(configuredLevel(loggingSystem, name))
                            .as(name)
                            .isEqualTo(LogLevel.OFF));
            return new QcloudCosClientAdapter(sdk, properties);
        };
        Path localRoot = stagingRoot().resolve("real-logger-prod").resolve("media");

        try {
            PROTECTED_LOGGERS.forEach(name -> loggingSystem.setLogLevel(name, LogLevel.DEBUG));
            new ApplicationContextRunner()
                    .withUserConfiguration(StorageConfiguration.class)
                    .withBean(Clock.class, () -> CLOCK)
                    .withBean(CosAdapterFactory.class, () -> factory)
                    .withPropertyValues(
                            "spring.profiles.active=prod",
                            "portfolio.storage.local.root=" + localRoot,
                            "portfolio.storage.cos.staging-root="
                                    + localRoot.resolveSibling("cos-scratch"),
                            "portfolio.storage.default-provider=TENCENT_COS",
                            "portfolio.storage.cos.region=ap-guangzhou",
                            "portfolio.storage.cos.bucket=portfolio-1234567890",
                            "portfolio.storage.cos.secret-id=secret-id",
                            "portfolio.storage.cos.secret-key=secret-key")
                    .run(context -> assertThat(context).hasNotFailed());
            assertConfiguredLevels(loggingSystem, LogLevel.OFF);
        } finally {
            for (int index = 0; index < PROTECTED_LOGGERS.size(); index++) {
                loggingSystem.setLogLevel(PROTECTED_LOGGERS.get(index), originalLevels.get(index));
            }
        }

        assertThat(adapterCreations).hasValue(1);
        assertThat(sdk.shutdownCalls).isOne();
    }

    @Test
    void prodRefreshRollbackClosesAnAlreadyCreatedAdapterExactlyOnce() throws Exception {
        Path localRoot = stagingRoot().resolve("rollback").resolve("media");
        Path invalidStagingRoot = localRoot.resolveSibling("invalid-cos-scratch");
        Files.createDirectories(invalidStagingRoot.getParent());
        Files.writeString(invalidStagingRoot, "not-a-directory", StandardCharsets.UTF_8);
        CapturingSdk sdk = new CapturingSdk();
        AtomicInteger adapterCreations = new AtomicInteger();
        LoggingSystem loggingSystem = LoggingSystem.get(
                StorageConfiguration.class.getClassLoader());
        sdk.shutdownObserver = () -> assertConfiguredLevels(loggingSystem, LogLevel.OFF);
        List<LogLevel> originalLevels = PROTECTED_LOGGERS.stream()
                .map(name -> configuredLevel(loggingSystem, name))
                .toList();
        CosAdapterFactory factory = properties -> {
            adapterCreations.incrementAndGet();
            assertConfiguredLevels(loggingSystem, LogLevel.OFF);
            return new QcloudCosClientAdapter(sdk, properties);
        };

        try {
            PROTECTED_LOGGERS.forEach(name -> loggingSystem.setLogLevel(name, LogLevel.DEBUG));
            new ApplicationContextRunner()
                    .withUserConfiguration(StorageConfiguration.class)
                    .withBean(Clock.class, () -> CLOCK)
                    .withBean(CosAdapterFactory.class, () -> factory)
                    .withPropertyValues(
                            "spring.profiles.active=prod",
                            "portfolio.storage.local.root=" + localRoot,
                            "portfolio.storage.cos.staging-root=" + invalidStagingRoot,
                            "portfolio.storage.default-provider=TENCENT_COS",
                            "portfolio.storage.cos.region=ap-guangzhou",
                            "portfolio.storage.cos.bucket=portfolio-1234567890",
                            "portfolio.storage.cos.secret-id=secret-id",
                            "portfolio.storage.cos.secret-key=secret-key")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(failureMessages(context.getStartupFailure()))
                                .doesNotContain(
                                        invalidStagingRoot.toString(),
                                        "secret-id",
                                        "secret-key");
                    });
            assertConfiguredLevels(loggingSystem, LogLevel.OFF);
        } finally {
            for (int index = 0; index < PROTECTED_LOGGERS.size(); index++) {
                loggingSystem.setLogLevel(PROTECTED_LOGGERS.get(index), originalLevels.get(index));
            }
        }

        assertThat(adapterCreations).hasValue(1);
        assertThat(sdk.shutdownCalls).isOne();
    }

    @Test
    void overlappingProdContextsKeepLogsOffThroughoutAndAfterShutdown() {
        LoggingSystem loggingSystem = LoggingSystem.get(
                StorageConfiguration.class.getClassLoader());
        List<LogLevel> originalLevels = PROTECTED_LOGGERS.stream()
                .map(name -> configuredLevel(loggingSystem, name))
                .toList();
        CapturingSdk firstSdk = new CapturingSdk();
        CapturingSdk secondSdk = new CapturingSdk();
        firstSdk.shutdownObserver = () -> assertConfiguredLevels(loggingSystem, LogLevel.OFF);
        secondSdk.shutdownObserver = () -> assertConfiguredLevels(loggingSystem, LogLevel.OFF);

        try {
            PROTECTED_LOGGERS.forEach(name -> loggingSystem.setLogLevel(name, LogLevel.DEBUG));
            prodContextRunner(
                            stagingRoot().resolve("overlap-first").resolve("media"),
                            properties -> new QcloudCosClientAdapter(firstSdk, properties))
                    .run(firstContext -> {
                        assertThat(firstContext).hasNotFailed();
                        assertConfiguredLevels(loggingSystem, LogLevel.OFF);

                        prodContextRunner(
                                        stagingRoot().resolve("overlap-second").resolve("media"),
                                        properties -> new QcloudCosClientAdapter(secondSdk, properties))
                                .run(secondContext -> {
                                    assertThat(secondContext).hasNotFailed();
                                    assertConfiguredLevels(loggingSystem, LogLevel.OFF);
                                });

                        assertThat(secondSdk.shutdownCalls).isOne();
                        assertThat(firstSdk.shutdownCalls).isZero();
                        assertConfiguredLevels(loggingSystem, LogLevel.OFF);
                    });

            assertThat(firstSdk.shutdownCalls).isOne();
            assertConfiguredLevels(loggingSystem, LogLevel.OFF);
        } finally {
            for (int index = 0; index < PROTECTED_LOGGERS.size(); index++) {
                loggingSystem.setLogLevel(PROTECTED_LOGGERS.get(index), originalLevels.get(index));
            }
        }
    }

    @Test
    void initialLoggerSilencingFailureRollsBackAndLeavesTheGuardReusable() {
        ControlledLoggingSystem loggingSystem = new ControlledLoggingSystem(LogLevel.DEBUG);
        CosSdkLogSilencer failed = new CosSdkLogSilencer(loggingSystem);
        loggingSystem.failOnSetNumber(3);

        assertThatThrownBy(failed::silence)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LOG_LEVEL_SET_FAILED");
        assertConfiguredLevels(loggingSystem, LogLevel.DEBUG);
        failed.close();

        CosSdkLogSilencer recovered = new CosSdkLogSilencer(loggingSystem);
        recovered.silence();
        assertConfiguredLevels(loggingSystem, LogLevel.OFF);
        recovered.close();
        assertConfiguredLevels(loggingSystem, LogLevel.DEBUG);
    }

    @Test
    void laterLoggerSilencingFailureKeepsTheExistingLeaseOff() {
        ControlledLoggingSystem loggingSystem = new ControlledLoggingSystem(LogLevel.DEBUG);
        CosSdkLogSilencer first = new CosSdkLogSilencer(loggingSystem);
        CosSdkLogSilencer failedSecond = new CosSdkLogSilencer(loggingSystem);

        first.silence();
        loggingSystem.failOnSetNumber(3);
        assertThatThrownBy(failedSecond::silence)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LOG_LEVEL_SET_FAILED");
        assertConfiguredLevels(loggingSystem, LogLevel.OFF);

        failedSecond.close();
        first.close();
        assertConfiguredLevels(loggingSystem, LogLevel.DEBUG);
    }

    @Test
    void loggerRestoreFailureClearsTheGuardForAControlledRetry() {
        ControlledLoggingSystem loggingSystem = new ControlledLoggingSystem(LogLevel.DEBUG);
        CosSdkLogSilencer failed = new CosSdkLogSilencer(loggingSystem);
        failed.silence();
        loggingSystem.failOnSetNumber(1);

        assertThatThrownBy(failed::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LOG_LEVEL_SET_FAILED");
        assertThat(configuredLevel(loggingSystem, PROTECTED_LOGGERS.get(0)))
                .isEqualTo(LogLevel.OFF);
        assertThat(PROTECTED_LOGGERS.subList(1, PROTECTED_LOGGERS.size()))
                .allSatisfy(name -> assertThat(configuredLevel(loggingSystem, name))
                        .isEqualTo(LogLevel.DEBUG));

        PROTECTED_LOGGERS.forEach(name -> loggingSystem.setLogLevel(name, LogLevel.DEBUG));
        CosSdkLogSilencer recovered = new CosSdkLogSilencer(loggingSystem);
        recovered.silence();
        recovered.close();
        assertConfiguredLevels(loggingSystem, LogLevel.DEBUG);
    }

    @Test
    void failedSdkShutdownKeepsTheLoggerLeaseOffAndClosesTheAdapter() {
        ControlledLoggingSystem loggingSystem = new ControlledLoggingSystem(LogLevel.DEBUG);
        CosSdkLogSilencer silencer = new CosSdkLogSilencer(loggingSystem);
        CapturingSdk sdk = new CapturingSdk();
        sdk.shutdownFailure = new IllegalStateException("sensitive shutdown detail");
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());

        silencer.silence();
        silencer.blockRestoreUntilClientStops();
        adapter.onShutdownSuccess(silencer::clientStopped);
        try {
            assertStorageFailure(adapter::close, "COS_SHUTDOWN_FAILED");
            silencer.close();
            assertConfiguredLevels(loggingSystem, LogLevel.OFF);
            assertStorageFailure(
                    () -> adapter.exists(properties().bucket(), "asset.bin"),
                    "COS_CLIENT_CLOSED");
            assertThat(sdk.existsCalls).isZero();
            assertThat(sdk.shutdownCalls).isOne();
        } finally {
            silencer.clientStopped();
            silencer.close();
        }
        assertConfiguredLevels(loggingSystem, LogLevel.DEBUG);
    }

    @Test
    void adapterCloseDrainsStartedCallsAndRejectsNewCalls() throws Exception {
        CapturingSdk sdk = new CapturingSdk();
        sdk.existsEntered = new CountDownLatch(1);
        sdk.releaseExists = new CountDownLatch(1);
        sdk.shutdownEntered = new CountDownLatch(1);
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());
        CompletableFuture<Boolean> exists = CompletableFuture.supplyAsync(
                () -> adapter.exists(properties().bucket(), "asset.bin"));
        CompletableFuture<Void> close = null;

        try {
            assertThat(sdk.existsEntered.await(5, TimeUnit.SECONDS)).isTrue();
            close = CompletableFuture.runAsync(adapter::close);
            boolean shutdownOverlappedActiveCall =
                    sdk.shutdownEntered.await(200, TimeUnit.MILLISECONDS);
            sdk.releaseExists.countDown();

            assertThat(shutdownOverlappedActiveCall).isFalse();
            assertThat(exists.get(5, TimeUnit.SECONDS)).isTrue();
            close.get(5, TimeUnit.SECONDS);
            assertThat(sdk.shutdownEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertStorageFailure(
                    () -> adapter.exists(properties().bucket(), "asset.bin"),
                    "COS_CLIENT_CLOSED");
            assertThat(sdk.existsCalls).isOne();
        } finally {
            sdk.releaseExists.countDown();
            exists.cancel(true);
            if (close != null) {
                close.cancel(true);
            }
        }
    }

    @Test
    void adapterCloseWaitsForReturnedReadBeforeShutdownAndLoggerRestore() throws Exception {
        ControlledLoggingSystem loggingSystem = new ControlledLoggingSystem(LogLevel.DEBUG);
        CosSdkLogSilencer silencer = new CosSdkLogSilencer(loggingSystem);
        CapturingSdk sdk = new CapturingSdk();
        AtomicInteger responseCloses = new AtomicInteger();
        sdk.read = new SdkReadResponse(
                tracking("abc".getBytes(StandardCharsets.UTF_8)),
                3,
                3,
                "application/octet-stream",
                "etag",
                responseCloses::incrementAndGet);
        sdk.shutdownEntered = new CountDownLatch(1);
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());
        silencer.silence();
        silencer.blockRestoreUntilClientStops();
        adapter.onShutdownSuccess(silencer::clientStopped);
        StorageRead read = adapter.open(
                properties().bucket(), "asset.bin", Optional.empty());
        CompletableFuture<Void> close = CompletableFuture.runAsync(adapter::close);

        try {
            assertThat(sdk.shutdownEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertConfiguredLevels(loggingSystem, LogLevel.OFF);
            assertStorageFailure(
                    () -> adapter.exists(properties().bucket(), "other.bin"),
                    "COS_CLIENT_CLOSED");
            assertThat(sdk.existsCalls).isZero();

            read.close();
            close.get(5, TimeUnit.SECONDS);
            assertThat(sdk.shutdownCalls).isOne();
            assertThat(responseCloses).hasValue(1);
            silencer.close();
            assertConfiguredLevels(loggingSystem, LogLevel.DEBUG);
        } finally {
            read.close();
            close.cancel(true);
            silencer.clientStopped();
            silencer.close();
        }
    }

    @Test
    void returnedReadCloseFailureStillReleasesTheAdapterLease() throws Exception {
        CapturingSdk sdk = new CapturingSdk();
        AtomicInteger responseCloses = new AtomicInteger();
        sdk.read = new SdkReadResponse(
                tracking("abc".getBytes(StandardCharsets.UTF_8)),
                3,
                3,
                "application/octet-stream",
                "etag",
                () -> {
                    responseCloses.incrementAndGet();
                    throw new IOException("sensitive response close detail");
                });
        sdk.shutdownEntered = new CountDownLatch(1);
        QcloudCosClientAdapter adapter = new QcloudCosClientAdapter(sdk, properties());
        StorageRead read = adapter.open(
                properties().bucket(), "asset.bin", Optional.empty());
        CompletableFuture<Void> close = CompletableFuture.runAsync(adapter::close);

        try {
            assertThat(sdk.shutdownEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThatThrownBy(read::close)
                    .isInstanceOf(IOException.class)
                    .hasMessage("COS_READ_FAILED")
                    .hasNoCause();
            close.get(5, TimeUnit.SECONDS);
            assertThat(sdk.shutdownCalls).isOne();
            assertThat(responseCloses).hasValue(1);
        } finally {
            try {
                read.close();
            } catch (IOException ignored) {
                // The lease was already released by the first fixed close failure.
            }
            close.cancel(true);
        }
    }

    private static void assertPrivateCreateOnly(PutObjectRequest request) {
        assertThat(request.getCannedAcl()).isNull();
        assertThat(request.getAccessControlList()).isNull();
        assertThat(request.getCustomRequestHeaders())
                .containsEntry("x-cos-forbid-overwrite", "true")
                .containsEntry("x-cos-acl", "private");
    }

    private static void assertOwnerOnlyDirectory(Path directory) throws IOException {
        FileStore store = Files.getFileStore(directory);
        if (store.supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            return;
        }
        AclFileAttributeView view = Files.getFileAttributeView(
                directory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        assertThat(view).isNotNull();
        assertThat(view.getAcl()).isNotEmpty().allSatisfy(entry -> {
            assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(entry.principal()).isEqualTo(view.getOwner());
        });
    }

    private static String failureMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            messages.append(current.getClass().getName())
                    .append(':')
                    .append(current.getMessage())
                    .append('\n');
            current = current.getCause();
        }
        return messages.toString();
    }

    private static LogLevel configuredLevel(LoggingSystem loggingSystem, String loggerName) {
        var configuration = loggingSystem.getLoggerConfiguration(loggerName);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }

    private static void assertConfiguredLevels(
            LoggingSystem loggingSystem, LogLevel expectedLevel) {
        assertThat(PROTECTED_LOGGERS).allSatisfy(name ->
                assertThat(configuredLevel(loggingSystem, name))
                        .as(name)
                        .isEqualTo(expectedLevel));
    }

    private ApplicationContextRunner prodContextRunner(
            Path localRoot, CosAdapterFactory factory) {
        return new ApplicationContextRunner()
                .withUserConfiguration(StorageConfiguration.class)
                .withBean(Clock.class, () -> CLOCK)
                .withBean(CosAdapterFactory.class, () -> factory)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "portfolio.storage.local.root=" + localRoot,
                        "portfolio.storage.cos.staging-root="
                                + localRoot.resolveSibling("cos-scratch"),
                        "portfolio.storage.default-provider=TENCENT_COS",
                        "portfolio.storage.cos.region=ap-guangzhou",
                        "portfolio.storage.cos.bucket=portfolio-1234567890",
                        "portfolio.storage.cos.secret-id=secret-id",
                        "portfolio.storage.cos.secret-key=secret-key");
    }

    private TencentCosStorageService service(FakeCosClient client, Clock clock) {
        return new TencentCosStorageService(
                client, properties(), clock, stagingRoot());
    }

    private List<Path> createCrashScratchResidues(
            FakeCosClient client, Path scratchRoot, int count) throws Exception {
        List<Path> residues = new ArrayList<>();
        TencentCosStorageService.StagingObserver crash =
                new TencentCosStorageService.StagingObserver() {
                    @Override
                    public void afterStageClosed(Path path) {
                        residues.add(path);
                        throw new SimulatedScratchCrash();
                    }

                    @Override
                    public void beforeCleanup(Path path) throws IOException {
                        throw new IOException("simulated cleanup interruption");
                    }
                };
        try (TencentCosStorageService writer = new TencentCosStorageService(
                client, properties(), CLOCK, scratchRoot, crash)) {
            for (int index = 0; index < count; index++) {
                try {
                    writer.put(
                            "scratch-residue-" + index + ".jpg",
                            tracking(new byte[] {1}),
                            1,
                            "image/jpeg");
                    throw new AssertionError("simulated crash was not observed");
                } catch (SimulatedScratchCrash expected) {
                    // The process died after durable local staging and before remote publication.
                }
            }
        }
        assertThat(residues).hasSize(count).allMatch(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS));
        return List.copyOf(residues);
    }

    private TencentCosStorageService service(
            FakeCosClient client,
            Clock clock,
            TencentCosStorageService.StagingObserver observer) {
        return new TencentCosStorageService(
                client, properties(), clock, stagingRoot(), observer);
    }

    private Path stagingRoot() {
        try {
            FileStore store = Files.getFileStore(temporaryDirectory);
            if (store.supportsFileAttributeView(AclFileAttributeView.class)
                    && !store.supportsFileAttributeView("posix")) {
                if (stagingBoundary == null) {
                    Path home = Path.of(System.getProperty("user.home"))
                            .toAbsolutePath()
                            .normalize();
                    stagingBoundary = Files.createTempDirectory(home, ".portfolio-cos-test-");
                    externalCleanup.add(stagingBoundary);
                }
                return stagingBoundary.resolve("staging");
            }
            return temporaryDirectory.resolve("staging");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void assertStorageFailure(ThrowingOperation operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(StorageException.class,
                        exception -> {
                            assertThat(exception.code()).isEqualTo(code);
                            assertThat(exception.getCause()).isNull();
                            assertThat(exception.getSuppressed()).isEmpty();
                        })
                .hasMessage(code)
                .hasMessageNotContaining("asset")
                .hasMessageNotContaining("myqcloud");
    }

    private static IOException assertSanitizedReadFailure(ThrowingOperation operation) {
        final IOException[] captured = new IOException[1];
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(IOException.class, exception -> {
                    captured[0] = exception;
                    assertThat(exception.getCause()).isNull();
                })
                .hasMessage("COS_READ_FAILED")
                .hasMessageNotContaining("asset")
                .hasMessageNotContaining("Authorization")
                .hasMessageNotContaining("secret");
        return captured[0];
    }

    private static void assertInvalidCosBucket(ThrowingOperation operation) {
        assertThatIllegalArgumentException()
                .isThrownBy(operation::run)
                .withMessage("Invalid COS bucket")
                .withNoCause();
    }

    private static void assertNoStagingAliases(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.list(root)) {
            assertThat(paths.filter(path -> path.getFileName().toString().startsWith("@part-")))
                    .isEmpty();
        }
    }

    private static long stagingAliases(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        try (var paths = Files.list(root)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("@part-"))
                    .count();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static CloseTrackingInputStream tracking(byte[] bytes) {
        return new CloseTrackingInputStream(bytes);
    }

    private static TencentCosProperties properties() {
        return new TencentCosProperties(
                "ap-guangzhou", "portfolio-1234567890", "secret-id", "secret-key", null);
    }

    private static final class ControlledLoggingSystem extends LoggingSystem {
        private final Map<String, LogLevel> levels = new LinkedHashMap<>();
        private int setCalls;
        private int failAtSetCall = -1;

        private ControlledLoggingSystem(LogLevel initialLevel) {
            PROTECTED_LOGGERS.forEach(name -> levels.put(name, initialLevel));
        }

        void failOnSetNumber(int ordinalFromNow) {
            failAtSetCall = setCalls + ordinalFromNow;
        }

        @Override
        public void beforeInitialize() {}

        @Override
        public void setLogLevel(String loggerName, LogLevel level) {
            setCalls++;
            if (setCalls == failAtSetCall) {
                failAtSetCall = -1;
                throw new IllegalStateException("LOG_LEVEL_SET_FAILED");
            }
            levels.put(loggerName, level);
        }

        @Override
        public LoggerConfiguration getLoggerConfiguration(String loggerName) {
            LogLevel level = levels.get(loggerName);
            return new LoggerConfiguration(loggerName, level, level);
        }
    }

    private static final class FakeCosClient implements CosClientPort {
        private Instant lastExpiration;
        private URI signedUri;
        private int signCalls;
        private CloseTrackingInputStream callerInput;
        private int callerCloseCountAtPut;
        private int putCalls;
        private byte[] lastPutBytes;
        private boolean overridePutResult;
        private StoredObject putResult;
        private RuntimeException putFailure;
        private int openCalls;
        private Optional<ByteRange> lastOpenRange;
        private boolean overrideOpenResult;
        private StorageRead openResult;
        private RuntimeException openFailure;
        private int existsCalls;
        private boolean existsResult;
        private RuntimeException existsFailure;
        private int copyCalls;
        private String lastCopySource;
        private String lastCopyTarget;
        private RuntimeException copyFailure;
        private int deleteCalls;
        private RuntimeException deleteFailure;
        private RuntimeException signFailure;

        @Override
        public StoredObject putCreateOnly(
                String bucket,
                String key,
                InputStream input,
                long contentLength,
                String contentType) {
            putCalls++;
            callerCloseCountAtPut = callerInput == null ? -1 : callerInput.closeCount();
            try {
                lastPutBytes = input.readAllBytes();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            if (putFailure != null) {
                throw putFailure;
            }
            if (overridePutResult) {
                return putResult;
            }
            return new StoredObject(
                    StorageProvider.TENCENT_COS,
                    bucket,
                    "ap-guangzhou",
                    key,
                    contentLength,
                    contentType,
                    "etag");
        }

        @Override
        public StorageRead open(String bucket, String key, Optional<ByteRange> range) {
            openCalls++;
            lastOpenRange = range;
            if (openFailure != null) {
                throw openFailure;
            }
            if (overrideOpenResult) {
                return openResult;
            }
            byte[] all = "portfolio".getBytes(StandardCharsets.UTF_8);
            ByteRange served = range.orElse(null);
            int start = served == null ? 0 : Math.toIntExact(served.startInclusive());
            int end = served == null ? all.length : Math.toIntExact(served.endInclusive() + 1);
            byte[] bytes = java.util.Arrays.copyOfRange(all, start, end);
            return new StorageRead(
                    tracking(bytes),
                    all.length,
                    range,
                    bytes.length,
                    key.endsWith(".bin") ? "application/octet-stream" : "text/plain",
                    "etag");
        }

        @Override
        public URI signGet(String bucket, String key, Instant expiresAt) {
            signCalls++;
            lastExpiration = expiresAt;
            if (signFailure != null) {
                throw signFailure;
            }
            if (signedUri != null) {
                return signedUri;
            }
            long ttl = Duration.between(NOW, expiresAt).toSeconds();
            return URI.create("https://" + bucket + ".cos.ap-guangzhou.myqcloud.com/"
                    + key + "?ttl=" + ttl);
        }

        @Override
        public boolean exists(String bucket, String key) {
            existsCalls++;
            if (existsFailure != null) {
                throw existsFailure;
            }
            return existsResult;
        }

        @Override
        public void copyCreateOnly(String bucket, String sourceKey, String targetKey) {
            copyCalls++;
            lastCopySource = sourceKey;
            lastCopyTarget = targetKey;
            if (copyFailure != null) {
                throw copyFailure;
            }
        }

        @Override
        public void delete(String bucket, String key) {
            deleteCalls++;
            if (deleteFailure != null) {
                throw deleteFailure;
            }
        }
    }

    private static final class SimulatedScratchCrash extends Error {
        private SimulatedScratchCrash() {
            super("simulated process crash", null, false, false);
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static final class CapturingSdk implements QcloudCosSdk {
        private PutObjectRequest putRequest;
        private CopyObjectRequest copyRequest;
        private GetObjectRequest getRequest;
        private GeneratePresignedUrlRequest signRequest;
        private boolean signHost;
        private String putEtag = "etag";
        private RuntimeException putFailure;
        private String copyEtag = "etag";
        private RuntimeException copyFailure;
        private SdkHeadMetadata head;
        private int headCalls;
        private SdkReadResponse read;
        private int getCalls;
        private int existsCalls;
        private int shutdownCalls;
        private RuntimeException shutdownFailure;
        private Runnable shutdownObserver = () -> {};
        private CountDownLatch existsEntered;
        private CountDownLatch releaseExists;
        private CountDownLatch shutdownEntered;

        @Override
        public String put(PutObjectRequest request) {
            putRequest = request;
            if (putFailure != null) {
                throw putFailure;
            }
            return putEtag;
        }

        @Override
        public SdkHeadMetadata head(String bucket, String key) {
            headCalls++;
            return head;
        }

        @Override
        public SdkReadResponse get(GetObjectRequest request) {
            getCalls++;
            getRequest = request;
            return read;
        }

        @Override
        public boolean exists(String bucket, String key) {
            existsCalls++;
            if (existsEntered != null) {
                existsEntered.countDown();
            }
            if (releaseExists != null) {
                try {
                    if (!releaseExists.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("EXISTS_WAIT_TIMED_OUT");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("EXISTS_WAIT_INTERRUPTED");
                }
            }
            return true;
        }

        @Override
        public String copy(CopyObjectRequest request) {
            copyRequest = request;
            if (copyFailure != null) {
                throw copyFailure;
            }
            return copyEtag;
        }

        @Override
        public URL sign(GeneratePresignedUrlRequest request, boolean signHost) {
            signRequest = request;
            this.signHost = signHost;
            try {
                return URI.create(
                                "https://portfolio-1234567890.cos.ap-guangzhou.myqcloud.com/"
                                        + "asset.jpg?sign=x")
                        .toURL();
            } catch (java.net.MalformedURLException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public void delete(String bucket, String key) {}

        @Override
        public void shutdown() {
            shutdownCalls++;
            shutdownObserver.run();
            if (shutdownEntered != null) {
                shutdownEntered.countDown();
            }
            if (shutdownFailure != null) {
                throw shutdownFailure;
            }
        }
    }

    private static class CloseTrackingInputStream extends java.io.ByteArrayInputStream {
        private final AtomicInteger closes = new AtomicInteger();

        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
            super.close();
        }

        int closeCount() {
            return closes.get();
        }
    }

    private static final class CloseFailingInputStream extends CloseTrackingInputStream {
        private CloseFailingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            super.close();
            throw new IOException("synthetic close failure");
        }
    }

    private static final class ProbeTrackingInputStream extends InputStream {
        private final byte[] bytes;
        private int position;
        private int bytesReturned;
        private int closeCount;

        private ProbeTrackingInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public int read() {
            if (position == bytes.length) {
                return -1;
            }
            bytesReturned++;
            return bytes[position++] & 0xff;
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (position == bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - position);
            System.arraycopy(bytes, position, destination, offset, count);
            position += count;
            bytesReturned += count;
            return count;
        }

        @Override
        public void close() {
            closeCount++;
        }

        int bytesReturned() {
            return bytesReturned;
        }

        int closeCount() {
            return closeCount;
        }
    }
}
