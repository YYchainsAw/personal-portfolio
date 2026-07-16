package xyz.yychainsaw.portfolio.media.application;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class MediaUploadService {
    private final CurrentAdminProvider currentAdmin;
    private final MediaIngestService ingest;

    public MediaUploadService(
            CurrentAdminProvider currentAdmin, MediaIngestService ingest) {
        this.currentAdmin = Objects.requireNonNull(
                currentAdmin, "current administrator provider is required");
        this.ingest = Objects.requireNonNull(ingest, "media ingest service is required");
    }

    public MediaAssetView upload(UploadMediaCommand command) {
        if (command == null) {
            throw requestInvalid();
        }

        UUID actorId;
        try {
            actorId = currentAdmin.requireAdminId();
        } catch (RuntimeException authenticationFailure) {
            closeBestEffort(command);
            throw authenticationFailure;
        }
        if (actorId == null || command.input() == null) {
            closeBestEffort(command);
            throw requestInvalid();
        }
        return ingest.ingest(command, actorId);
    }

    private static void closeBestEffort(UploadMediaCommand command) {
        if (command.input() == null) {
            return;
        }
        try {
            command.input().close();
        } catch (IOException | RuntimeException ignored) {
            // Authentication or fixed validation remains the sole outward failure.
        }
    }

    private static DomainException requestInvalid() {
        return new DomainException(
                "MEDIA_REQUEST_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, Map.of());
    }
}
