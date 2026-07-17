package xyz.yychainsaw.portfolio.media.application;

import java.io.InputStream;

public record UploadMediaCommand(
        String filename,
        String declaredContentType,
        long declaredSize,
        InputStream input) {
    @Override
    public String toString() {
        return "UploadMediaCommand[redacted]";
    }
}
