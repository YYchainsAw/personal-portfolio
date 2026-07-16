package xyz.yychainsaw.portfolio.system.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import xyz.yychainsaw.portfolio.media.application.StagingCleanupScheduler;

class StorageProfileConfigurationTest {
    private static final String RELEASE = "release-20260717";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-17T03:00:00Z"), ZoneOffset.UTC);
    private static final Map<String, Object> PRODUCTION_ENVIRONMENT = Map.of(
            "PORTFOLIO_RELEASE_ID", RELEASE,
            "PORTFOLIO_JOBS_WORKER_ENABLED", "true",
            "PORTFOLIO_STAGING_CLEANUP_ENABLED", "true",
            "PORTFOLIO_LOCAL_STORAGE", "/var/lib/portfolio/media",
            "PORTFOLIO_COS_STAGING_ROOT", "/tmp/portfolio-cos-staging",
            "COS_REGION", "ap-guangzhou",
            "COS_BUCKET", "portfolio-1250000000",
            "COS_SECRET_ID", "example-secret-id",
            "COS_SECRET_KEY", "example-secret-key",
            "COS_SESSION_TOKEN", "example-session-token");

    @Test
    void profilesMapExactStorageLocationsAndKeepArchivedCleanupOff() throws Exception {
        ConfigurableEnvironment development = loadProfile("application-dev.yml", Map.of());
        ConfigurableEnvironment production =
                loadProfile("application-prod.yml", PRODUCTION_ENVIRONMENT);
        ConfigurableEnvironment test = loadProfile("application-test.yml", Map.of());

        assertThat(development.getProperty("portfolio.release-id")).isEqualTo("dev");
        assertLocalSafeDefaults(development, "../runtime/media");
        assertLocalSafeDefaults(test, "../runtime/test-media");

        assertThat(production.getProperty("portfolio.release-id")).isEqualTo(RELEASE);
        assertThat(production.getProperty("portfolio.jobs.worker-enabled")).isEqualTo("true");
        assertThat(production.getProperty("portfolio.storage.default-provider"))
                .isEqualTo("TENCENT_COS");
        assertThat(production.getProperty("portfolio.storage.local.root"))
                .isEqualTo("/var/lib/portfolio/media");
        assertThat(production.getProperty("portfolio.storage.cos.region"))
                .isEqualTo("ap-guangzhou");
        assertThat(production.getProperty("portfolio.storage.cos.bucket"))
                .isEqualTo("portfolio-1250000000");
        assertThat(production.getProperty("portfolio.storage.cos.secret-id"))
                .isEqualTo("example-secret-id");
        assertThat(production.getProperty("portfolio.storage.cos.secret-key"))
                .isEqualTo("example-secret-key");
        assertThat(production.getProperty("portfolio.storage.cos.session-token"))
                .isEqualTo("example-session-token");
        assertThat(production.getProperty("portfolio.storage.cos.staging-root"))
                .isEqualTo("/tmp/portfolio-cos-staging");
        assertThat(production.getProperty("portfolio.media.staging-cleanup.enabled"))
                .isEqualTo("true");
        assertThat(production.getProperty("portfolio.media.cleanup.enabled"))
                .isEqualTo("false");
        assertThat(production.getProperty("portfolio.media.cleanup.cooling-period"))
                .isEqualTo("30d");
    }

    @Test
    void productionProfileActivatesWorkerAndStagingCleanupTogether() throws Exception {
        BackgroundJobService jobs = mock(BackgroundJobService.class);

        productionRunner(jobs).run(context -> {
            assertThat(context).hasSingleBean(DatabaseJobWorker.class);
            assertThat(context).hasSingleBean(StagingCleanupScheduler.class);
            assertThat(context.getEnvironment().getProperty("portfolio.release-id"))
                    .isEqualTo(RELEASE);
            assertThat(context.getEnvironment()
                            .getProperty("portfolio.storage.local.root"))
                    .isEqualTo("/var/lib/portfolio/media");
            assertThat(context.getEnvironment()
                            .getProperty("portfolio.storage.cos.staging-root"))
                    .isEqualTo("/tmp/portfolio-cos-staging");
            assertThat(context.getEnvironment()
                            .getProperty("portfolio.media.cleanup.enabled"))
                    .isEqualTo("false");

            StagingCleanupScheduler cleanup = context.getBean(StagingCleanupScheduler.class);
            cleanup.onApplicationReady();
            cleanup.enqueueDaily();
            verify(jobs, times(2)).enqueue(
                    eq("CLEAN_MEDIA_STAGING"),
                    eq("media-staging-cleanup:" + RELEASE + ":2026-07-17"),
                    anyMap());
        });
    }

