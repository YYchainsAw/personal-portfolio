package xyz.yychainsaw.portfolio.analytics.config;

import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@ConditionalOnWebApplication(type = Type.SERVLET)
final class AnalyticsProductionConfigurationValidator {
    AnalyticsProductionConfigurationValidator(
            AnalyticsProperties properties, Environment environment) {
        AnalyticsProperties configured = Objects.requireNonNull(
                properties, "analytics properties are required");
        if (!configured.configured()) {
            throw new IllegalStateException(
                    "analytics HMAC secret must be configured in production");
        }
        Environment configuration = Objects.requireNonNull(
                environment, "analytics environment is required");
        boolean workerEnabled = configuration.getProperty(
                "portfolio.jobs.worker-enabled", Boolean.class, false);
        boolean schedulingEnabled = configuration.getProperty(
                "portfolio.analytics.maintenance-scheduling-enabled",
                Boolean.class,
                true);
        if (!workerEnabled || !schedulingEnabled) {
            throw new IllegalStateException(
                    "analytics maintenance must be enabled in production");
        }
    }
}
