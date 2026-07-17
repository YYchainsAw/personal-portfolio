package xyz.yychainsaw.portfolio.content.importer;

public record ImportIssue(Severity severity, String path, String code, String message) {
    public enum Severity { STRUCTURE_ERROR, PUBLISH_WARNING }
}
