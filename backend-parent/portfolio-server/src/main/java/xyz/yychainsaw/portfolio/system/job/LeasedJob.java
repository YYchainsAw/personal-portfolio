package xyz.yychainsaw.portfolio.system.job;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record LeasedJob(
        UUID id,
        String jobType,
        JsonNode payload,
        int attempts,
        String leaseOwner) {

    public LeasedJob {
        if (id == null
                || !JobHandlerRegistry.isCanonicalType(jobType)
                || payload == null
                || !payload.isObject()
                || attempts < 1
                || attempts > BackgroundJobService.MAX_ATTEMPTS
                || !BackgroundJobService.isValidWorkerId(leaseOwner)) {
            throw new IllegalArgumentException("leased job is invalid");
        }
        payload = payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }
}
