package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;
import xyz.yychainsaw.portfolio.media.storage.StorageException;
import xyz.yychainsaw.portfolio.media.storage.TencentCosStorageService;

class StagingCleanupSchedulerTest {
    private static final String RELEASE = "portfolio-2026.07.17-a";

    @Test
    void startupBeforeFourUsesThePreviousHongKongBoundary() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        StagingCleanupScheduler scheduler = scheduler(
                jobs, "2026-07-16T19:59:59Z", RELEASE, List.of());

        scheduler.onApplicationReady();

        verify(jobs).enqueue(
                "CLEAN_MEDIA_STAGING",
                "media-staging-cleanup:" + RELEASE + ":2026-07-16",
                Map.of("cutoffEpochSecond",
                        Instant.parse("2026-07-14T20:00:00Z").getEpochSecond()));
    }

    @Test
    void startupAtFourUsesTheCurrentHongKongBoundary() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        StagingCleanupScheduler scheduler = scheduler(
                jobs, "2026-07-16T20:00:00Z", RELEASE, List.of());

        scheduler.onApplicationReady();

        verify(jobs).enqueue(
                "CLEAN_MEDIA_STAGING",
                "media-staging-cleanup:" + RELEASE + ":2026-07-17",
                Map.of("cutoffEpochSecond",
                        Instant.parse("2026-07-15T20:00:00Z").getEpochSecond()));
    }

    @Test
    void dailyEntryPointUsesTheSameBoundaryAndHasTheExactSchedule() throws Exception {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        StagingCleanupScheduler scheduler = scheduler(
                jobs, "2026-07-17T03:00:00Z", RELEASE, List.of());

        scheduler.enqueueDaily();

        verify(jobs).enqueue(
                "CLEAN_MEDIA_STAGING",
                "media-staging-cleanup:" + RELEASE + ":2026-07-17",
                Map.of("cutoffEpochSecond",
                        Instant.parse("2026-07-15T20:00:00Z").getEpochSecond()));
        Method daily = StagingCleanupScheduler.class.getMethod("enqueueDaily");
        Scheduled scheduled = daily.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 0 4 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Hong_Kong");
    }

    @Test
    void startupEntryPointIsBoundOnlyToApplicationReady() throws Exception {
        Method startup = StagingCleanupScheduler.class.getMethod("onApplicationReady");
        EventListener listener = startup.getAnnotation(EventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.classes()).containsExactly(ApplicationReadyEvent.class);
    }

    @Test
    void periodicRetryUsesABoundedConfigurableDelayAndTheSameIdempotentEnqueue()
            throws Exception {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        StagingCleanupScheduler scheduler = scheduler(
                jobs, "2026-07-17T03:00:00Z", RELEASE, List.of());

        scheduler.retryPendingBoundary();

        verify(jobs).enqueue(
                "CLEAN_MEDIA_STAGING",
                "media-staging-cleanup:" + RELEASE + ":2026-07-17",
                Map.of("cutoffEpochSecond",
                        Instant.parse("2026-07-15T20:00:00Z").getEpochSecond()));
        Method retry = StagingCleanupScheduler.class.getMethod("retryPendingBoundary");
        Scheduled scheduled = retry.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.initialDelayString()).isEqualTo(
                "${portfolio.media.staging-cleanup.retry-interval:PT5M}");
        assertThat(scheduled.fixedDelayString()).isEqualTo(
                "${portfolio.media.staging-cleanup.retry-interval:PT5M}");
    }

    @Test
    void retryDoesNotRunPerNodeCosScratchOutsideStartupOrDaily() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        TencentCosStorageService cos = mock(TencentCosStorageService.class);
        StagingCleanupScheduler scheduler = scheduler(
                jobs, "2026-07-17T03:00:00Z", RELEASE, List.of(cos));

        scheduler.retryPendingBoundary();

        verify(cos, org.mockito.Mockito.never()).cleanupScratch(
                org.mockito.ArgumentMatchers.any(Instant.class));
    }

    @Test
    void aFailedDailyScratchScavengeIsRetriedOnTheBoundedTick() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        TencentCosStorageService cos = mock(TencentCosStorageService.class);
        Instant cutoff = Instant.parse("2026-07-15T20:00:00Z");
        org.mockito.Mockito.when(cos.cleanupScratch(cutoff))
                .thenThrow(new StorageException("COS_STAGING_CLEANUP_FAILED"))
                .thenReturn(new xyz.yychainsaw.portfolio.media.storage.StagingCleanupResult(
                        1, 1, 1, Duration.ofMillis(1)));
        StagingCleanupScheduler scheduler = scheduler(
                jobs, "2026-07-17T03:00:00Z", RELEASE, List.of(cos));

        org.assertj.core.api.Assertions.assertThatThrownBy(scheduler::enqueueDaily)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_STAGING_CLEANUP_FAILED")
                .hasNoCause();
        org.assertj.core.api.Assertions.assertThatCode(scheduler::retryPendingBoundary)
                .doesNotThrowAnyException();

        verify(cos, org.mockito.Mockito.times(2)).cleanupScratch(cutoff);
    }

    @Test
    void startupEnqueueFailurePropagatesInsteadOfReportingFalseReadiness() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        org.mockito.Mockito.when(jobs.enqueue(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyMap()))
                .thenThrow(new IllegalStateException("JOB_ENQUEUE_FAILED"));
        StagingCleanupScheduler scheduler = scheduler(
                jobs, "2026-07-17T03:00:00Z", RELEASE, List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(scheduler::onApplicationReady)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("JOB_ENQUEUE_FAILED");
    }

    @Test
    void failedDailyEnqueueIsRetriedWithTheSameCurrentBoundaryKey() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        TencentCosStorageService cos = mock(TencentCosStorageService.class);
        org.mockito.Mockito.when(jobs.enqueue(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyMap()))
                .thenThrow(new IllegalStateException("JOB_ENQUEUE_FAILED"))
                .thenReturn(java.util.UUID.randomUUID());
        StagingCleanupScheduler scheduler = scheduler(
                jobs, "2026-07-17T03:00:00Z", RELEASE, List.of(cos));

        org.assertj.core.api.Assertions.assertThatThrownBy(scheduler::enqueueDaily)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("JOB_ENQUEUE_FAILED");
        org.assertj.core.api.Assertions.assertThatCode(scheduler::retryPendingBoundary)
                .doesNotThrowAnyException();

        verify(jobs, org.mockito.Mockito.times(2)).enqueue(
                "CLEAN_MEDIA_STAGING",
                "media-staging-cleanup:" + RELEASE + ":2026-07-17",
                Map.of("cutoffEpochSecond",
                        Instant.parse("2026-07-15T20:00:00Z").getEpochSecond()));
        verify(cos).cleanupScratch(Instant.parse("2026-07-15T20:00:00Z"));
    }

    @Test
    void replicasOfOneReleaseShareAKeyAndDifferentReleasesDoNot() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-17T03:00:00Z"), ZoneOffset.UTC);
        new StagingCleanupScheduler(jobs, clock, "release-a", List.of())
                .onApplicationReady();
        new StagingCleanupScheduler(jobs, clock, "release-a", List.of())
                .onApplicationReady();
        new StagingCleanupScheduler(jobs, clock, "release-b", List.of())
                .onApplicationReady();
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);

        verify(jobs, org.mockito.Mockito.times(3)).enqueue(
                org.mockito.ArgumentMatchers.eq("CLEAN_MEDIA_STAGING"),
                keys.capture(),
                org.mockito.ArgumentMatchers.eq(Map.of(
                        "cutoffEpochSecond",
                        Instant.parse("2026-07-15T20:00:00Z").getEpochSecond())));
        assertThat(keys.getAllValues()).containsExactly(
                "media-staging-cleanup:release-a:2026-07-17",
                "media-staging-cleanup:release-a:2026-07-17",
                "media-staging-cleanup:release-b:2026-07-17");
    }

    @Test
    void rejectsEveryNonCanonicalReleaseIdAtConstruction() {
        for (String invalid : new String[] {
                null,
                "",
                "UPPER",
                " leading",
                "trailing ",
                "slash/release",
                "a".repeat(65)
        }) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new StagingCleanupScheduler(
                            mock(BackgroundJobService.class),
                            Clock.systemUTC(),
                            invalid,
                            List.of()))
                    .withMessage("media staging cleanup release id is invalid")
                    .withNoCause();
        }
    }

    @Test
    void rejectsUnboundedRetryIntervalsAtConstruction() {
        for (Duration invalid : new Duration[] {
                null,
                Duration.ZERO,
                Duration.ofMillis(999),
                Duration.ofHours(1).plusMillis(1)
        }) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new StagingCleanupScheduler(
                            mock(BackgroundJobService.class),
                            Clock.systemUTC(),
                            RELEASE,
                            List.of(),
                            invalid))
                    .withMessage("media staging cleanup retry interval is invalid")
                    .withNoCause();
        }
    }

    @Test
    void everyReplicaScavengesItsOwnCosScratchAfterTheDurableEnqueue() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        TencentCosStorageService first = mock(TencentCosStorageService.class);
        TencentCosStorageService second = mock(TencentCosStorageService.class);
        StagingCleanupScheduler scheduler = scheduler(
                jobs, "2026-07-17T03:00:00Z", RELEASE, List.of(first, second));

        scheduler.onApplicationReady();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(jobs, first, second);
        order.verify(jobs).enqueue(
                "CLEAN_MEDIA_STAGING",
                "media-staging-cleanup:" + RELEASE + ":2026-07-17",
                Map.of("cutoffEpochSecond",
                        Instant.parse("2026-07-15T20:00:00Z").getEpochSecond()));
        order.verify(first).cleanupScratch(
                Instant.parse("2026-07-15T20:00:00Z"));
        order.verify(second).cleanupScratch(
                Instant.parse("2026-07-15T20:00:00Z"));
    }

    @Test
    void cosScratchFailureUsesOneCauseFreeSchedulerSurface() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        TencentCosStorageService cos = mock(TencentCosStorageService.class);
        org.mockito.Mockito.when(cos.cleanupScratch(
                        Instant.parse("2026-07-15T20:00:00Z")))
                .thenThrow(new StorageException(
                        "COS_PRIVATE_FAILURE",
                        new IllegalStateException("C:/secret/path")));
        StagingCleanupScheduler scheduler = scheduler(
                jobs, "2026-07-17T03:00:00Z", RELEASE, List.of(cos));

        org.assertj.core.api.Assertions.assertThatThrownBy(scheduler::onApplicationReady)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_STAGING_CLEANUP_FAILED")
                .hasNoCause();
    }

    @Test
    void beanRequiresServletWorkerAndCleanupGates() {
        ApplicationContextRunner nonServlet = new ApplicationContextRunner()
                .withUserConfiguration(StagingCleanupScheduler.class)
                .withBean(BackgroundJobService.class,
                        () -> mock(BackgroundJobService.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues(
                        "portfolio.jobs.worker-enabled=true",
                        "portfolio.media.staging-cleanup.enabled=true",
                        "portfolio.release-id=" + RELEASE);
        nonServlet.run(context -> assertThat(context)
                .doesNotHaveBean(StagingCleanupScheduler.class));

        WebApplicationContextRunner servlet = new WebApplicationContextRunner()
                .withUserConfiguration(StagingCleanupScheduler.class)
                .withBean(BackgroundJobService.class,
                        () -> mock(BackgroundJobService.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("portfolio.release-id=" + RELEASE);
        servlet.run(context -> assertThat(context)
                .doesNotHaveBean(StagingCleanupScheduler.class));
        servlet.withPropertyValues(
                        "portfolio.jobs.worker-enabled=false",
                        "portfolio.media.staging-cleanup.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(StagingCleanupScheduler.class));
        servlet.withPropertyValues(
                        "portfolio.jobs.worker-enabled=true",
                        "portfolio.media.staging-cleanup.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(StagingCleanupScheduler.class));
        servlet.withPropertyValues(
                        "portfolio.jobs.worker-enabled=true",
                        "portfolio.media.staging-cleanup.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(StagingCleanupScheduler.class));
    }

    private static StagingCleanupScheduler scheduler(
            BackgroundJobService jobs,
            String now,
            String release,
            List<TencentCosStorageService> cosServices) {
        return new StagingCleanupScheduler(
                jobs,
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC),
                release,
                cosServices);
    }
}
