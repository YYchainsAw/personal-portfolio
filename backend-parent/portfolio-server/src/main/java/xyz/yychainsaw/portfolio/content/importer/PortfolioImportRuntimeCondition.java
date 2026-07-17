package xyz.yychainsaw.portfolio.content.importer;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;

final class PortfolioImportRuntimeCondition extends AnyNestedCondition {
    PortfolioImportRuntimeCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnWebApplication(type = Type.SERVLET)
    static final class ServletRuntime {}

    @ConditionalOnProperty(name = "portfolio.cli.command", havingValue = "import")
    static final class ImportCliRuntime {}
}
