package xyz.yychainsaw.portfolio;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;

import static org.assertj.core.api.Assertions.assertThat;

class PortfolioApplicationTest {
    @Test
    void selectsServletWebApplicationTypeByDefault() {
        assertThat(PortfolioApplication.webApplicationType(new String[0]))
                .isEqualTo(WebApplicationType.SERVLET);
    }

    @Test
    void selectsNoneWebApplicationTypeForCliCommand() {
        String[] args = {"--portfolio.cli.command=sync"};

        assertThat(PortfolioApplication.webApplicationType(args))
                .isEqualTo(WebApplicationType.NONE);
    }
}
