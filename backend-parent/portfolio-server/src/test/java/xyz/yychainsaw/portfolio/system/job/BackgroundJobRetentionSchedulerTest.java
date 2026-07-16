package xyz.yychainsaw.portfolio.system.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(OutputCaptureExtension.class)
class BackgroundJobRetentionSchedulerTest {

    @Test
    void dailyRunLogsOnlyAggregateCounts(CapturedOutput output) {
        BackgroundJobRetentionService service =
                mock(BackgroundJobRetentionService.class);
        when(service.deleteExpiredTerminalJobs())
                .thenReturn(new BackgroundJobRetentionResult(731, 2));
        BackgroundJobRetentionScheduler scheduler =
                new BackgroundJobRetentionScheduler(service);

        scheduler.deleteDaily();

        verify(service).deleteExpiredTerminalJobs();
        assertThat(output)
                .contains("Background job retention completed deletedCount=731 batches=2")
                .doesNotContain("payload")
                .doesNotContain("key=")
                .doesNotContain("id=");
    }

    @Test
    void schedulerFailureNeverLeaksTheDependencyMessageOrCause(CapturedOutput output) {
        BackgroundJobRetentionService service =
                mock(BackgroundJobRetentionService.class);
        when(service.deleteExpiredTerminalJobs())
                .thenThrow(new IllegalStateException(
                        "payload={secret} key=private-key id=private-id"));
        BackgroundJobRetentionScheduler scheduler =
                new BackgroundJobRetentionScheduler(service);

        assertThatThrownBy(scheduler::deleteDaily)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("BACKGROUND_JOB_RETENTION_FAILED")
                .hasNoCause();
        assertThat(output)
                .doesNotContain("payload={secret}")
                .doesNotContain("private-key")
                .doesNotContain("private-id");
    }

    @Test
    void dailyScheduleIsFixedToOneHongKongRun() throws Exception {
        Method entryPoint = BackgroundJobRetentionScheduler.class
                .getMethod("deleteDaily");
        Scheduled scheduled = entryPoint.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 30 3 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Hong_Kong");
    }

    @Test
    void beanRequiresBothAServletApplicationAndTheExplicitRetentionGate() {
        ApplicationContextRunner nonServlet = new ApplicationContextRunner()
                .withUserConfiguration(BackgroundJobRetentionScheduler.class)
                .withBean(BackgroundJobRetentionService.class,
                        () -> mock(BackgroundJobRetentionService.class))
                .withPropertyValues("portfolio.jobs.retention.enabled=true");
        nonServlet.run(context -> assertThat(context)
                .doesNotHaveBean(BackgroundJobRetentionScheduler.class));

        WebApplicationContextRunner servlet = new WebApplicationContextRunner()
                .withUserConfiguration(BackgroundJobRetentionScheduler.class)
                .withBean(BackgroundJobRetentionService.class,
                        () -> mock(BackgroundJobRetentionService.class));
        servlet.run(context -> assertThat(context)
                .doesNotHaveBean(BackgroundJobRetentionScheduler.class));
        servlet.withPropertyValues("portfolio.jobs.retention.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(BackgroundJobRetentionScheduler.class));
        servlet.withPropertyValues("portfolio.jobs.retention.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(BackgroundJobRetentionScheduler.class));
    }
}
