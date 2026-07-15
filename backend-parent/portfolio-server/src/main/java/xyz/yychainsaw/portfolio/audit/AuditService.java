package xyz.yychainsaw.portfolio.audit;

public interface AuditService {
    void record(AuditCommand command);
}
