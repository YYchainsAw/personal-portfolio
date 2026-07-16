package xyz.yychainsaw.portfolio.media.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditOutcome;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.application.MediaIngestService;
import xyz.yychainsaw.portfolio.media.application.MediaObjectKeys;
import xyz.yychainsaw.portfolio.media.application.UploadMediaCommand;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.storage.ByteRange;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRead;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

@SpringBootTest
class MediaAssetRepositoryTest extends PostgresIntegrationTestBase {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String SHA256 = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-16T00:00:00Z");
    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired MediaAssetRepository assets;
    @Autowired BackgroundJobService jobs;
    @Autowired AuditService audit;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcClient jdbc;
    @Autowired MediaIngestService ingest;
    @MockitoBean StorageRouter storageRouter;

    @Test
    void mediaStatusAndDefensiveAdminViewExposeOnlyTheFrozenPublicShape() {
        assertThat(MediaStatus.values())
                .extracting(Enum::name)
                .containsExactly("PROCESSING", "READY", "FAILED", "ARCHIVED", "PENDING_DELETE");

        MediaAssetView view = new MediaAssetView(
                ASSET_ID,
                "photo.jpg",
                "image/jpeg",
                123,
                10,
                20,
                SHA256,
                "PROCESSING",
                0,
                NOW,
                NOW);

        assertThat(view)
                .extracting(
                        MediaAssetView::id,
                        MediaAssetView::originalFilename,
                        MediaAssetView::mimeType,
                        MediaAssetView::byteSize,
                        MediaAssetView::width,
                        MediaAssetView::height,
                        MediaAssetView::sha256,
                        MediaAssetView::status,
                        MediaAssetView::version,
                        MediaAssetView::createdAt,
                        MediaAssetView::updatedAt)
                .containsExactly(
                        ASSET_ID, "photo.jpg", "image/jpeg", 123L, 10, 20,
                        SHA256, "PROCESSING", 0L, NOW, NOW);
        assertThat(MediaAssetView.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "id", "originalFilename", "mimeType", "byteSize", "width", "height",
                        "sha256", "status", "version", "createdAt", "updatedAt");
    }

