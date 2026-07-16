package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

@SpringBootTest(properties = "portfolio.jobs.worker-enabled=false")
@Isolated
class FinalizeMediaDeadLetterIntegrationTest extends PostgresIntegrationTestBase {
    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final OffsetDateTime OLD =
            OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired MediaAssetRepository assets;
    @Autowired BackgroundJobService jobs;
    @Autowired JdbcClient jdbc;

    @Test
    void tenthFailAndLeaseExhaustionAtomicallyMoveProcessingAssetsToFailed() {
        UUID directAsset = UUID.randomUUID();
        UUID leaseAsset = UUID.randomUUID();
        UUID directJob = UUID.randomUUID();
        UUID leaseJob = UUID.randomUUID();
        try {
            assets.insertProcessing(asset(directAsset));
            assets.insertProcessing(asset(leaseAsset));
            insertRunningJob(directJob, directAsset, "direct-owner", OLD.plusYears(100));
            insertRunningJob(leaseJob, leaseAsset, "crashed-owner", OLD);

            assertThat(jobs.fail(
                            directJob,
                            "direct-owner",
                            10,
                            "JOB_HANDLER_FAILED"))
                    .isTrue();
            assertThat(jobStatus(directJob)).isEqualTo("DEAD");
            assertThat(assets.findById(directAsset).orElseThrow().status())
                    .isEqualTo(MediaStatus.FAILED);

            jobs.leaseNext("recovery-owner", Duration.ofMinutes(5));
            assertThat(jobStatus(leaseJob)).isEqualTo("DEAD");
            assertThat(assets.findById(leaseAsset).orElseThrow().status())
                    .isEqualTo(MediaStatus.FAILED);
        } finally {
            cleanup(directJob, directAsset, null);
            cleanup(leaseJob, leaseAsset, null);
        }
    }

    @Test
    void hookDatabaseFailureRollsBackDirectDeadTransitionAndAssetCas() {
        UUID assetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String trigger = null;
        try {
            assets.insertProcessing(asset(assetId));
            insertRunningJob(jobId, assetId, "direct-owner", OLD.plusYears(100));
            trigger = installFailureTrigger(assetId);

            assertThatThrownBy(() -> jobs.fail(
                            jobId,
                            "direct-owner",
                            10,
                            "JOB_HANDLER_FAILED"))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("job dead-letter hook failed")
                    .hasNoCause();

            assertThat(jobStatus(jobId)).isEqualTo("RUNNING");
            assertThat(assets.findById(assetId).orElseThrow().status())
                    .isEqualTo(MediaStatus.PROCESSING);
        } finally {
            cleanup(jobId, assetId, trigger);
        }
    }

    @Test
    void hookDatabaseFailureRollsBackLeaseExhaustionDeadTransitionAndAssetCas() {
        UUID assetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String trigger = null;
        try {
            assets.insertProcessing(asset(assetId));
            insertRunningJob(jobId, assetId, "crashed-owner", OLD);
            trigger = installFailureTrigger(assetId);

            assertThatThrownBy(() -> jobs.leaseNext(
                            "recovery-owner", Duration.ofMinutes(5)))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("job dead-letter hook failed")
                    .hasNoCause();

            assertThat(jobStatus(jobId)).isEqualTo("RUNNING");
            assertThat(assets.findById(assetId).orElseThrow().status())
                    .isEqualTo(MediaStatus.PROCESSING);
        } finally {
            cleanup(jobId, assetId, trigger);
        }
    }

    @Test
    void malformedDeadPayloadDoesNotBlockTheJobDeadTransition() {
        UUID jobId = UUID.randomUUID();
        try {
            insertRunningJob(jobId, null, "malformed-owner", OLD.plusYears(100));

            assertThat(jobs.fail(
                            jobId,
                            "malformed-owner",
                            10,
                            "JOB_HANDLER_FAILED"))
                    .isTrue();
            assertThat(jobStatus(jobId)).isEqualTo("DEAD");
        } finally {
            cleanup(jobId, null, null);
        }
    }

    private void insertRunningJob(
            UUID jobId, UUID assetId, String owner, OffsetDateTime leaseUntil) {
        String payload = assetId == null
                ? "{}"
                : "{\"assetId\":\"" + assetId + "\"}";
        migratorJdbc().sql("""
                        insert into portfolio.background_job(
                            id, job_type, idempotency_key, payload, status, attempts,
                            next_run_at, lease_owner, lease_until, created_at, updated_at
                        ) values (
                            :id, 'FINALIZE_MEDIA_UPLOAD', :key, cast(:payload as jsonb),
                            'RUNNING', 10, :old, :owner, :leaseUntil, :old, :old
                        )
                        """)
                .param("id", jobId)
                .param("key", "dead-finalizer-test:" + jobId)
                .param("payload", payload)
                .param("old", OLD)
                .param("owner", owner)
                .param("leaseUntil", leaseUntil)
                .update();
    }

    private String installFailureTrigger(UUID assetId) {
        String suffix = assetId.toString().replace("-", "");
        String function = "fail_media_" + suffix;
        String trigger = "fail_media_" + suffix;
        JdbcClient owner = migratorJdbc();
        owner.sql("create function portfolio." + function + "() returns trigger "
                        + "language plpgsql as $$ begin raise exception 'private db failure'; end $$")
                .update();
        owner.sql("create trigger " + trigger
                        + " before update on portfolio.media_asset for each row "
                        + "when (old.id = '" + assetId + "'::uuid) "
                        + "execute function portfolio." + function + "()")
                .update();
        return trigger;
    }

    private String jobStatus(UUID jobId) {
        return jdbc.sql("select status from portfolio.background_job where id=:id")
                .param("id", jobId)
                .query(String.class)
                .single();
    }

    private static MediaAssetRecord.Insert asset(UUID assetId) {
        String sha = sha256(PDF);
        return new MediaAssetRecord.Insert(
                assetId,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(assetId, sha, "application/pdf"),
                "document.pdf",
                "application/pdf",
                PDF.length,
                null,
                null,
                sha);
    }

    private void cleanup(UUID jobId, UUID assetId, String trigger) {
        JdbcClient owner = migratorJdbc();
        if (trigger != null) {
            String function = trigger;
            owner.sql("drop trigger if exists " + trigger
                            + " on portfolio.media_asset")
                    .update();
            owner.sql("drop function if exists portfolio." + function + "()")
                    .update();
        }
        owner.sql("delete from portfolio.background_job where id=:id")
                .param("id", jobId)
                .update();
        if (assetId != null) {
            owner.sql("delete from portfolio.media_variant where asset_id=:id")
                    .param("id", assetId)
                    .update();
            owner.sql("delete from portfolio.media_asset where id=:id")
                    .param("id", assetId)
                    .update();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
