package xyz.yychainsaw.portfolio.media.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.system.job.JobHandler;

@Component
public final class FinalizeMediaUploadJobHandler implements JobHandler {
    @Override
    public String jobType() {
        return "FINALIZE_MEDIA_UPLOAD";
    }

    @Override
    public void handle(JsonNode payload) {
        if (!isValidPayload(payload)) {
            throw new IllegalArgumentException("MEDIA_FINALIZER_PAYLOAD_INVALID");
        }
        throw new IllegalStateException("MEDIA_FINALIZER_NOT_READY");
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