    @Test
    void valuesRejectUnknownStatesAndInvalidPersistedShapes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MediaAssetView(
                        ASSET_ID, "photo.jpg", "image/jpeg", 1, 1, 1, SHA256,
                        "processing", 0, NOW, NOW));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MediaAssetRecord(
                        ASSET_ID,
                        StorageProvider.LOCAL,
                        null,
                        null,
                        MediaObjectKeys.originalKey(ASSET_ID, SHA256, "image/jpeg"),
                        "photo.jpg",
                        "image/jpeg",
                        1,
                        1,
                        1,
                        SHA256,
                        MediaStatus.ARCHIVED,
                        null,
                        0,
                        NOW,
                        NOW));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MediaAssetRecord.Insert(
                        ASSET_ID,
                        StorageProvider.LOCAL,
                        null,
                        null,
                        MediaObjectKeys.originalKey(ASSET_ID, SHA256, "application/pdf"),
                        "document.pdf",
                        "application/pdf",
                        1,
                        1,
                        null,
                        SHA256));
    }

    @Test
    void insertIdentityRequiresTheExactFinalKeyAndBoundedCosLocation() {
        String finalKey = MediaObjectKeys.originalKey(ASSET_ID, SHA256, "image/jpeg");
        MediaAssetRecord.Insert local = insert(ASSET_ID, StorageProvider.LOCAL, null, null, finalKey);

        assertThat(local.objectKey()).isEqualTo(finalKey);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> insert(
                        ASSET_ID,
                        StorageProvider.LOCAL,
                        null,
                        null,
                        MediaObjectKeys.stagingKey(ASSET_ID, SHA256, "image/jpeg")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> insert(
                        ASSET_ID,
                        StorageProvider.LOCAL,
                        null,
                        null,
                        MediaObjectKeys.originalKey(UUID.randomUUID(), SHA256, "image/jpeg")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> insert(
                        ASSET_ID,
                        StorageProvider.LOCAL,
                        null,
                        null,
                        "originals/" + ASSET_ID + "/" + SHA256 + ".png"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> insert(
                        ASSET_ID,
                        StorageProvider.TENCENT_COS,
                        "b".repeat(129),
                        "ap-hongkong",
                        finalKey));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> insert(
                        ASSET_ID,
                        StorageProvider.TENCENT_COS,
                        "bucket",
                        "r".repeat(65),
                        finalKey));
    }

    @Test
    void completeReadModelRejectsANonFinalPersistedObjectIdentity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MediaAssetRecord(
                        ASSET_ID,
                        StorageProvider.LOCAL,
                        null,
                        null,
                        MediaObjectKeys.stagingKey(ASSET_ID, SHA256, "image/jpeg"),
                        "photo.jpg",
                        "image/jpeg",
                        123,
                        10,
                        20,
                        SHA256,
                        MediaStatus.PROCESSING,
                        null,
                        0,
                        NOW,
                        NOW));
    }

    @Test
    void runtimeRepositoryRoundTripsV3DefaultsWithHostileSearchPath() {
        UUID assetId = UUID.randomUUID();
        MediaAssetRecord.Insert insert = insert(
                assetId,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(assetId, SHA256, "image/jpeg"));

        try {
            MediaAssetRecord saved = transactions.execute(status -> {
                assertThat(jdbc.sql("select current_user").query(String.class).single())
                        .isEqualTo("test_runtime");
                jdbc.sql("select set_config('search_path', 'pg_catalog', true)")
                        .query(String.class)
                        .single();
                return assets.insertProcessing(insert);
            });

            assertThat(saved).isNotNull();
            assertThat(saved.id()).isEqualTo(assetId);
            assertThat(saved.provider()).isEqualTo(StorageProvider.LOCAL);
            assertThat(saved.objectKey()).isEqualTo(insert.objectKey());
            assertThat(saved.status()).isEqualTo(MediaStatus.PROCESSING);
            assertThat(saved.archivedAt()).isNull();
            assertThat(saved.version()).isZero();
            assertThat(saved.createdAt()).isNotNull();
            assertThat(saved.updatedAt()).isNotNull();
            assertThat(assets.findById(assetId)).contains(saved);
            assertThat(assets.toView(saved))
                    .extracting(MediaAssetView::id, MediaAssetView::status)
                    .containsExactly(assetId, "PROCESSING");
        } finally {
            jdbc.sql("delete from portfolio.media_asset where id=:id")
                    .param("id", assetId)
                    .update();
        }
    }

    @Test
    void assetJobAndAuditCommitTogetherWithRegisteredProductionJobType() {
        UUID assetId = UUID.randomUUID();
        String trace = "media-commit-" + UUID.randomUUID();
        String idempotencyKey = "media-finalize:" + assetId;
        AtomicReference<UUID> jobId = new AtomicReference<>();

        try {
            transactions.executeWithoutResult(status -> {
                assets.insertProcessing(insert(
                        assetId,
                        StorageProvider.LOCAL,
                        null,
                        null,
                        MediaObjectKeys.originalKey(assetId, SHA256, "image/jpeg")));
                jobId.set(jobs.enqueue(
                        "FINALIZE_MEDIA_UPLOAD",
                        idempotencyKey,
                        Map.of("assetId", assetId.toString())));
                audit.record(canonicalAudit(assetId, trace));
            });

            assertThat(count("portfolio.media_asset", "id", assetId)).isOne();
            assertThat(jdbc.sql("""
                            select job_type,
                                   idempotency_key,
                                   status,
                                   payload ->> 'assetId' as asset_id,
                                   payload = jsonb_build_object('assetId', :assetId)
                                       as payload_exact
                            from portfolio.background_job
                            where id=:id
                            """)
                    .param("id", jobId.get())
                    .param("assetId", assetId.toString())
                    .query((resultSet, rowNumber) -> new JobShape(
                            resultSet.getString("job_type"),
                            resultSet.getString("idempotency_key"),
                            resultSet.getString("status"),
                            resultSet.getString("asset_id"),
                            resultSet.getBoolean("payload_exact")))
                    .single())
                    .isEqualTo(new JobShape(
                            "FINALIZE_MEDIA_UPLOAD",
                            idempotencyKey,
                            "PENDING",
                            assetId.toString(),
                            true));
            assertThat(jdbc.sql("""
                            select actor_admin_id,
                                   action,
                                   target_type,
                                   target_id,
                                   outcome,
                                   trace_id,
                                   metadata = '{}'::jsonb as metadata_empty
                            from portfolio.audit_log
                            where trace_id=:trace
                            """)
                    .param("trace", trace)
                    .query((resultSet, rowNumber) -> new AuditShape(
                            resultSet.getObject("actor_admin_id", UUID.class),
                            resultSet.getString("action"),
                            resultSet.getString("target_type"),
                            resultSet.getString("target_id"),
                            resultSet.getString("outcome"),
                            resultSet.getString("trace_id"),
                            resultSet.getBoolean("metadata_empty")))
                    .single())
                    .isEqualTo(new AuditShape(
                            null,
                            "MEDIA_UPLOAD",
                            "MEDIA_ASSET",
                            assetId.toString(),
                            "SUCCESS",
                            trace,
                            true));
        } finally {
            JdbcClient owner = migratorJdbc();
            owner.sql("delete from portfolio.background_job where idempotency_key=:key")
                    .param("key", idempotencyKey)
                    .update();
            owner.sql("delete from portfolio.media_asset where id=:id")
                    .param("id", assetId)
                    .update();
        }
    }

    @Test
    void assetJobAndAuditAllDisappearOnKnownOuterRollback() {
        UUID assetId = UUID.randomUUID();
        String trace = "media-rollback-" + UUID.randomUUID();
        String idempotencyKey = "media-finalize:" + assetId;

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    assets.insertProcessing(insert(
                            assetId,
                            StorageProvider.LOCAL,
                            null,
                            null,
                            MediaObjectKeys.originalKey(assetId, SHA256, "image/jpeg")));
                    jobs.enqueue(
                            "FINALIZE_MEDIA_UPLOAD",
                            idempotencyKey,
                            Map.of("assetId", assetId.toString()));
                    audit.record(canonicalAudit(assetId, trace));
                    throw new IllegalStateException("force rollback");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("portfolio.media_asset", "id", assetId)).isZero();
        assertThat(count("portfolio.background_job", "idempotency_key", idempotencyKey))
                .isZero();
        assertThat(count("portfolio.audit_log", "trace_id", trace)).isZero();
    }

    @Test
    void realRemoteIngestAuditConstraintRollbackRemovesEveryRowAndDeletesOwnedStagingOnce() {
        UUID nonexistentActor = UUID.randomUUID();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(count("portfolio.admin_user", "id", nonexistentActor)).isZero();
        RecordingStorage storage = new RecordingStorage();
        when(storageRouter.defaultWriter()).thenReturn(storage);

        Throwable failure = catchThrowable(() -> ingest.ingest(
                new UploadMediaCommand(
                        "document.pdf",
                        "application/pdf",
                        PDF.length,
                        new ByteArrayInputStream(PDF)),
                nonexistentActor));

        assertThat(storage.putObservedTransaction()).isFalse();
        assertThat(storage.putKeys()).hasSize(1);
        String stagingKey = storage.putKeys().get(0);
        String[] segments = stagingKey.split("/");
        assertThat(segments).hasSize(3);
        assertThat(segments[0]).isEqualTo("staging");
        UUID assetId = UUID.fromString(segments[1]);
        String idempotencyKey = "media-finalize:" + assetId;
        try {
            assertThat(failure)
                    .isExactlyInstanceOf(DomainException.class)
                    .satisfies(thrown -> {
                        DomainException domain = (DomainException) thrown;
                        assertThat(domain.code()).isEqualTo("MEDIA_UPLOAD_FAILED");
                        assertThat(domain.fieldErrors()).isEmpty();
                        assertThat(domain).hasNoCause();
                        assertThat(domain.getSuppressed()).isEmpty();
                    });
            assertThat(count("portfolio.media_asset", "id", assetId)).isZero();
            assertThat(count(
                            "portfolio.background_job", "idempotency_key", idempotencyKey))
                    .isZero();
            assertThat(count("portfolio.audit_log", "target_id", assetId.toString())).isZero();
            assertThat(storage.deleteKeys()).containsExactly(stagingKey);
            assertThat(storage.deleteObservedTransaction()).containsExactly(false);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        } finally {
            JdbcClient owner = migratorJdbc();
            owner.sql("delete from portfolio.background_job where idempotency_key=:key")
                    .param("key", idempotencyKey)
                    .update();
            owner.sql("delete from portfolio.media_asset where id=:id")
                    .param("id", assetId)
                    .update();
        }
    }

    private static MediaAssetRecord.Insert insert(
            UUID id,
            StorageProvider provider,
            String bucket,
            String region,
            String objectKey) {
        return new MediaAssetRecord.Insert(
                id,
                provider,
                bucket,
                region,
                objectKey,
                "photo.jpg",
                "image/jpeg",
                123,
                10,
                20,
                SHA256);
    }

    private static AuditCommand canonicalAudit(UUID assetId, String trace) {
        return new AuditCommand(
                null,
                "MEDIA_UPLOAD",
                "MEDIA_ASSET",
                assetId.toString(),
                AuditOutcome.SUCCESS,
                trace,
                Map.of());
    }

    private int count(String table, String field, Object value) {
        String sql = "select count(*) from " + table + " where " + field + "=:value";
        return jdbc.sql(sql).param("value", value).query(Integer.class).single();
    }

    private record JobShape(
            String jobType,
            String idempotencyKey,
            String status,
            String assetId,
            boolean payloadExact) {}

    private record AuditShape(
            UUID actorAdminId,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String traceId,
            boolean metadataEmpty) {}

    private static final class RecordingStorage implements StorageService {
        private static final String BUCKET = "portfolio-test";
        private static final String REGION = "ap-guangzhou";

        private final List<String> putKeys = new ArrayList<>();
        private final List<String> deleteKeys = new ArrayList<>();
        private final List<Boolean> deleteObservedTransaction = new ArrayList<>();
        private boolean putObservedTransaction;

        @Override
        public StorageProvider provider() {
            return StorageProvider.TENCENT_COS;
        }

        @Override
        public StorageLocation location() {
            return new StorageLocation(StorageProvider.TENCENT_COS, BUCKET, REGION);
        }

        @Override
        public StoredObject put(
                String objectKey,
                InputStream input,
                long contentLength,
                String contentType) {
            putObservedTransaction =
                    TransactionSynchronizationManager.isActualTransactionActive();
            putKeys.add(objectKey);
            try (input) {
                if (!java.util.Arrays.equals(input.readAllBytes(), PDF)) {
                    throw new IllegalStateException("published bytes differ");
                }
            } catch (IOException failure) {
                throw new IllegalStateException("storage read failed");
            }
            return new StoredObject(
                    StorageProvider.TENCENT_COS,
                    BUCKET,
                    REGION,
                    objectKey,
                    contentLength,
                    contentType,
                    "test-etag");
        }

        @Override
        public void delete(String objectKey) {
            deleteObservedTransaction.add(
                    TransactionSynchronizationManager.isActualTransactionActive());
            deleteKeys.add(objectKey);
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

        private List<Boolean> deleteObservedTransaction() {
            return List.copyOf(deleteObservedTransaction);
        }

        private boolean putObservedTransaction() {
            return putObservedTransaction;
        }
    }
}
