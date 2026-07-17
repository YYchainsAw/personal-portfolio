package xyz.yychainsaw.portfolio.publishing.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.yychainsaw.portfolio.publishing.application.SafeMarkdownRenderer;

@Configuration
public class PublishingConfiguration {
    @Bean
    SnapshotCodec snapshotCodec(ObjectMapper objectMapper) {
        return new SnapshotCodec(objectMapper);
    }

    @Bean
    SafeMarkdownRenderer safeMarkdownRenderer() {
        return new SafeMarkdownRenderer();
    }
}
