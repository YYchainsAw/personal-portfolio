package xyz.yychainsaw.portfolio.content.importer;

import java.util.List;

public record ImportReport(
        String sha256,
        boolean committed,
        int projectCount,
        int mediaCount,
        int tagCount,
        List<ImportIssue> issues
) {
    public ImportReport {
        issues = List.copyOf(issues);
    }

    public boolean hasStructureErrors() {
        return issues.stream()
                .anyMatch(issue -> issue.severity() == ImportIssue.Severity.STRUCTURE_ERROR);
    }
}
