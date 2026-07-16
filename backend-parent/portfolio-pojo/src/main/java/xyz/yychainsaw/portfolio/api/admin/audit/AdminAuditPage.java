package xyz.yychainsaw.portfolio.api.admin.audit;

import java.util.List;
import java.util.Objects;

public record AdminAuditPage(List<AdminAuditItem> items, String nextCursor) {
    public AdminAuditPage {
        items = List.copyOf(Objects.requireNonNull(items, "audit items are required"));
    }
}
