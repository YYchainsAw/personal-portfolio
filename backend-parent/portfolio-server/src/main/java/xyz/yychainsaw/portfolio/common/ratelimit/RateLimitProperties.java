package xyz.yychainsaw.portfolio.common.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.rate-limit")
public record RateLimitProperties(int maximumSubjects, Map<String, Policy> policies) {
    public RateLimitProperties {
        if (maximumSubjects < 1) {
            throw new IllegalArgumentException("maximumSubjects must be positive");
        }
        if (policies == null || policies.isEmpty()) {
            throw new IllegalArgumentException("policies must not be null or empty");
        }
        for (Map.Entry<String, Policy> entry : policies.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("policy name must not be blank");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("policy '" + name + "' must not be null");
            }
        }
        policies = Map.copyOf(policies);
    }

    public record Policy(int limit, Duration window) {
        public Policy {
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be positive");
            }
            if (window == null) {
                throw new IllegalArgumentException("window must not be null");
            }
            if (window.toSeconds() < 1) {
                throw new IllegalArgumentException("window must be at least one second");
            }
        }
    }
}
