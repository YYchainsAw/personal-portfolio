package xyz.yychainsaw.portfolio.publishing.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PublishingConfiguration {
    @Bean
    SnapshotCodec snapshotCodec(ObjectMapper objectMapper) {
        return new SnapshotCodec(objectMapper);
    }
}
