package xyz.yychainsaw.portfolio.content.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnNotWebApplication
@ConditionalOnProperty(name = "portfolio.cli.command", havingValue = "import")
public final class PortfolioImportCli implements ApplicationRunner, ExitCodeGenerator {
    private static final int STRUCTURE_ERROR_EXIT_CODE = 2;
    private static final int CONFLICT_EXIT_CODE = 3;

    private final PortfolioImportService service;
    private final ObjectWriter json;
    private final String input;
    private final String assetRoot;
    private final String expectedSha256;
    private final boolean commit;

    private volatile ImportReport report;
    private volatile boolean conflict;

    public PortfolioImportCli(
            PortfolioImportService service,
            ObjectMapper objectMapper,
            @Value("${portfolio.import.input}") String input,
            @Value("${portfolio.import.asset-root}") String assetRoot,
            @Value("${portfolio.import.sha256}") String expectedSha256,
            @Value("${portfolio.import.commit}") boolean commit) {
        this.service = Objects.requireNonNull(service, "portfolio import service is required");
        this.json = Objects.requireNonNull(objectMapper, "object mapper is required")
                .writer()
                .without(SerializationFeature.INDENT_OUTPUT);
        this.input = requireText(input, "portfolio import input is required");
        this.assetRoot = requireText(assetRoot, "portfolio import asset root is required");
        this.expectedSha256 = requireText(
                expectedSha256, "portfolio import SHA-256 is required");
        this.commit = commit;
    }

    @Override
    public void run(ApplicationArguments ignored) {
        report = null;
        conflict = false;

        ImportReport result;
        try {
            Path inputPath = Path.of(input);
            Path assetRootPath = Path.of(assetRoot);
            result = commit
                    ? service.commit(inputPath, assetRootPath, expectedSha256)
                    : service.dryRun(inputPath, assetRootPath, expectedSha256);
        } catch (DomainException failure) {
            if (failure.status() != HttpStatus.CONFLICT) {
                throw failure;
            }
            conflict = true;
            result = new ImportReport(
                    expectedSha256, false, 0, 0, 0, List.of());
        }

        report = Objects.requireNonNull(result, "portfolio import report is required");
        System.out.println(serialize(report));
        System.out.flush();
    }

    @Override
    public int getExitCode() {
        if (conflict) {
            return CONFLICT_EXIT_CODE;
        }
        ImportReport current = report;
        return current != null && current.hasStructureErrors()
                ? STRUCTURE_ERROR_EXIT_CODE
                : 0;
    }

    private String serialize(ImportReport value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("portfolio import report serialization failed");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
