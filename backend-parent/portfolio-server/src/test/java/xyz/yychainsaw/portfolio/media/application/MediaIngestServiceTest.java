package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.storage.ByteRange;
import xyz.yychainsaw.portfolio.media.storage.StorageRead;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

@ExtendWith(OutputCaptureExtension.class)
class MediaIngestServiceTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SECOND_ASSET_ID =
            UUID.fromString("11111111-2222-3333-4444-666666666666");
    private static final UUID ACTOR_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final String SHA256 = sha256(PDF);
    private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesOutsideTransactionThenPersistsExactAssetJobAuditAndDefensiveView() {
        TestContext context = context(TransactionMode.NORMAL, false, ASSET_ID);
        CloseCountingInputStream inbound = new CloseCountingInputStream(PDF);

        MediaAssetView view = context.service().ingest(command(inbound), ACTOR_ID);

        String stagingKey = MediaObjectKeys.stagingKey(ASSET_ID, SHA256, "application/pdf");
        String originalKey = MediaObjectKeys.originalKey(ASSET_ID, SHA256, "application/pdf");
        assertThat(context.storage().putKeys()).containsExactly(stagingKey);
        assertThat(context.storage().putObservedTransaction()).isFalse();
        assertThat(context.storage().putInputCloseCalls()).isOne();
        assertThat(context.storage().publishedBytes()).containsExactly(PDF);
        assertThat(context.storage().deleteKeys()).isEmpty();
        assertThat(inbound.closeCalls()).isOne();
        assertDirectoryEmpty();

        ArgumentCaptor<MediaAssetRecord.Insert> insertCaptor =
                ArgumentCaptor.forClass(MediaAssetRecord.Insert.class);
        verify(context.assets()).insertProcessing(insertCaptor.capture());
        MediaAssetRecord.Insert insert = insertCaptor.getValue();
        assertThat(insert.id()).isEqualTo(ASSET_ID);
        assertThat(insert.provider()).isEqualTo(StorageProvider.LOCAL);
        assertThat(insert.bucket()).isNull();
        assertThat(insert.region()).isNull();
        assertThat(insert.objectKey()).isEqualTo(originalKey);
        assertThat(insert.objectKey()).doesNotStartWith("staging/");
        assertThat(insert.originalFilename()).isEqualTo("document.pdf");
        assertThat(insert.sha256()).isEqualTo(SHA256);
        assertThat(insert.width()).isNull();
        assertThat(insert.height()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> payloadCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(context.jobs()).enqueue(
                eq("FINALIZE_MEDIA_UPLOAD"),
                eq("media-finalize:" + ASSET_ID),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .isEqualTo(Map.of("assetId", ASSET_ID.toString()));

        ArgumentCaptor<AuditCommand> auditCaptor = ArgumentCaptor.forClass(AuditCommand.class);
        verify(context.audit()).record(auditCaptor.capture());
        AuditCommand audit = auditCaptor.getValue();
        assertThat(audit.actorAdminId()).isEqualTo(ACTOR_ID);
        assertThat(audit.action()).isEqualTo("MEDIA_UPLOAD");
        assertThat(audit.targetType()).isEqualTo("MEDIA_ASSET");
        assertThat(audit.targetId()).isEqualTo(ASSET_ID.toString());
        assertThat(audit.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(audit.traceId()).matches("[0-9a-f]{32}");
        assertThat(audit.metadata()).isEmpty();

        InOrder order = inOrder(context.assets(), context.jobs(), context.audit());
        order.verify(context.assets()).insertProcessing(any());
        order.verify(context.jobs()).enqueue(any(), any(), anyMap());
        order.verify(context.audit()).record(any());
        assertThat(view.id()).isEqualTo(ASSET_ID);
        assertThat(view.status()).isEqualTo("PROCESSING");
        assertThat(view.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("provider", "bucket", "region", "objectKey", "etag");
    }

    @Test
    void repeatedRequestsCreateDistinctImmutableAssetIdentities() {
        TestContext context = context(
                TransactionMode.NORMAL, false, ASSET_ID, SECOND_ASSET_ID);

        MediaAssetView first = context.service().ingest(
                command(new CloseCountingInputStream(PDF)), ACTOR_ID);
        MediaAssetView second = context.service().ingest(
                command(new CloseCountingInputStream(PDF)), ACTOR_ID);

        assertThat(first.id()).isEqualTo(ASSET_ID);
        assertThat(second.id()).isEqualTo(SECOND_ASSET_ID);
        assertThat(context.storage().putKeys())
                .containsExactly(
                        MediaObjectKeys.stagingKey(ASSET_ID, SHA256, "application/pdf"),
                        MediaObjectKeys.stagingKey(
                                SECOND_ASSET_ID, SHA256, "application/pdf"));
    }

    @Test
    void rejectsAmbientTransactionBeforeReadOrStorageAndClosesInboundOnce() {
        TestContext context = context(TransactionMode.NORMAL, false, ASSET_ID);
        CloseCountingInputStream inbound = new CloseCountingInputStream(PDF);
        TransactionTemplate outer = new TransactionTemplate(new ScenarioTransactionManager(
                TransactionMode.NORMAL));

        assertThatThrownBy(() -> outer.executeWithoutResult(status ->
                        context.service().ingest(command(inbound), ACTOR_ID)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("media ingest requires no ambient transaction")
                .hasNoCause();
        assertThat(inbound.readCalls()).isZero();
        assertThat(inbound.closeCalls()).isOne();
        assertThat(context.storage().putKeys()).isEmpty();
        verifyNoInteractions(context.assets(), context.jobs(), context.audit());
    }

    @Test
    void thrownPutHasUnknownPublicationOutcomeAndNeverDeletesStaging() {
        TestContext context = context(TransactionMode.NORMAL, false, ASSET_ID);
        context.storage().putFailure = new IllegalStateException("provider secret");

        assertUploadFailed(() -> context.service().ingest(
                command(new CloseCountingInputStream(PDF)), ACTOR_ID));

        assertThat(context.storage().putKeys()).hasSize(1);
        assertThat(context.storage().putInputCloseCalls()).isOne();
        assertThat(context.storage().deleteKeys()).isEmpty();
        verifyNoInteractions(context.assets(), context.jobs(), context.audit());
        assertDirectoryEmpty();
    }

    @Test
    void everyStoredObjectIdentityMismatchIsUnknownOwnershipAndNeverDeletesStaging() {
        String stagingKey = MediaObjectKeys.stagingKey(
                ASSET_ID, SHA256, "application/pdf");
        List<StoredObject> mismatches = List.of(
                storedObject(
                        StorageProvider.TENCENT_COS,
                        stagingKey,
                        PDF.length,
                        "application/pdf"),
                storedObject(
                        StorageProvider.LOCAL,
                        "staging/other/" + SHA256 + ".pdf",
                        PDF.length,
                        "application/pdf"),
                storedObject(
                        StorageProvider.LOCAL,
                        stagingKey,
                        PDF.length + 1L,
                        "application/pdf"),
                storedObject(
                        StorageProvider.LOCAL,
                        stagingKey,
                        PDF.length,
                        "image/png"));

        for (StoredObject mismatch : mismatches) {
            TestContext context = context(TransactionMode.NORMAL, false, ASSET_ID);
            context.storage().returnedObject = mismatch;

            assertUploadFailed(() -> context.service().ingest(
                    command(new CloseCountingInputStream(PDF)), ACTOR_ID));

            assertThat(context.storage().deleteKeys()).isEmpty();
            verifyNoInteractions(context.assets(), context.jobs(), context.audit());
            assertDirectoryEmpty();
        }
    }

    @Test
    void storageOwnsAndClosesThePublishedInputExactlyOnce() throws Exception {
        MediaFileInspector inspector = mock(MediaFileInspector.class);
        InspectedMedia media = mock(InspectedMedia.class);
        StorageRouter router = mock(StorageRouter.class);
        RecordingStorage storage = new RecordingStorage();
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        AuditService audit = mock(AuditService.class);
        CloseCountingInputStream storageInput = new CloseCountingInputStream(PDF);
        when(router.defaultWriter()).thenReturn(storage);
        when(inspector.inspect(any())).thenReturn(media);
        when(media.mimeType()).thenReturn("application/pdf");
        when(media.sha256()).thenReturn(SHA256);
        when(media.byteSize()).thenReturn((long) PDF.length);
        when(media.originalFilename()).thenReturn("document.pdf");
        when(media.openStream()).thenReturn(storageInput);
        doThrow(new IllegalStateException("database refusal"))
                .when(assets).insertProcessing(any());
        MediaIngestService service = new MediaIngestService(
                inspector,
                router,
                assets,
                jobs,
                audit,
                new TransactionTemplate(new ScenarioTransactionManager(TransactionMode.NORMAL)),
                () -> ASSET_ID);

        assertUploadFailed(() -> service.ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        assertThat(storageInput.closeCalls()).isOne();
        verify(media).close();
    }

    @Test
    void knownRollbackRollsBackCallbackAndDeletesOwnedStagingExactlyOnce() {
        TestContext context = context(TransactionMode.NORMAL, false, ASSET_ID);
        doThrow(new IllegalStateException("database secret"))
                .when(context.assets()).insertProcessing(any());

        assertUploadFailed(() -> context.service().ingest(
                command(new CloseCountingInputStream(PDF)), ACTOR_ID));

        assertThat(context.storage().deleteKeys())
                .containsExactly(MediaObjectKeys.stagingKey(
                        ASSET_ID, SHA256, "application/pdf"));
        verifyNoInteractions(context.jobs(), context.audit());
    }

    @Test
    void normalReturnWithLocalRollbackDeletesOwnedStagingAndNeverReturnsView() {
        TestContext context = context(TransactionMode.NORMAL, true, ASSET_ID);

        assertUploadFailed(() -> context.service().ingest(
                command(new CloseCountingInputStream(PDF)), ACTOR_ID));

        assertThat(context.storage().deleteKeys()).hasSize(1);
        verify(context.assets()).insertProcessing(any());
        verify(context.jobs()).enqueue(any(), any(), anyMap());
        verify(context.audit()).record(any());
    }

    @Test
    void transactionBeginFailureDeletesOwnedStagingBecauseCallbackNeverEntered() {
        TestContext context = context(TransactionMode.BEGIN_FAIL, false, ASSET_ID);

        assertUploadFailed(() -> context.service().ingest(
                command(new CloseCountingInputStream(PDF)), ACTOR_ID));

        assertThat(context.storage().deleteKeys()).hasSize(1);
        verifyNoInteractions(context.assets(), context.jobs(), context.audit());
    }

    @Test
    void commitPhaseUnknownRetainsStagingForFinalizerOrScavenger() {
        TestContext context = context(TransactionMode.COMMIT_FAIL, false, ASSET_ID);

        assertUploadFailed(() -> context.service().ingest(
                command(new CloseCountingInputStream(PDF)), ACTOR_ID));

        assertThat(context.storage().deleteKeys()).isEmpty();
        verify(context.assets()).insertProcessing(any());
        verify(context.jobs()).enqueue(any(), any(), anyMap());
        verify(context.audit()).record(any());
    }

    @Test
    void rollbackFailureWithoutCompletionRetainsStaging(CapturedOutput output) {
        TestContext context = context(TransactionMode.ROLLBACK_FAIL, false, ASSET_ID);
        doThrow(new IllegalStateException("database secret"))
                .when(context.assets()).insertProcessing(any());

        assertUploadFailed(() -> context.service().ingest(
                command(new CloseCountingInputStream(PDF)), ACTOR_ID));

        assertThat(context.storage().deleteKeys()).isEmpty();
        assertThat(output).doesNotContain("database secret");
    }

    @Test
    void cleanupFailureIsCauseFreeAndDoesNotRepeatDelete() {
        TestContext context = context(TransactionMode.NORMAL, false, ASSET_ID);
        doThrow(new IllegalStateException("database secret"))
                .when(context.assets()).insertProcessing(any());
        context.storage().deleteFailure = new IllegalStateException("provider secret");

        assertUploadFailed(() -> context.service().ingest(
                command(new CloseCountingInputStream(PDF)), ACTOR_ID));

        assertThat(context.storage().deleteKeys()).hasSize(1);
    }

    @Test
    void tempDeleteRefusalDeletesOwnedStagingOnceAndRetriesTempOnlyOnce() throws Exception {
        MediaFileInspector inspector = mock(MediaFileInspector.class);
        InspectedMedia media = mock(InspectedMedia.class);
        StorageRouter router = mock(StorageRouter.class);
        RecordingStorage storage = new RecordingStorage();
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        AuditService audit = mock(AuditService.class);
        when(router.defaultWriter()).thenReturn(storage);
        when(inspector.inspect(any())).thenReturn(media);
        when(media.mimeType()).thenReturn("application/pdf");
        when(media.sha256()).thenReturn(SHA256);
        when(media.byteSize()).thenReturn((long) PDF.length);
        when(media.originalFilename()).thenReturn("document.pdf");
        when(media.openStream()).thenReturn(new ByteArrayInputStream(PDF));
        doThrow(new IOException("private path refusal")).when(media).close();
        MediaIngestService service = new MediaIngestService(
                inspector,
                router,
                assets,
                jobs,
                audit,
                new TransactionTemplate(new ScenarioTransactionManager(TransactionMode.NORMAL)),
                () -> ASSET_ID);

        assertUploadFailed(() -> service.ingest(
                command(new CloseCountingInputStream(PDF)), ACTOR_ID));

        assertThat(storage.deleteKeys()).hasSize(1);
        verify(media, times(2)).close();
        verifyNoInteractions(assets, jobs, audit);
    }

    @Test
    void objectKeysAndProvisionalHandlerEnforceExactExternalContract() throws Exception {
        assertThat(MediaObjectKeys.stagingKey(ASSET_ID, SHA256, "image/jpeg"))
                .isEqualTo("staging/" + ASSET_ID + "/" + SHA256 + ".jpg");
        assertThat(MediaObjectKeys.originalKey(ASSET_ID, SHA256, "application/pdf"))
                .isEqualTo("originals/" + ASSET_ID + "/" + SHA256 + ".pdf");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MediaObjectKeys.stagingKey(
                        ASSET_ID, "../request-text", "image/jpeg"));

        FinalizeMediaUploadJobHandler handler = new FinalizeMediaUploadJobHandler();
        var valid = new ObjectMapper().readTree("{\"assetId\":\"" + ASSET_ID + "\"}");
        var extra = new ObjectMapper().readTree(
                "{\"assetId\":\"" + ASSET_ID + "\",\"stagingKey\":\"private/key\"}");
        assertThat(handler.jobType()).isEqualTo("FINALIZE_MEDIA_UPLOAD");
        assertThatIllegalStateException()
                .isThrownBy(() -> handler.handle(valid))
                .withMessage("MEDIA_FINALIZER_NOT_READY")
                .withNoCause();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.handle(extra))
                .withMessage("MEDIA_FINALIZER_PAYLOAD_INVALID")
                .withNoCause();
    }

    private TestContext context(
            TransactionMode mode, boolean rollbackAfterCallback, UUID... generatedIds) {
        MediaFileInspector inspector = new MediaFileInspector(temporaryDirectory);
        StorageRouter router = mock(StorageRouter.class);
        RecordingStorage storage = new RecordingStorage();
        when(router.defaultWriter()).thenReturn(storage);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        AuditService audit = mock(AuditService.class);
        when(assets.insertProcessing(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return complete(invocation.getArgument(0));
        });
        when(assets.toView(any())).thenAnswer(invocation ->
                ((MediaAssetRecord) invocation.getArgument(0)).toView());
        when(jobs.enqueue(any(), any(), anyMap())).thenReturn(UUID.randomUUID());

        ScenarioTransactionManager manager = new ScenarioTransactionManager(mode);
        TransactionTemplate template = rollbackAfterCallback
                ? new RollbackAfterCallbackTemplate(manager)
                : new TransactionTemplate(manager);
        Queue<UUID> ids = new ArrayDeque<>(List.of(generatedIds));
        MediaIngestService service = new MediaIngestService(
                inspector,
                router,
                assets,
                jobs,
                audit,
                template,
                ids::remove);
        return new TestContext(service, storage, assets, jobs, audit);
    }

    private static MediaAssetRecord complete(MediaAssetRecord.Insert insert) {
        return new MediaAssetRecord(
                insert.id(),
                insert.provider(),
                insert.bucket(),
                insert.region(),
                insert.objectKey(),
                insert.originalFilename(),
                insert.mimeType(),
                insert.byteSize(),
                insert.width(),
                insert.height(),
                insert.sha256(),
                MediaStatus.PROCESSING,
                null,
                0,
                NOW,
                NOW);
    }

    private static UploadMediaCommand command(InputStream input) {
        return new UploadMediaCommand(
                "C:\\request\\document.exe", "application/pdf", PDF.length, input);
    }

    private void assertDirectoryEmpty() {
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).isEmpty();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertUploadFailed(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isExactlyInstanceOf(DomainException.class)
                .satisfies(failure -> {
                    DomainException domain = (DomainException) failure;
                    assertThat(domain.code()).isEqualTo("MEDIA_UPLOAD_FAILED");
                    assertThat(domain.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(domain.fieldErrors()).isEmpty();
                    assertThat(domain).hasNoCause();
                    assertThat(domain.getSuppressed()).isEmpty();
                    assertThat(domain.getMessage()).isEqualTo("MEDIA_UPLOAD_FAILED");
                });
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static StoredObject storedObject(
            StorageProvider provider,
            String objectKey,
            long contentLength,
            String contentType) {
        StoredObject stored = mock(StoredObject.class);
        when(stored.provider()).thenReturn(provider);
        when(stored.objectKey()).thenReturn(objectKey);
        when(stored.contentLength()).thenReturn(contentLength);
        when(stored.contentType()).thenReturn(contentType);
        return stored;
    }

    private record TestContext(
            MediaIngestService service,
            RecordingStorage storage,
            MediaAssetRepository assets,
            BackgroundJobService jobs,
            AuditService audit) {}

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private enum TransactionMode {
        NORMAL,
        BEGIN_FAIL,
        COMMIT_FAIL,
        ROLLBACK_FAIL
    }

    private static final class ScenarioTransactionManager
            extends AbstractPlatformTransactionManager {
        private final TransactionMode mode;

        private ScenarioTransactionManager(TransactionMode mode) {
            this.mode = mode;
            setRollbackOnCommitFailure(false);
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (mode == TransactionMode.BEGIN_FAIL) {
                throw new CannotCreateTransactionException("database begin secret");
            }
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (mode == TransactionMode.COMMIT_FAIL) {
                throw new TransactionSystemException("database commit secret");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            if (mode == TransactionMode.ROLLBACK_FAIL) {
                throw new TransactionSystemException("database rollback secret");
            }
        }
    }

    private static final class RollbackAfterCallbackTemplate extends TransactionTemplate {
        private RollbackAfterCallbackTemplate(ScenarioTransactionManager manager) {
            super(manager);
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            return super.execute(status -> {
                T result = action.doInTransaction(status);
                status.setRollbackOnly();
                return result;
            });
        }
    }

    private static final class RecordingStorage implements StorageService {
        private final List<String> putKeys = new ArrayList<>();
        private final List<String> deleteKeys = new ArrayList<>();
        private byte[] publishedBytes = new byte[0];
        private Boolean putObservedTransaction;
        private int putInputCloseCalls;
        private RuntimeException putFailure;
        private RuntimeException deleteFailure;
        private StoredObject returnedObject;

        @Override
        public StorageProvider provider() {
            return StorageProvider.LOCAL;
        }

        @Override
        public StoredObject put(
                String objectKey,
                InputStream input,
                long contentLength,
                String contentType) {
            putKeys.add(objectKey);
            putObservedTransaction =
                    TransactionSynchronizationManager.isActualTransactionActive();
            try {
                publishedBytes = input.readAllBytes();
            } catch (IOException failure) {
                throw new IllegalStateException("storage read failed");
            } finally {
                try {
                    input.close();
                    putInputCloseCalls++;
                } catch (IOException failure) {
                    throw new IllegalStateException("storage close failed");
                }
            }
            if (putFailure != null) {
                throw putFailure;
            }
            if (returnedObject != null) {
                return returnedObject;
            }
            return new StoredObject(
                    StorageProvider.LOCAL,
                    null,
                    null,
                    objectKey,
                    contentLength,
                    contentType,
                    "test-etag");
        }

        @Override
        public void delete(String objectKey) {
            deleteKeys.add(objectKey);
            if (deleteFailure != null) {
                throw deleteFailure;
            }
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

        private List<String> putKeys() {
            return List.copyOf(putKeys);
        }

        private List<String> deleteKeys() {
            return List.copyOf(deleteKeys);
        }

        private byte[] publishedBytes() {
            return publishedBytes.clone();
        }

        private Boolean putObservedTransaction() {
            return putObservedTransaction;
        }

        private int putInputCloseCalls() {
            return putInputCloseCalls;
        }
    }

    private static final class CloseCountingInputStream extends FilterInputStream {
        private int readCalls;
        private int closeCalls;

        private CloseCountingInputStream(byte[] bytes) {
            super(new ByteArrayInputStream(bytes));
        }

        @Override
        public int read() throws IOException {
            readCalls++;
            return super.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            readCalls++;
            return super.read(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            super.close();
        }

        private int readCalls() {
            return readCalls;
        }

        private int closeCalls() {
            return closeCalls;
        }
    }
}
