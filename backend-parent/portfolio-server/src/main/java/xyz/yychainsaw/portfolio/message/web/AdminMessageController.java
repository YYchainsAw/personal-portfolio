package xyz.yychainsaw.portfolio.message.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.message.application.MessageDetail;
import xyz.yychainsaw.portfolio.message.application.MessageInboxService;
import xyz.yychainsaw.portfolio.message.application.MessagePage;

@RestController
@RequestMapping("/api/admin/messages")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminMessageController {
    private final MessageInboxService messages;
    private final AdminMessageStatusBodyReader statusBodies;

    public AdminMessageController(
            MessageInboxService messages,
            AdminMessageStatusBodyReader statusBodies) {
        this.messages = Objects.requireNonNull(messages, "message inbox service is required");
        this.statusBodies = Objects.requireNonNull(
                statusBodies, "message status body reader is required");
    }

    @GetMapping
    public ResponseEntity<MessagePage> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {
        return ok(messages.list(status, cursor, limit));
    }

    @GetMapping("/{messageId}")
    public ResponseEntity<MessageDetail> detail(@PathVariable UUID messageId) {
        return ok(messages.detail(messageId));
    }

    @PatchMapping(
            value = "/{messageId}/status",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageDetail> updateStatus(
            @PathVariable UUID messageId,
            HttpServletRequest servletRequest) {
        UpdateMessageStatusRequest request = statusBodies.read(servletRequest);
        return ok(messages.updateStatus(messageId, request.status(), request.version()));
    }

    @PostMapping("/{messageId}/email/retry")
    public ResponseEntity<Void> retryEmail(@PathVariable UUID messageId) {
        messages.retryEmail(messageId);
        return noContent();
    }

    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> delete(@PathVariable UUID messageId) {
        messages.delete(messageId);
        return noContent();
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
