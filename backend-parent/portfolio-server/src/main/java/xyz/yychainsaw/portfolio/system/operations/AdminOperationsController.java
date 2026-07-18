package xyz.yychainsaw.portfolio.system.operations;

import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/system/operations")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminOperationsController {
    private final OperationsStatusService operations;

    public AdminOperationsController(OperationsStatusService operations) {
        this.operations = Objects.requireNonNull(
                operations, "operations status service is required");
    }

    @GetMapping
    public ResponseEntity<OperationsStatus> getOperations() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(operations.read());
    }
}
