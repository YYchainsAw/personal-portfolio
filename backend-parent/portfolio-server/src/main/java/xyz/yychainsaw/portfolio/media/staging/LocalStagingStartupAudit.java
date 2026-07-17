package xyz.yychainsaw.portfolio.media.staging;

import java.util.Objects;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class LocalStagingStartupAudit implements ApplicationRunner {
    private final LocalStagingReconciliationService reconciliation;

    public LocalStagingStartupAudit(LocalStagingReconciliationService reconciliation) {
        this.reconciliation = Objects.requireNonNull(
                reconciliation, "local staging reconciliation is required");
    }

    @Override
    public void run(ApplicationArguments arguments) {
        reconciliation.auditStartup();
    }
}
