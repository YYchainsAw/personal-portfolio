package xyz.yychainsaw.portfolio;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class PortfolioApplication {
    public static void main(String[] args) {
        WebApplicationType webApplicationType = webApplicationType(args);
        boolean cli = webApplicationType == WebApplicationType.NONE;
        SpringApplication application = new SpringApplication(PortfolioApplication.class);
        application.setWebApplicationType(webApplicationType);
        ConfigurableApplicationContext context = application.run(args);
        if (cli) {
            int exitCode = SpringApplication.exit(context);
            System.exit(exitCode);
        }
    }

    static WebApplicationType webApplicationType(String[] args) {
        boolean cli = Arrays.stream(args)
                .anyMatch(arg -> arg.startsWith("--portfolio.cli.command="));
        return cli ? WebApplicationType.NONE : WebApplicationType.SERVLET;
    }
}
