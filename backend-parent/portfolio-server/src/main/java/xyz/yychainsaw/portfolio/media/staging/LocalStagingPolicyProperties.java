package xyz.yychainsaw.portfolio.media.staging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "portfolio.media.local-staging")
public record LocalStagingPolicyProperties(
        @DefaultValue("10000") int activeCapacity,
        @DefaultValue("100000") int scanEntryCeiling,
        @DefaultValue("1000") int reservedHeadroom) {
    LocalStagingPolicy toPolicy() {
        return new LocalStagingPolicy(
                activeCapacity,
                scanEntryCeiling,
                LocalStagingPolicy.FIXED_WORST_CASE_ENTRIES_PER_RESERVATION,
                reservedHeadroom);
    }
}
