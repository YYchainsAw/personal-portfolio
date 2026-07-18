package xyz.yychainsaw.portfolio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

class AnalyticsMaintenanceSchedulerTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-17T16:15:01Z"), ZoneOffset.UTC);

    @Test
    void aggregationUsesOneFinalPreviousDayKeyAndHourlyCurrentDayKeys() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        AnalyticsRetentionRepository retention = mock(AnalyticsRetentionRepository.class);
        AnalyticsAggregationScheduler scheduler = new AnalyticsAggregationScheduler(
                jobs, new AnalyticsRules(), retention, CLOCK);

        scheduler.onApplicationReady();
        scheduler.enqueueDaily();
        scheduler.enqueueHourly();

        verify(jobs, times(2)).enqueue(
                "ANALYTICS_AGGREGATE",
                "analytics-aggregate:2026-07-17:analytics-rules-v1",
                Map.of(
                        "siteDate", "2026-07-17",
                        "aggregationVersion", "analytics-rules-v1"));
        verify(jobs, times(2)).enqueue(
                "ANALYTICS_AGGREGATE",
                "analytics-aggregate:2026-07-18:analytics-rules-v1:hour:2026-07-18T00",
                Map.of(
                        "siteDate", "2026-07-18",
                        "aggregationVersion", "analytics-rules-v1"));
    }

    @Test
    void recoveryUsesAnIndependentHourlyKeyForTheFirstRecentGap() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        AnalyticsRetentionRepository retention = mock(AnalyticsRetentionRepository.class);
        when(retention.findFirstIncompleteAggregateDate(
                        Instant.parse("2026-07-17T16:15:01Z"),
                        LocalDate.parse("2026-06-20")))
                .thenReturn(Optional.of(LocalDate.parse("2026-07-10")));
        AnalyticsAggregationScheduler scheduler = new AnalyticsAggregationScheduler(
                jobs, new AnalyticsRules(), retention, CLOCK);

        scheduler.enqueueRecovery();

        verify(jobs).enqueue(
                "ANALYTICS_AGGREGATE",
                "analytics-aggregate:2026-07-10:analytics-rules-v1:repair:2026-07-18T00",
                Map.of(
                        "siteDate", "2026-07-10",
                        "aggregationVersion", "analytics-rules-v1"));
    }

    @Test
    void retentionUsesOneHongKongCurrentDayKey() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        AnalyticsRetentionScheduler scheduler = new AnalyticsRetentionScheduler(jobs, CLOCK);

        scheduler.onApplicationReady();
        scheduler.enqueueDaily();

        verify(jobs, times(2)).enqueue(
                "ANALYTICS_RETENTION",
                "analytics-retention:2026-07-18",
                Map.of("siteDate", "2026-07-18"));
    }

    @Test
    void annotationsBindReadinessAndHongKongSchedules() throws Exception {
        Method aggregationReady = AnalyticsAggregationScheduler.class
                .getMethod("onApplicationReady");
        assertThat(aggregationReady.getAnnotation(EventListener.class).classes())
                .containsExactly(ApplicationReadyEvent.class);
        assertSchedule(
                AnalyticsAggregationScheduler.class.getMethod("enqueueDaily"),
                "0 15 0 * * *");
        assertSchedule(
                AnalyticsAggregationScheduler.class.getMethod("enqueueHourly"),
                "0 15 * * * *");
        assertSchedule(
                AnalyticsAggregationScheduler.class.getMethod("enqueueRecovery"),
                "0 45 * * * *");

        Method retentionReady = AnalyticsRetentionScheduler.class
                .getMethod("onApplicationReady");
        assertThat(retentionReady.getAnnotation(EventListener.class).classes())
                .containsExactly(ApplicationReadyEvent.class);
        assertSchedule(
                AnalyticsRetentionScheduler.class.getMethod("enqueueDaily"),
                "0 15 2 * * *");
    }

    @Test
    void schedulersRequireServletWorkerAndMaintenanceGates() {
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withUserConfiguration(
                        AnalyticsAggregationScheduler.class,
                        AnalyticsRetentionScheduler.class)
                .withBean(BackgroundJobService.class,
                        () -> mock(BackgroundJobService.class))
                .withBean(AnalyticsRules.class, AnalyticsRules::new)
                .withBean(AnalyticsRetentionRepository.class,
                        () -> mock(AnalyticsRetentionRepository.class))
                .withBean(Clock.class, () -> CLOCK);

        runner.run(context -> assertThat(context)
                .doesNotHaveBean(AnalyticsAggregationScheduler.class)
                .doesNotHaveBean(AnalyticsRetentionScheduler.class));
        runner.withPropertyValues(
                        "portfolio.jobs.worker-enabled=true",
                        "portfolio.analytics.maintenance-scheduling-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(AnalyticsAggregationScheduler.class)
                        .doesNotHaveBean(AnalyticsRetentionScheduler.class));
        runner.withPropertyValues(
                        "portfolio.jobs.worker-enabled=true",
                        "portfolio.analytics.maintenance-scheduling-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AnalyticsAggregationScheduler.class)
                        .hasSingleBean(AnalyticsRetentionScheduler.class));
    }

    private static void assertSchedule(Method method, String cron) {
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo(cron);
        assertThat(scheduled.zone()).isEqualTo("Asia/Hong_Kong");
    }
}