    @Test
    void productionProfileFileCanEnableArchivedCleanupOverTheBaseProdDocument() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "PORTFOLIO_MEDIA_CLEANUP_ENABLED=true")
                .run(context -> assertThat(context.getEnvironment()
                                .getProperty("portfolio.media.cleanup.enabled"))
                        .isEqualTo("true"));
    }

    @Test
    void stagingCleanupIsAbsentWhenEitherProductionGateIsExplicitlyDisabled()
            throws Exception {
        BackgroundJobService jobs = mock(BackgroundJobService.class);

        productionRunner(jobs)
                .withPropertyValues("portfolio.jobs.worker-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DatabaseJobWorker.class)
                        .doesNotHaveBean(StagingCleanupScheduler.class));

        productionRunner(jobs)
                .withPropertyValues("portfolio.media.staging-cleanup.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(DatabaseJobWorker.class);
                    assertThat(context).doesNotHaveBean(StagingCleanupScheduler.class);
                });
    }

    @Test
    void deploymentExampleKeepsCredentialsAndVolumeIdentityEmpty() throws Exception {
        Map<String, String> variables = readDotenv(
                repositoryRoot().resolve("deploy/.env.example"));

        assertThat(variables)
                .containsEntry("PORTFOLIO_RELEASE_ID", "")
                .containsEntry("COS_REGION", "")
                .containsEntry("COS_BUCKET", "")
                .containsEntry("COS_SECRET_ID", "")
                .containsEntry("COS_SECRET_KEY", "")
                .containsEntry("PORTFOLIO_LOCAL_STORAGE", "/var/lib/portfolio/media")
                .containsEntry(
                        "PORTFOLIO_COS_STAGING_ROOT", "/tmp/portfolio-cos-staging")
                .containsEntry("PORTFOLIO_LOCAL_VOLUME_ID", "")
                .containsEntry("PORTFOLIO_JOBS_WORKER_ENABLED", "true")
                .containsEntry("PORTFOLIO_STAGING_CLEANUP_ENABLED", "true")
                .containsEntry("PORTFOLIO_MEDIA_CLEANUP_ENABLED", "false");
    }

    private static void assertLocalSafeDefaults(
            ConfigurableEnvironment environment, String expectedRoot) {
        assertThat(environment.getProperty("portfolio.jobs.worker-enabled"))
                .isEqualTo("false");
        assertThat(environment.getProperty("portfolio.storage.default-provider"))
                .isEqualTo("LOCAL");
        assertThat(environment.getProperty("portfolio.storage.local.root"))
                .isEqualTo(expectedRoot);
        assertThat(environment.getProperty("portfolio.media.staging-cleanup.enabled"))
                .isEqualTo("false");
        assertThat(environment.getProperty("portfolio.media.cleanup.enabled"))
                .isEqualTo("false");
        assertThat(environment.getProperty("portfolio.media.cleanup.cooling-period"))
                .isEqualTo("30d");
    }

    private static WebApplicationContextRunner productionRunner(BackgroundJobService jobs) {
        return new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(
                        DatabaseJobWorker.class, StagingCleanupScheduler.class)
                .withBean(BackgroundJobService.class, () -> jobs)
                .withBean(JobHandlerRegistry.class, () -> mock(JobHandlerRegistry.class))
                .withBean(Clock.class, () -> CLOCK)
                .withBean(WorkerShutdownSignal.class, WorkerShutdownSignal::new)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "PORTFOLIO_RELEASE_ID=" + RELEASE,
                        "PORTFOLIO_JOBS_WORKER_ENABLED=true",
                        "PORTFOLIO_STAGING_CLEANUP_ENABLED=true",
                        "PORTFOLIO_LOCAL_STORAGE=/var/lib/portfolio/media",
                        "PORTFOLIO_COS_STAGING_ROOT=/tmp/portfolio-cos-staging",
                        "COS_REGION=ap-guangzhou",
                        "COS_BUCKET=portfolio-1250000000",
                        "COS_SECRET_ID=example-secret-id",
                        "COS_SECRET_KEY=example-secret-key");
    }

    private static ConfigurableEnvironment loadProfile(
            String resourceName, Map<String, Object> variables) throws IOException {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("task8-environment", variables));
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                resourceName, new ClassPathResource(resourceName));
        for (PropertySource<?> source : sources) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    private static Map<String, String> readDotenv(Path path) throws IOException {
        Map<String, String> variables = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(path)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            assertThat(separator).as("dotenv assignment: %s", line).isPositive();
            String previous = variables.put(
                    line.substring(0, separator), line.substring(separator + 1));
            assertThat(previous).as("duplicate dotenv name: %s", line).isNull();
        }
        return variables;
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("backend-parent"))
                    && Files.isDirectory(candidate.resolve("deploy"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("repository root is unavailable");
    }
}
