package xyz.yychainsaw.portfolio.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class LocalPublicationFenceConditionTest {
    @Test
    void nonWebCliContextDoesNotCreateTheFenceOrTouchItsDependencies() {
        DataSource dataSource = mock(DataSource.class);

        new ApplicationContextRunner()
                .withBean(DataSource.class, () -> dataSource)
                .withUserConfiguration(FenceConfiguration.class)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(LocalPublicationFence.class)).isEmpty();
                });

        verifyNoInteractions(dataSource);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LocalPublicationFence.class)
    static class FenceConfiguration {}
}
