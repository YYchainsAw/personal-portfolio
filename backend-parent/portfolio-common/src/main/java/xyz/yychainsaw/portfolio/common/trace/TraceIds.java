package xyz.yychainsaw.portfolio.common.trace;

import java.util.UUID;
import org.slf4j.MDC;

public final class TraceIds {
    public static final String MDC_KEY = "traceId";

    private TraceIds() {
    }

    public static String current() {
        String traceId = MDC.get(MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = fresh();
            MDC.put(MDC_KEY, traceId);
        }
        return traceId;
    }

    static String fresh() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
