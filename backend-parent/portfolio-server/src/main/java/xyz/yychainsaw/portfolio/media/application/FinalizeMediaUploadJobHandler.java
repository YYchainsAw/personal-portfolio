package xyz.yychainsaw.portfolio.media.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.system.job.JobHandler;

@Component
public final class FinalizeMediaUploadJobHandler implements JobHandler {
    private final MediaFinalizationService finalization;
    private final MediaAssetRepository assets;

    public FinalizeMediaUploadJobHandler(
            MediaFinalizationService finalization, MediaAssetRepository assets) {
        this.finalization = Objects.requireNonNull(
                finalization, "media finalization service is required");
        this.assets = Objects.requireNonNull(assets, "media asset repository is required");
    }

    @Override
    public String jobType() {
        return "FINALIZE_MEDIA_UPLOAD";
    }

    @Override
    public void handle(JsonNode payload) throws InterruptedException {
        UUID assetId = parseAssetId(payload).orElseThrow(() ->
                new IllegalArgumentException("MEDIA_FINALIZER_PAYLOAD_INVALID"));
        finalization.finalizeAsset(assetId);
    }

    @Override
    public void onDeadLetter(JsonNode payload, String safeSummaryCode) {
        Optional<UUID> assetId = parseAssetId(payload);
        if (assetId.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "MEDIA_FINALIZER_DEAD_LETTER_TRANSACTION_REQUIRED");
        }
        assets.markFailedIfProcessing(assetId.orElseThrow());
    }

    private static Optional<UUID> parseAssetId(JsonNode payload) {
        if (!isValidPayload(payload)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(payload.get("assetId").textValue()));
        } catch (IllegalArgumentException invalidUuid) {
            return Optional.empty();
        }
    }

    private static boolean isValidPayload(JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.size() != 1) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = payload.fields();
        Map.Entry<String, JsonNode> field = fields.next();
        if (!"assetId".equals(field.getKey()) || !field.getValue().isTextual()) {
            return false;
        }
        String value = field.getValue().textValue();
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalidUuid) {
            return false;
        }
    }
}
