package xyz.yychainsaw.portfolio.system.job;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

public interface JobHandler {
    String jobType();

    void handle(JsonNode payload) throws Exception;

    default void onDeadLetter(JsonNode payload, String safeSummaryCode) throws Exception {
        // Most jobs need no terminal database reconciliation.
    }
}

@Component
final class JobHandlerRegistry {
    private static final Pattern JOB_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final Map<String, JobHandler> handlers;

    JobHandlerRegistry(Collection<JobHandler> handlers) {
        if (handlers == null) {
            throw new IllegalStateException("job handlers are required");
        }
        Map<String, JobHandler> indexed = new LinkedHashMap<>();
        for (JobHandler handler : handlers) {
            if (handler == null) {
                throw new IllegalStateException("job handler is invalid");
            }
            String jobType;
            try {
                jobType = handler.jobType();
            } catch (RuntimeException exception) {
                throw new IllegalStateException("job handler is invalid");
            }
            if (!isCanonicalType(jobType)) {
                throw new IllegalStateException("job handler is invalid");
            }
            if (indexed.putIfAbsent(jobType, handler) != null) {
                throw new IllegalStateException("duplicate job handler");
            }
        }
        this.handlers = Collections.unmodifiableMap(indexed);
    }

    boolean contains(String jobType) {
        return handlers.containsKey(jobType);
    }

    Optional<JobHandler> find(String jobType) {
        return Optional.ofNullable(handlers.get(jobType));
    }

    static boolean isCanonicalType(String jobType) {
        return jobType != null && JOB_TYPE.matcher(jobType).matches();
    }
}
