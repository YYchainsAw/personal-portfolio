package xyz.yychainsaw.portfolio.analytics.config;

import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
final class AnalyticsProductionConfigurationValidator {
    AnalyticsProductionConfigurationValidator(AnalyticsProperties properties) {
        AnalyticsProperties configured = Objects.requireNonNull(
                properties, "analytics properties are required");
        if (!configured.configured()) {
            throw new IllegalStateException(
                    "analytics HMAC secret must be configured in production");
        }
    }
}
