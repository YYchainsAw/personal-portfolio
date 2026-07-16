package xyz.yychainsaw.portfolio.media.staging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
class LocalStagingCleanupConfiguration {
    @Bean
    @ConditionalOnMissingBean(LocalStagingObjectCleanupPort.class)
    LocalStagingObjectCleanupPort unavailableExactLocalStagingCleaner() {
        return (reservation, asset) -> {
            throw new IllegalStateException("LOCAL_STAGING_EXACT_CLEANER_UNAVAILABLE");
        };
    }
}
