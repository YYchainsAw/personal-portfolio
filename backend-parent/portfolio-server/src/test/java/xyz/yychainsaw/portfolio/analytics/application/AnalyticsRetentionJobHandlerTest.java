package xyz.yychainsaw.portfolio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.yychainsaw.portfolio.analytics.domain.AnalyticsEventType;
import xyz.yychainsaw.portfolio.analytics.domain.DeviceClass;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsDailyMapper;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventMapper;
import xyz.yychainsaw.portfolio.analytics.persistence.AnalyticsEventRecord;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;
import xyz.yychainsaw.portfolio.system.job.JobExecutionContext;

@SpringBootTest
@Isolated
@Import(AnalyticsRetentionJobHandlerTest.FixedClockConfiguration.class)
class AnalyticsRetentionJobHandlerTest extends PostgresIntegrationTestBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final Instant NOW = Instant.parse("2026-07-17T08:30:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-06-17T08:30:00Z");
    private static final LocalDate CURRENT_SITE_DATE = LocalDate.parse("2026-07-17");
    private static final UUID RUN_ID =
            UUID.fromString("95000000-0000-4000-8000-000000000006");
    private static final long TEST_BLOCKING_LOCK_KEY = 7_777_777_777L;

    @Autowired AnalyticsDailyMapper dailyMapper;
    @Autowired AnalyticsAggregationService aggregationService;
    @Autowired AnalyticsRetentionRepository retentionRepository;
    @Autowired AnalyticsMaintenanceRunRepository maintenanceRuns;
    @Autowired AnalyticsEventMapper eventMapper;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearBefore() {
        clearAnalytics();
    }

    @AfterEach
    void clearAfter() {
        clearAnalytics();
    }

    @Test
    void checksCoverageBeforeDeletingInStableFiveThousandRowBatches() {
        AnalyticsRetentionRepository repository = mock(AnalyticsRetentionRepository.class);
        AnalyticsMaintenanceRunRepository maintenance =
                mock(AnalyticsMaintenanceRunRepository.class);
        when(repository.hasCompleteAggregateCoverage(CUTOFF))
                .thenReturn(true);
        when(repository.deleteExpiredBatch(
                        CUTOFF, AnalyticsRetentionJobHandler.BATCH_SIZE))
                .thenReturn(5_000, 2, 0);
        AnalyticsRetentionJobHandler handler = handler(repository, maintenance, RUN_ID);

        handler.handle(context(RUN_ID), payload(CURRENT_SITE_DATE));

        assertThat(handler.jobType()).isEqualTo("ANALYTICS_RETENTION");
        InOrder ordered = inOrder(repository, maintenance);
        ordered.verify(maintenance).startRetention(RUN_ID, NOW, CUTOFF);
        ordered.verify(repository).hasCompleteAggregateCoverage(CUTOFF);
        ordered.verify(repository, times(3)).deleteExpiredBatch(
                CUTOFF, 5_000);
        ordered.verify(maintenance).succeedRetention(RUN_ID, 5_002, CUTOFF, NOW);
    }

    @Test
    void missingAggregateCoverageFailsBeforeAnyRawEventIsDeleted() {
        AnalyticsRetentionRepository repository = mock(AnalyticsRetentionRepository.class);
        AnalyticsMaintenanceRunRepository maintenance =
                mock(AnalyticsMaintenanceRunRepository.class);
        when(repository.hasCompleteAggregateCoverage(CUTOFF))
                .thenReturn(false);
        AnalyticsRetentionJobHandler handler = handler(repository, maintenance, RUN_ID);

        assertThatThrownBy(() -> handler.handle(context(RUN_ID), payload(CURRENT_SITE_DATE)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("ANALYTICS_RETENTION_FAILED")
                .hasNoCause();

        verify(repository, never()).deleteExpiredBatch(CUTOFF, 5_000);
        verify(maintenance).failRetention(
                RUN_ID, 0, CUTOFF, NOW, "ANALYTICS_RETENTION_FAILED");
    }

    @Test
    void committedBatchCountIsPreservedWhenTheNextBatchFails() {
        AnalyticsRetentionRepository repository = mock(AnalyticsRetentionRepository.class);
        AnalyticsMaintenanceRunRepository maintenance =
                mock(AnalyticsMaintenanceRunRepository.class);
        when(repository.hasCompleteAggregateCoverage(CUTOFF))
                .thenReturn(true);
        when(repository.deleteExpiredBatch(CUTOFF, 5_000))
                .thenReturn(5_000)
                .thenThrow(new IllegalStateException("private SQL detail"));
        AnalyticsRetentionJobHandler handler = handler(repository, maintenance, RUN_ID);

        assertThatThrownBy(() -> handler.handle(context(RUN_ID), payload(CURRENT_SITE_DATE)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("ANALYTICS_RETENTION_FAILED")
                .hasNoCause();

        verify(maintenance).failRetention(
                RUN_ID, 5_000, CUTOFF, NOW, "ANALYTICS_RETENTION_FAILED");
    }

    @Test
    void tenthBatchSchedulesAnAtomicSuccessorAndEndsTheCurrentSlice() {
        AnalyticsRetentionRepository repository = mock(AnalyticsRetentionRepository.class);
        AnalyticsMaintenanceRunRepository maintenance =
                mock(AnalyticsMaintenanceRunRepository.class);
        when(repository.hasCompleteAggregateCoverage(CUTOFF)).thenReturn(true);
        when(repository.deleteExpiredBatch(CUTOFF, 5_000)).thenReturn(5_000);
        when(repository.deleteFinalBatchAndScheduleSuccessor(
                        CUTOFF, 5_000, RUN_ID, 1, CURRENT_SITE_DATE))
                .thenReturn(new AnalyticsRetentionRepository.RetentionSliceTail(
                        5_000, true));
        AnalyticsRetentionJobHandler handler = handler(repository, maintenance, RUN_ID);

        handler.handle(context(RUN_ID), payload(CURRENT_SITE_DATE));

        verify(repository, times(9)).deleteExpiredBatch(CUTOFF, 5_000);
        verify(repository).deleteFinalBatchAndScheduleSuccessor(
                CUTOFF, 5_000, RUN_ID, 1, CURRENT_SITE_DATE);
        verify(maintenance).succeedRetention(RUN_ID, 50_000, CUTOFF, NOW);
    }

    @Test
    void payloadCannotChooseTheCutoffOrAnUnboundedDate() {
        AnalyticsRetentionJobHandler handler = handler(
                mock(AnalyticsRetentionRepository.class),
                mock(AnalyticsMaintenanceRunRepository.class),
                RUN_ID);
        JsonNode extra = JSON.valueToTree(Map.of(
                "siteDate", CURRENT_SITE_DATE.toString(),
                "cutoffEpochSecond", 0));

        for (JsonNode invalid : java.util.List.of(
                JSON.nullNode(),
                JSON.createArrayNode(),
                JSON.createObjectNode(),
                JSON.valueToTree(Map.of("siteDate", "2026-07-18")),
                JSON.valueToTree(Map.of("siteDate", "2026-06-15")),
                JSON.valueToTree(Map.of("siteDate", 123)),
                extra)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> handler.handle(context(RUN_ID), invalid))
                    .withMessage("ANALYTICS_RETENTION_PAYLOAD_INVALID")
                    .withNoCause();
        }
    }

    @Test
    void integrationDeletesStrictlyBeforeCutoffAndKeepsAggregates() {
        Instant expired = CUTOFF.minusNanos(1_000);
        insertEvent(1, expired, "a".repeat(64));
        insertEvent(2, CUTOFF, "b".repeat(64));
        LocalDate aggregateDate = expired.atZone(SITE_ZONE).toLocalDate();
        insertCompleteCoverage(aggregateDate, 2, 2);
        int aggregateRows = dailyMapper.findByDate(aggregateDate).size();
        AnalyticsRetentionJobHandler handler = handler(
                retentionRepository, maintenanceRuns, RUN_ID);

        handler.handle(context(RUN_ID), payload(CURRENT_SITE_DATE));

        assertThat(eventMapper.findAll())
                .singleElement()
                .satisfies(event -> assertThat(event.receivedAt()).isEqualTo(CUTOFF));
        assertThat(dailyMapper.findByDate(aggregateDate)).hasSize(aggregateRows);
        MaintenanceRun run = retentionRun(RUN_ID);
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.deletedCount()).isOne();
        assertThat(run.cutoffEpochSecond()).isEqualTo(CUTOFF.getEpochSecond());
        assertThat(run.errorSummary()).isNull();
        assertThat(run.details()).doesNotContain("a".repeat(64), "b".repeat(64));
    }

    @Test
    void integrationRepairsAnUnaggregatedExpiredDateBeforeAnyPurge() {
        Instant covered = CUTOFF.minusNanos(1_000);
        Instant uncovered = CUTOFF.minusSeconds(172_800);
        insertEvent(3, covered, "c".repeat(64));
        insertCompleteCoverage(covered.atZone(SITE_ZONE).toLocalDate(), 1, 1);
        insertEvent(4, uncovered, "d".repeat(64));
        UUID runId = UUID.fromString("95000000-0000-4000-8000-000000000007");
        AnalyticsRetentionJobHandler handler = handler(
                retentionRepository, maintenanceRuns, runId);

        handler.handle(context(runId), payload(CURRENT_SITE_DATE));

        assertThat(eventMapper.count()).isZero();
        LocalDate repairedDate = uncovered.atZone(SITE_ZONE).toLocalDate();
        assertThat(dailyMapper.findByDate(repairedDate))
                .extracting(row -> row.aggregationVersion())
                .containsOnly("analytics-rules-v1");
        assertThat(jdbc.sql("""
                        select count(*)
                        from portfolio.analytics_retention_checkpoint
                        where site_date in (:coveredDate, :repairedDate)
                        """)
                .param("coveredDate", covered.atZone(SITE_ZONE).toLocalDate())
                .param("repairedDate", repairedDate)
                .query(Integer.class)
                .single()).isEqualTo(2);
        MaintenanceRun run = retentionRun(runId);
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.deletedCount()).isEqualTo(2);
        assertThat(run.cutoffEpochSecond()).isEqualTo(CUTOFF.getEpochSecond());
        assertThat(run.errorSummary()).isNull();
    }

    @Test
    void staleAggregationCannotReplacePermanentMetricsAfterPurgeWindowStarts() {
        LocalDate expiredDate = LocalDate.parse("2026-06-17");
        insertAllOnlyCoverage(expiredDate, 1, 1);
        List<String> before = dailyMapper.findByDate(expiredDate).stream()
                .map(row -> row.metric() + "|" + row.eventType() + "|" + row.metricCount())
                .toList();

        assertThatThrownBy(() -> aggregationService.rebuild(expiredDate))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("ANALYTICS_AGGREGATION_DATE_EXPIRED")
                .hasNoCause();

        assertThat(dailyMapper.findByDate(expiredDate).stream()
                        .map(row -> row.metric() + "|" + row.eventType() + "|"
                                + row.metricCount())
                        .toList())
                .containsExactlyElementsOf(before);
    }

    @Test
    void eachBatchRechecksCompleteCoverageInsideItsDateLock() {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        insertEvent(5, expired, "e".repeat(64));
        insertCompleteCoverage(siteDate, 1, 1);
        assertThat(retentionRepository.hasCompleteAggregateCoverage(CUTOFF)).isTrue();
        assertThat(jdbc.sql("""
                        delete from portfolio.analytics_daily
                        where site_date=:siteDate
                          and metric='DAILY_UV'
                          and event_type='PAGE_VIEW'
                          and dimension='ALL'
                          and dimension_value='(all)'
                        """)
                .param("siteDate", siteDate)
                .update()).isOne();

        assertThatThrownBy(() -> retentionRepository.deleteExpiredBatch(CUTOFF, 5_000))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("ANALYTICS_RETENTION_COVERAGE_MISSING")
                .hasNoCause();
        assertThat(eventMapper.count()).isOne();
    }

    @Test
    void missingDimensionRowPreventsCheckpointAndEveryRawDeletion() {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        insertEvent(18, expired, "8".repeat(64));
        aggregationService.rebuildForRetention(siteDate, CUTOFF);
        assertThat(jdbc.sql("""
                        delete from portfolio.analytics_daily
                        where site_date=:siteDate
                          and metric='PV'
                          and event_type='PAGE_VIEW'
                          and dimension='PAGE'
                          and dimension_value='HOME'
                        """)
                .param("siteDate", siteDate)
                .update()).isOne();
        assertThat(retentionRepository.hasCompleteAggregateCoverage(CUTOFF)).isFalse();

        assertThatThrownBy(() -> retentionRepository.deleteExpiredBatch(CUTOFF, 5_000))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("ANALYTICS_RETENTION_COVERAGE_MISSING")
                .hasNoCause();

        assertThat(eventMapper.count()).isOne();
        assertThat(checkpointCount(siteDate)).isZero();
    }

    @Test
    void tamperedDimensionCountPreventsCheckpointAndEveryRawDeletion() {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        insertEvent(19, expired, "9".repeat(64));
        aggregationService.rebuildForRetention(siteDate, CUTOFF);
        assertThat(jdbc.sql("""
                        update portfolio.analytics_daily
                        set metric_count=metric_count + 1
                        where site_date=:siteDate
                          and metric='EVENT_COUNT'
                          and event_type='PAGE_VIEW'
                          and dimension='DEVICE'
                          and dimension_value='DESKTOP'
                        """)
                .param("siteDate", siteDate)
                .update()).isOne();
        assertThat(retentionRepository.hasCompleteAggregateCoverage(CUTOFF)).isFalse();

        assertThatThrownBy(() -> retentionRepository.deleteExpiredBatch(CUTOFF, 5_000))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("ANALYTICS_RETENTION_COVERAGE_MISSING")
                .hasNoCause();

        assertThat(eventMapper.count()).isOne();
        assertThat(checkpointCount(siteDate)).isZero();
    }

    @Test
    void handlerRepairsAMissingDimensionBeforeCheckpointAndPurge() {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        insertEvent(23, expired, "e".repeat(64));
        aggregationService.rebuildForRetention(siteDate, CUTOFF);
        assertThat(jdbc.sql("""
                        delete from portfolio.analytics_daily
                        where site_date=:siteDate
                          and metric='PV'
                          and event_type='PAGE_VIEW'
                          and dimension='PAGE'
                          and dimension_value='HOME'
                        """)
                .param("siteDate", siteDate)
                .update()).isOne();
        assertThat(retentionRepository.hasCompleteAggregateCoverage(CUTOFF)).isFalse();
        UUID runId = UUID.fromString("95000000-0000-4000-8000-000000000015");

        handler(retentionRepository, maintenanceRuns, runId)
                .handle(context(runId), payload(CURRENT_SITE_DATE));

        assertThat(eventMapper.count()).isZero();
        assertThat(checkpointCount(siteDate)).isOne();
        assertThat(jdbc.sql("""
                        select metric_count
                        from portfolio.analytics_daily
                        where site_date=:siteDate
                          and metric='PV'
                          and event_type='PAGE_VIEW'
                          and dimension='PAGE'
                          and dimension_value='HOME'
                        """)
                .param("siteDate", siteDate)
                .query(Long.class)
                .single()).isOne();
    }

    @Test
    void directCheckpointRejectsADailySnapshotThatOnlyCoversTheCutoffPrefix() {
        Instant expired = CUTOFF.minusSeconds(1);
        Instant retained = CUTOFF.plusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        assertThat(retained.atZone(SITE_ZONE).toLocalDate()).isEqualTo(siteDate);
        insertEvent(20, expired, "a".repeat(64));
        aggregationService.rebuildForRetention(siteDate, CUTOFF);
        insertEvent(21, retained, "b".repeat(64));
        assertThat(allMetricCount(siteDate, "PV", AnalyticsEventType.PAGE_VIEW))
                .isOne();

        assertThatThrownBy(() -> jdbc.sql("""
                        select portfolio.prepare_analytics_retention_checkpoint(
                            :siteDate, :cutoff
                        )
                        """)
                .param("siteDate", siteDate)
                .param("cutoff", CUTOFF.atOffset(ZoneOffset.UTC))
                .query(String.class)
                .single()).rootCause().hasMessageContaining(
                        "analytics retention aggregate coverage is incomplete");

        assertThat(eventMapper.count()).isEqualTo(2);
        assertThat(checkpointCount(siteDate)).isZero();
    }

    @Test
    void rawAndDailyWritesWaitForCheckpointThenFailOnIndependentConnections()
            throws Exception {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        insertEvent(22, expired, "c".repeat(64));
        aggregationService.rebuildForRetention(siteDate, CUTOFF);
        List<String> dailySnapshot = aggregateSnapshot(siteDate);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection checkpoint = dataSource.getConnection()) {
            checkpoint.setAutoCommit(false);
            prepareCheckpoint(checkpoint, siteDate, CUTOFF);

            Future<String> rawFailure = executor.submit(
                    () -> insertRawAndCaptureFailure(23, expired, "d".repeat(64)));
            Future<String> dailyFailure = executor.submit(
                    () -> updateDailyAndCaptureFailure(siteDate));
            awaitDateLockWaiters(checkpoint, siteDate, 2);
            assertThat(rawFailure.isDone()).isFalse();
            assertThat(dailyFailure.isDone()).isFalse();

            checkpoint.commit();

            assertThat(rawFailure.get(10, TimeUnit.SECONDS))
                    .contains("analytics event date has entered retention");
            assertThat(dailyFailure.get(10, TimeUnit.SECONDS))
                    .contains("retained analytics aggregates are immutable");
            assertThat(eventMapper.count()).isOne();
            assertThat(aggregateSnapshot(siteDate))
                    .containsExactlyElementsOf(dailySnapshot);
            assertThat(checkpointCount(siteDate)).isOne();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void oneConsistentHistoricalAggregationVersionCanStillBePurged() {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        insertEvent(6, expired, "6".repeat(64));
        insertCompleteCoverage(siteDate, 1, 1);
        int aggregateRows = dailyMapper.findByDate(siteDate).size();
        assertThat(jdbc.sql("""
                        update portfolio.analytics_daily
                        set aggregation_version='analytics-rules-v0'
                        where site_date=:siteDate
                        """)
                .param("siteDate", siteDate)
                .update()).isEqualTo(aggregateRows);

        assertThat(retentionRepository.hasCompleteAggregateCoverage(CUTOFF)).isTrue();
        assertThat(retentionRepository.deleteExpiredBatch(CUTOFF, 5_000)).isOne();
        assertThat(eventMapper.count()).isZero();
    }

    @Test
    void mixedAggregationVersionsPreventEveryDeletion() {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        insertEvent(7, expired, "7".repeat(64));
        insertCompleteCoverage(siteDate, 1, 1);
        assertThat(jdbc.sql("""
                        update portfolio.analytics_daily
                        set aggregation_version='analytics-rules-v0'
                        where site_date=:siteDate
                          and metric='PV'
                          and event_type='PAGE_VIEW'
                          and dimension='ALL'
                        """)
                .param("siteDate", siteDate)
                .update()).isOne();

        assertThat(retentionRepository.hasCompleteAggregateCoverage(CUTOFF)).isFalse();
        assertThat(eventMapper.count()).isOne();
    }

    @Test
    void realRepositoryDeletesFiveThousandThenOneWithoutTouchingDailyRows() {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        bulkInsertPageViews(5_001, expired, siteDate);
        insertCompleteCoverage(siteDate, 5_001, 1);
        List<String> aggregateSnapshot = dailyMapper.findByDate(siteDate).stream()
                .map(row -> row.metric() + "|" + row.eventType() + "|"
                        + row.metricCount())
                .toList();

        assertThat(retentionRepository.hasCompleteAggregateCoverage(CUTOFF)).isTrue();
        assertThat(retentionRepository.deleteExpiredBatch(CUTOFF, 5_000))
                .isEqualTo(5_000);
        assertThat(eventMapper.count()).isOne();
        assertThat(retentionRepository.hasCompleteAggregateCoverage(CUTOFF)).isTrue();
        assertThat(retentionRepository.deleteExpiredBatch(CUTOFF, 5_000)).isOne();
        assertThat(retentionRepository.deleteExpiredBatch(CUTOFF, 5_000)).isZero();
        assertThat(eventMapper.count()).isZero();
        assertThat(dailyMapper.findByDate(siteDate).stream()
                        .map(row -> row.metric() + "|" + row.eventType() + "|"
                                + row.metricCount())
                        .toList())
                .containsExactlyElementsOf(aggregateSnapshot);
    }

    @Test
    void checkpointFreezesHistoryAndCannotBeChangedByTheRuntimeRole() {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        insertEvent(14, expired, "a".repeat(64));
        insertEvent(15, expired, "b".repeat(64));
        insertCompleteCoverage(siteDate, 2, 2);

        assertThat(retentionRepository.deleteExpiredBatch(CUTOFF, 1)).isOne();

        assertThatThrownBy(() -> aggregationService.rebuildForRetention(siteDate, CUTOFF))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("ANALYTICS_RETENTION_REPAIR_UNSAFE")
                .hasNoCause();
        assertThatThrownBy(() -> jdbc.sql("""
                        update portfolio.analytics_daily
                        set metric_count=metric_count
                        where site_date=:siteDate
                        """)
                .param("siteDate", siteDate)
                .update()).rootCause().hasMessageContaining(
                        "retained analytics aggregates are immutable");
        assertThatThrownBy(() -> insertEvent(16, expired, "c".repeat(64)))
                .rootCause().hasMessageContaining(
                        "analytics event date has entered retention");
        assertThatThrownBy(() -> jdbc.sql("""
                        delete from portfolio.analytics_retention_checkpoint
                        where site_date=:siteDate
                        """)
                .param("siteDate", siteDate)
                .update()).rootCause().hasMessageContaining(
                        "permission denied for table analytics_retention_checkpoint");
        assertThat(eventMapper.count()).isOne();
    }

    @Test
    void runtimeCannotBypassScopedPurgeWithDirectRawDeletion() {
        Instant expired = CUTOFF.minusSeconds(1);
        insertEvent(17, expired, "d".repeat(64));

        assertThatThrownBy(() -> jdbc.sql("""
                        delete from portfolio.analytics_event
                        where received_at=:receivedAt
                        """)
                .param("receivedAt", expired.atOffset(ZoneOffset.UTC))
                .update()).rootCause().hasMessageContaining(
                        "permission denied for table analytics_event");
        assertThat(eventMapper.count()).isOne();
    }

    @Test
    void finalBatchAndSuccessorAreDurableAndIdempotentTogether() {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        bulkInsertPageViews(10_001, expired, siteDate);
        insertCompleteCoverage(siteDate, 10_001, 1);
        UUID parentJobId =
                UUID.fromString("95000000-0000-4000-8000-000000000011");

        AnalyticsRetentionRepository.RetentionSliceTail first =
                retentionRepository.deleteFinalBatchAndScheduleSuccessor(
                        CUTOFF, 5_000, parentJobId, 1, CURRENT_SITE_DATE);
        AnalyticsRetentionRepository.RetentionSliceTail retry =
                retentionRepository.deleteFinalBatchAndScheduleSuccessor(
                        CUTOFF, 5_000, parentJobId, 1, CURRENT_SITE_DATE);

        assertThat(first).isEqualTo(
                new AnalyticsRetentionRepository.RetentionSliceTail(5_000, true));
        assertThat(retry).isEqualTo(
                new AnalyticsRetentionRepository.RetentionSliceTail(5_000, true));
        assertThat(eventMapper.count()).isOne();
        assertThat(jdbc.sql("""
                        select count(*)
                        from portfolio.background_job job
                        where job.idempotency_key=:key
                          and job.job_type='ANALYTICS_RETENTION'
                          and job.payload=cast(:payload as jsonb)
                        """)
                .param("key", "analytics-retention-next:" + parentJobId + ":1")
                .param("payload", "{\"siteDate\":\"2026-07-17\"}")
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void laterParentAttemptCreatesLiveSuccessorAfterPriorSuccessorCompleted() {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        bulkInsertPageViews(10_001, expired, siteDate);
        insertCompleteCoverage(siteDate, 10_001, 1);
        UUID parentJobId =
                UUID.fromString("95000000-0000-4000-8000-000000000014");
        String firstKey = "analytics-retention-next:" + parentJobId + ":1";
        String secondKey = "analytics-retention-next:" + parentJobId + ":2";

        AnalyticsRetentionRepository.RetentionSliceTail first =
                retentionRepository.deleteFinalBatchAndScheduleSuccessor(
                        CUTOFF, 5_000, parentJobId, 1, CURRENT_SITE_DATE);
        assertThat(migratorJdbc().sql("""
                        update portfolio.background_job
                        set status='SUCCEEDED'
                        where idempotency_key=:key
                          and status='PENDING'
                        """)
                .param("key", firstKey)
                .update()).isOne();

        AnalyticsRetentionRepository.RetentionSliceTail second =
                retentionRepository.deleteFinalBatchAndScheduleSuccessor(
                        CUTOFF, 5_000, parentJobId, 2, CURRENT_SITE_DATE);

        assertThat(first).isEqualTo(
                new AnalyticsRetentionRepository.RetentionSliceTail(5_000, true));
        assertThat(second).isEqualTo(
                new AnalyticsRetentionRepository.RetentionSliceTail(5_000, true));
        assertThat(eventMapper.count()).isOne();
        assertThat(jdbc.sql("""
                        select count(*)
                        from portfolio.background_job
                        where idempotency_key=:key
                          and status='SUCCEEDED'
                        """)
                .param("key", firstKey)
                .query(Integer.class)
                .single()).isOne();
        assertThat(jdbc.sql("""
                        select count(*)
                        from portfolio.background_job
                        where idempotency_key=:key
                          and status='PENDING'
                        """)
                .param("key", secondKey)
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void successorConflictRollsBackItsFinalDeletionAndCheckpoint() {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        insertEvent(12, expired, "8".repeat(64));
        insertEvent(13, expired, "9".repeat(64));
        insertCompleteCoverage(siteDate, 2, 2);
        UUID parentJobId =
                UUID.fromString("95000000-0000-4000-8000-000000000012");
        migratorJdbc().sql("""
                        insert into portfolio.background_job(
                            id, job_type, idempotency_key, payload,
                            status, attempts, next_run_at
                        ) values (
                            '95000000-0000-4000-8000-000000000013',
                            'ANALYTICS_RETENTION', :key,
                            '{"siteDate":"2026-07-16"}'::jsonb,
                            'PENDING', 0, clock_timestamp()
                        )
                        """)
                .param("key", "analytics-retention-next:" + parentJobId + ":1")
                .update();

        assertThatThrownBy(() ->
                        retentionRepository.deleteFinalBatchAndScheduleSuccessor(
                                CUTOFF, 1, parentJobId, 1, CURRENT_SITE_DATE))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("ANALYTICS_RETENTION_REPOSITORY_FAILED")
                .hasNoCause();

        assertThat(eventMapper.count()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        select count(*)
                        from portfolio.analytics_retention_checkpoint
                        where site_date=:siteDate
                        """)
                .param("siteDate", siteDate)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void concurrentRetentionHandlersDeleteEachRawEventExactlyOnce() throws Exception {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        bulkInsertPageViews(5_001, expired, siteDate);
        insertCompleteCoverage(siteDate, 5_001, 1);
        List<String> aggregateSnapshot = aggregateSnapshot(siteDate);
        UUID leftRunId = UUID.fromString("95000000-0000-4000-8000-000000000009");
        UUID rightRunId = UUID.fromString("95000000-0000-4000-8000-000000000010");
        AnalyticsRetentionJobHandler left = handler(
                retentionRepository, maintenanceRuns, leftRunId);
        AnalyticsRetentionJobHandler right = handler(
                retentionRepository, maintenanceRuns, rightRunId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> leftFuture = executor.submit(
                    () -> runRetentionTogether(left, leftRunId, ready, start));
            Future<?> rightFuture = executor.submit(
                    () -> runRetentionTogether(right, rightRunId, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            leftFuture.get(30, TimeUnit.SECONDS);
            rightFuture.get(30, TimeUnit.SECONDS);

            MaintenanceRun leftRun = retentionRun(leftRunId);
            MaintenanceRun rightRun = retentionRun(rightRunId);
            assertThat(leftRun.status()).isEqualTo("SUCCEEDED");
            assertThat(rightRun.status()).isEqualTo("SUCCEEDED");
            assertThat(Math.addExact(
                            leftRun.deletedCount(), rightRun.deletedCount()))
                    .isEqualTo(5_001);
            assertThat(leftRun.deletedCount()).isBetween(0L, 5_001L);
            assertThat(rightRun.deletedCount()).isBetween(0L, 5_001L);
            assertThat(eventMapper.count()).isZero();
            assertThat(aggregateSnapshot(siteDate))
                    .containsExactlyElementsOf(aggregateSnapshot);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void retentionWaitsForAnAggregationHoldingTheSharedDateLock() throws Exception {
        Instant expired = CUTOFF.minusSeconds(1);
        LocalDate siteDate = expired.atZone(SITE_ZONE).toLocalDate();
        insertEvent(7, expired, "7".repeat(64));
        insertCompleteCoverage(siteDate, 1, 1);
        UUID retentionRunId =
                UUID.fromString("95000000-0000-4000-8000-000000000011");
        AnalyticsAggregationService earlierAggregation = new AnalyticsAggregationService(
                dailyMapper,
                new AnalyticsRules(),
                Clock.fixed(Instant.parse("2026-06-30T08:30:00Z"), ZoneOffset.UTC),
                transactionManager);
        AnalyticsRetentionJobHandler retention = handler(
                retentionRepository, maintenanceRuns, retentionRunId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch aggregationReady = new CountDownLatch(1);
        CountDownLatch retentionReady = new CountDownLatch(1);
        createBlockingAggregationTrigger();
        try (Connection blocker = migratorDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            acquireTransactionLock(blocker, TEST_BLOCKING_LOCK_KEY);
            Future<AnalyticsAggregationService.AggregationResult> aggregationFuture =
                    executor.submit(() -> {
                        aggregationReady.countDown();
                        return earlierAggregation.rebuild(siteDate);
                    });
            assertThat(aggregationReady.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDateLock(blocker, siteDate);

            Future<?> retentionFuture = executor.submit(() -> {
                retentionReady.countDown();
                retention.handle(context(retentionRunId), payload(CURRENT_SITE_DATE));
            });
            assertThat(retentionReady.await(10, TimeUnit.SECONDS)).isTrue();
            awaitMaintenanceStatus(retentionRunId, "RUNNING");
            awaitDateLockWaiter(blocker, siteDate);
            assertThat(retentionFuture.isDone()).isFalse();
            assertThat(eventMapper.count()).isOne();

            blocker.commit();
            AnalyticsAggregationService.AggregationResult aggregationResult =
                    aggregationFuture.get(20, TimeUnit.SECONDS);
            retentionFuture.get(20, TimeUnit.SECONDS);

            assertThat(aggregationResult.inputCount()).isOne();
            assertThat(aggregationResult.outputCount()).isPositive();
            assertThat(eventMapper.count()).isZero();
            assertThat(dailyMapper.findByDate(siteDate)).isNotEmpty();
            MaintenanceRun run = retentionRun(retentionRunId);
            assertThat(run.status()).isEqualTo("SUCCEEDED");
            assertThat(run.deletedCount()).isOne();
        } finally {
            executor.shutdownNow();
            boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            dropBlockingAggregationTrigger();
            assertThat(terminated).isTrue();
        }
    }

    private AnalyticsRetentionJobHandler handler(
            AnalyticsRetentionRepository repository,
            AnalyticsMaintenanceRunRepository maintenance,
            UUID runId) {
        return new AnalyticsRetentionJobHandler(
                repository,
                maintenance,
                aggregationService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> runId);
    }

    private static Void runRetentionTogether(
            AnalyticsRetentionJobHandler handler,
            UUID jobId,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        handler.handle(context(jobId), payload(CURRENT_SITE_DATE));
        return null;
    }

    private static JobExecutionContext context(UUID jobId) {
        return new JobExecutionContext(jobId, "analytics-retention-test-worker", 1);
    }

    private List<String> aggregateSnapshot(LocalDate siteDate) {
        return dailyMapper.findByDate(siteDate).stream()
                .map(row -> row.metric() + "|" + row.eventType() + "|"
                        + row.dimension() + "|" + row.dimensionValue() + "|"
                        + row.metricCount() + "|" + row.aggregationVersion())
                .toList();
    }

    private int checkpointCount(LocalDate siteDate) {
        return jdbc.sql("""
                        select count(*)
                        from portfolio.analytics_retention_checkpoint
                        where site_date=:siteDate
                        """)
                .param("siteDate", siteDate)
                .query(Integer.class)
                .single();
    }

    private long allMetricCount(
            LocalDate siteDate, String metric, AnalyticsEventType eventType) {
        return jdbc.sql("""
                        select metric_count
                        from portfolio.analytics_daily
                        where site_date=:siteDate
                          and metric=:metric
                          and event_type=:eventType
                          and dimension='ALL'
                          and dimension_value='(all)'
                        """)
                .param("siteDate", siteDate)
                .param("metric", metric)
                .param("eventType", eventType.name())
                .query(Long.class)
                .single();
    }

    private static void prepareCheckpoint(
            Connection connection, LocalDate siteDate, Instant cutoff)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select portfolio.prepare_analytics_retention_checkpoint(?, ?)
                """)) {
            statement.setObject(1, siteDate);
            statement.setObject(2, cutoff.atOffset(ZoneOffset.UTC));
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("analytics-rules-v1");
                assertThat(result.next()).isFalse();
            }
        }
    }

    private String insertRawAndCaptureFailure(
            int sequence, Instant receivedAt, String visitorKey) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                    insert into portfolio.analytics_event(
                        id, client_event_id, site_date, received_at,
                        visitor_day_key, session_day_key, event_type, page_key,
                        project_id, referrer_domain, device_class, locale,
                        rules_version, created_at
                    ) values (?, ?, ?, ?, ?, ?, 'PAGE_VIEW', 'HOME', null,
                              '(direct)', 'DESKTOP', 'en',
                              'analytics-rules-v1', ?)
                    """)) {
            statement.setObject(1, UUID.fromString(
                    "50000000-0000-4000-8000-%012d".formatted(sequence * 10 + 1)));
            statement.setObject(2, UUID.fromString(
                    "50000000-0000-4000-8000-%012d".formatted(sequence * 10 + 2)));
            statement.setObject(3, receivedAt.atZone(SITE_ZONE).toLocalDate());
            statement.setObject(4, receivedAt.atOffset(ZoneOffset.UTC));
            statement.setString(5, visitorKey);
            statement.setString(6, "f".repeat(64));
            statement.setObject(7, receivedAt.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
            return "analytics raw write unexpectedly succeeded";
        } catch (SQLException exception) {
            return exception.getMessage();
        }
    }

    private String updateDailyAndCaptureFailure(LocalDate siteDate) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                    update portfolio.analytics_daily
                    set metric_count=metric_count
                    where site_date=?
                      and metric='PV'
                      and event_type='PAGE_VIEW'
                      and dimension='PAGE'
                      and dimension_value='HOME'
                    """)) {
            statement.setObject(1, siteDate);
            statement.executeUpdate();
            return "analytics daily write unexpectedly succeeded";
        } catch (SQLException exception) {
            return exception.getMessage();
        }
    }

    private void createBlockingAggregationTrigger() {
        dropBlockingAggregationTrigger();
        JdbcClient owner = migratorJdbc();
        owner.sql("""
                        create or replace function
                            portfolio.analytics_test_wait_for_lock_release()
                        returns trigger
                        language plpgsql
                        as $$
                        begin
                            perform pg_catalog.set_config(
                                'lock_timeout', '10s', true);
                            perform pg_catalog.pg_advisory_xact_lock(%d);
                            return null;
                        end;
                        $$
                        """.formatted(TEST_BLOCKING_LOCK_KEY))
                .update();
        owner.sql("""
                        create trigger analytics_test_wait_for_lock_release
                        before insert on portfolio.analytics_daily
                        for each statement
                        execute function
                            portfolio.analytics_test_wait_for_lock_release()
                        """)
                .update();
    }

    private void dropBlockingAggregationTrigger() {
        JdbcClient owner = migratorJdbc();
        owner.sql("""
                        drop trigger if exists analytics_test_wait_for_lock_release
                        on portfolio.analytics_daily
                        """)
                .update();
        owner.sql("""
                        drop function if exists
                            portfolio.analytics_test_wait_for_lock_release()
                        """)
                .update();
    }

    private static void acquireTransactionLock(Connection connection, long lockKey)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select pg_catalog.pg_advisory_xact_lock(?)")) {
            statement.setLong(1, lockKey);
            statement.execute();
        }
    }

    private static void awaitDateLock(Connection connection, LocalDate siteDate)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        long lockKey = analyticsDateLockKey(siteDate);
        while (System.nanoTime() < deadline) {
            if (dateLockIsHeldByAnotherSession(connection, lockKey)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("aggregation did not acquire the analytics date lock");
    }

    private static boolean dateLockIsHeldByAnotherSession(
            Connection connection, long lockKey) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select pg_catalog.pg_try_advisory_lock(?)")) {
            statement.setLong(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                if (!result.getBoolean(1)) {
                    return true;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "select pg_catalog.pg_advisory_unlock(?)")) {
            statement.setLong(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isTrue();
            }
        }
        return false;
    }

    private static long analyticsDateLockKey(LocalDate siteDate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                ("portfolio:analytics:date:" + siteDate)
                        .getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(digest).getLong();
    }

    private static void awaitDateLockWaiter(Connection connection, LocalDate siteDate)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        long lockKey = analyticsDateLockKey(siteDate);
        long classId = lockKey >>> Integer.SIZE;
        long objectId = lockKey & 0xffff_ffffL;
        while (System.nanoTime() < deadline) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select exists (
                        select 1
                        from pg_catalog.pg_locks advisory_lock
                        where advisory_lock.locktype='advisory'
                          and advisory_lock.database=(
                              select db.oid
                              from pg_catalog.pg_database db
                              where db.datname=pg_catalog.current_database()
                          )
                          and not advisory_lock.granted
                          and advisory_lock.classid::bigint=?
                          and advisory_lock.objid::bigint=?
                          and advisory_lock.objsubid=1
                          and advisory_lock.mode='ExclusiveLock'
                    )
                    """)) {
                statement.setLong(1, classId);
                statement.setLong(2, objectId);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    if (result.getBoolean(1)) {
                        return;
                    }
                }
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("retention did not wait for the analytics date lock");
    }

    private static void awaitDateLockWaiters(
            Connection connection, LocalDate siteDate, int expectedCount)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long lockKey = analyticsDateLockKey(siteDate);
        long classId = lockKey >>> Integer.SIZE;
        long objectId = lockKey & 0xffff_ffffL;
        while (System.nanoTime() < deadline) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select count(*)
                    from pg_catalog.pg_locks advisory_lock
                    where advisory_lock.locktype='advisory'
                      and advisory_lock.database=(
                          select db.oid
                          from pg_catalog.pg_database db
                          where db.datname=pg_catalog.current_database()
                      )
                      and not advisory_lock.granted
                      and advisory_lock.classid::bigint=?
                      and advisory_lock.objid::bigint=?
                      and advisory_lock.objsubid=1
                    """)) {
                statement.setLong(1, classId);
                statement.setLong(2, objectId);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    if (result.getInt(1) >= expectedCount) {
                        return;
                    }
                }
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError(
                "analytics writes did not wait for the retention checkpoint lock");
    }

    private void awaitMaintenanceStatus(UUID runId, String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            boolean matched = jdbc.sql("""
                            select exists (
                                select 1
                                from portfolio.maintenance_run
                                where id=:id and status=:status
                            )
                            """)
                    .param("id", runId)
                    .param("status", status)
                    .query(Boolean.class)
                    .single();
            if (matched) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("analytics maintenance run did not reach " + status);
    }

    private void insertCompleteCoverage(
            LocalDate siteDate, long pageViews, long dailyUv) {
        AnalyticsAggregationService.AggregationResult result =
                aggregationService.rebuildForRetention(siteDate, CUTOFF);
        assertThat(result.inputCount()).isEqualTo(pageViews);
        assertThat(allMetricCount(siteDate, "PV", AnalyticsEventType.PAGE_VIEW))
                .isEqualTo(pageViews);
        assertThat(allMetricCount(
                        siteDate, "DAILY_UV", AnalyticsEventType.PAGE_VIEW))
                .isEqualTo(dailyUv);
    }

    private void insertAllOnlyCoverage(
            LocalDate siteDate, long pageViews, long dailyUv) {
        for (AnalyticsEventType eventType : AnalyticsEventType.values()) {
            long count = eventType == AnalyticsEventType.PAGE_VIEW ? pageViews : 0;
            insertAllMetric(siteDate, "EVENT_COUNT", eventType, count);
        }
        insertAllMetric(siteDate, "PV", AnalyticsEventType.PAGE_VIEW, pageViews);
        insertAllMetric(siteDate, "DAILY_UV", AnalyticsEventType.PAGE_VIEW, dailyUv);
    }

    private void insertAllMetric(
            LocalDate siteDate,
            String metric,
            AnalyticsEventType eventType,
            long count) {
        assertThat(jdbc.sql("""
                        insert into portfolio.analytics_daily(
                            site_date, metric, event_type, dimension,
                            dimension_value, metric_count, aggregation_version
                        ) values (
                            :siteDate, :metric, :eventType, 'ALL',
                            '(all)', :metricCount, 'analytics-rules-v1'
                        )
                        """)
                .param("siteDate", siteDate)
                .param("metric", metric)
                .param("eventType", eventType.name())
                .param("metricCount", count)
                .update()).isOne();
    }

    private void insertEvent(int sequence, Instant receivedAt, String visitorKey) {
        int inserted = eventMapper.insertIgnoreClientRetry(new AnalyticsEventRecord(
                UUID.fromString("50000000-0000-4000-8000-%012d".formatted(sequence * 10 + 1)),
                UUID.fromString("50000000-0000-4000-8000-%012d".formatted(sequence * 10 + 2)),
                receivedAt.atZone(SITE_ZONE).toLocalDate(),
                receivedAt,
                visitorKey,
                Integer.toHexString(Math.floorMod(sequence, 16)).repeat(64),
                AnalyticsEventType.PAGE_VIEW,
                "HOME",
                null,
                "(direct)",
                DeviceClass.DESKTOP,
                LocaleCode.EN,
                "analytics-rules-v1",
                receivedAt));
        assertThat(inserted).isOne();
    }

    private void bulkInsertPageViews(
            int count, Instant receivedAt, LocalDate siteDate) {
        assertThat(jdbc.sql("""
                        insert into portfolio.analytics_event(
                            id, client_event_id, site_date, received_at,
                            visitor_day_key, session_day_key, event_type, page_key,
                            project_id, referrer_domain, device_class, locale,
                            rules_version, created_at
                        )
                        select (
                                   '60000000-0000-4000-8000-'
                                   || lpad(series::text, 12, '0')
                               )::uuid,
                               (
                                   '61000000-0000-4000-8000-'
                                   || lpad(series::text, 12, '0')
                               )::uuid,
                               :siteDate,
                               :receivedAt,
                               repeat('e', 64),
                               repeat('f', 64),
                               'PAGE_VIEW',
                               'HOME',
                               null,
                               '(direct)',
                               'DESKTOP',
                               'en',
                               'analytics-rules-v1',
                               :receivedAt
                        from generate_series(1, :eventCount) series
                        """)
                .param("siteDate", siteDate)
                .param("receivedAt", receivedAt.atOffset(ZoneOffset.UTC))
                .param("eventCount", count)
                .update()).isEqualTo(count);
    }

    private MaintenanceRun retentionRun(UUID runId) {
        return jdbc.sql("""
                        select status,
                               (details ->> 'deleted_count')::bigint deleted_count,
                               (details ->> 'cutoff_epoch_second')::bigint cutoff_epoch_second,
                               error_summary,
                               details::text details
                        from portfolio.maintenance_run
                        where id=:id
                        """)
                .param("id", runId)
                .query((row, number) -> new MaintenanceRun(
                        row.getString("status"),
                        row.getLong("deleted_count"),
                        row.getLong("cutoff_epoch_second"),
                        row.getString("error_summary"),
                        row.getString("details")))
                .single();
    }

    private void clearAnalytics() {
        migratorJdbc()
                .sql("""
                        truncate table
                            portfolio.analytics_event,
                            portfolio.analytics_retention_checkpoint
                        """)
                .update();
        jdbc.sql("delete from portfolio.analytics_daily").update();
        migratorJdbc()
                .sql("delete from portfolio.maintenance_run where run_type like 'ANALYTICS_%'")
                .update();
        migratorJdbc()
                .sql("""
                        delete from portfolio.background_job
                        where idempotency_key like 'analytics-retention-next:%'
                        """)
                .update();
    }

    private static JsonNode payload(LocalDate siteDate) {
        return JSON.valueToTree(Map.of("siteDate", siteDate.toString()));
    }

    private record MaintenanceRun(
            String status,
            long deletedCount,
            long cutoffEpochSecond,
            String errorSummary,
            String details) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock analyticsRetentionTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
