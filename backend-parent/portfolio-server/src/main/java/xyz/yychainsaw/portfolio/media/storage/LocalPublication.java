package xyz.yychainsaw.portfolio.media.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class LocalPublication {
    private final ObjectKey key;
    private final Path target;
    private final Path temporary;
    private final LocalFileIdentity identity;
    private final long contentLength;
    private final String contentType;
    private final String etag;
    private final String failureCode;
    private boolean targetLinked;
    private boolean temporaryDeleted;
    private boolean completed;

    LocalPublication(
            ObjectKey key,
            Path target,
            Path temporary,
            LocalFileIdentity identity,
            long contentLength,
            String contentType,
            String etag,
            String failureCode) {
        this.key = key;
        this.target = target;
        this.temporary = temporary;
        this.identity = identity;
        this.contentLength = contentLength;
        this.contentType = contentType;
        this.etag = etag;
        this.failureCode = failureCode;
    }

    ObjectKey key() {
        return key;
    }

    Path target() {
        return target;
    }

    Path temporary() {
        return temporary;
    }

    LocalFileIdentity identity() {
        return identity;
    }

    long contentLength() {
        return contentLength;
    }

    String contentType() {
        return contentType;
    }

    String etag() {
        return etag;
    }

    String failureCode() {
        return failureCode;
    }

    void markTargetLinked() {
        targetLinked = true;
    }

    void deleteTemporaryAfterValidation() {
        try {
            if (identity.matches(temporary)) {
                Files.delete(temporary);
                temporaryDeleted = true;
            }
        } catch (IOException ignored) {
            // A complete target is validated; a complete temporary alias may remain.
        }
    }

    void finishIdentityGuard() {
        identity.releaseGuard(target);
    }

    void markCompleted() {
        completed = true;
    }

    void cleanupFailure() {
        if (completed) {
            return;
        }
        try {
            boolean targetMatches = targetLinked && identity.matches(target);
            boolean temporaryMatches = !temporaryDeleted && identity.matches(temporary);
            identity.releaseGuard(targetMatches ? target : temporary);
            if (targetMatches) {
                Files.deleteIfExists(target);
            }
            if (temporaryMatches) {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ignored) {
            // Preserve the primary failure and never delete a name with a changed identity.
        }
    }
}
