package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

class MediaCleanupSchedulerTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-17T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void startupAndDailyUseOneStableHongKongDayKeyAndPayload() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        MediaCleanupScheduler scheduler = new MediaCleanupScheduler(
                jobs, CLOCK, resolverWithChecker());

        scheduler.onApplicationReady();
        scheduler.enqueueDaily();

        verify(jobs, org.mockito.Mockito.times(2)).enqueue(
                "MEDIA_CLEANUP_SCAN",
                "media-cleanup-scan:2026-07-17",
                Map.of(
                        "cutoffEpochSecond",
                        Instant.parse("2026-06-16T16:00:00Z").getEpochSecond()));
    }

    @Test
    void entryPointsAreBoundToReadinessAndOneHongKongRunPerDay() throws Exception {
        Method startup = MediaCleanupScheduler.class.getMethod("onApplicationReady");
        EventListener listener = startup.getAnnotation(EventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.classes()).containsExactly(ApplicationReadyEvent.class);

        Method daily = MediaCleanupScheduler.class.getMethod("enqueueDaily");
        Scheduled scheduled = daily.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 30 4 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Hong_Kong");
    }

    @Test
    void enabledCleanupFailsClosedWithoutAReferenceChecker() {
        assertThatThrownBy(() -> new MediaCleanupScheduler(
                        mock(BackgroundJobService.class),
                        CLOCK,
                        new MediaReferenceResolver(List.of())))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_REFERENCE_CHECKER_REQUIRED")
                .hasNoCause();
    }

    @Test
    void schedulerRequiresServletWorkerAndCleanupGates() {
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withUserConfiguration(MediaCleanupScheduler.class)
                .withBean(BackgroundJobService.class,
                        () -> mock(BackgroundJobService.class))
                .withBean(Clock.class, () -> CLOCK)
                .withBean(MediaReferenceResolver.class,
                        MediaCleanupSchedulerTest::resolverWithChecker);

        runner.run(context -> assertThat(context)
                .doesNotHaveBean(MediaCleanupScheduler.class));
        runner.withPropertyValues(
                        "portfolio.jobs.worker-enabled=true",
                        "portfolio.media.cleanup.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MediaCleanupScheduler.class));
        runner.withPropertyValues(
                        "portfolio.jobs.worker-enabled=false",
                        "portfolio.media.cleanup.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MediaCleanupScheduler.class));
        runner.withPropertyValues(
                        "portfolio.jobs.worker-enabled=true",
                        "portfolio.media.cleanup.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(MediaCleanupScheduler.class));
    }

    private static MediaReferenceResolver resolverWithChecker() {
        return new MediaReferenceResolver(List.of(assetId -> List.of()));
    }
}
