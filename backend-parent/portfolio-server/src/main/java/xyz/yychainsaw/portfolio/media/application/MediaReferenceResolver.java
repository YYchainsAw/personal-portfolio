package xyz.yychainsaw.portfolio.media.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class MediaReferenceResolver {
    private final List<MediaReferenceChecker> checkers;

    public MediaReferenceResolver(List<MediaReferenceChecker> checkers) {
        try {
            this.checkers = List.copyOf(checkers);
        } catch (RuntimeException invalidConfiguration) {
            throw checkFailed();
        }
    }

    public List<MediaReference> findReferences(UUID assetId) {
        if (assetId == null) {
            throw checkFailed();
        }

        Set<MediaReference> references = new LinkedHashSet<>();
        boolean failed = false;
        for (MediaReferenceChecker checker : checkers) {
            try {
                List<MediaReference> checkerReferences = checker.findReferences(assetId);
                if (checkerReferences == null) {
                    failed = true;
                    continue;
                }
                for (MediaReference reference : checkerReferences) {
                    if (!isValid(reference)) {
                        failed = true;
                        continue;
                    }
                    references.add(reference);
                }
            } catch (RuntimeException checkerFailure) {
                failed = true;
            }
        }
        if (failed) {
            throw checkFailed();
        }
        return List.copyOf(references);
    }

    public boolean hasReferences(UUID assetId) {
        return !findReferences(assetId).isEmpty();
    }

    public void requireCheckerForCleanup() {
        if (checkers.isEmpty()) {
            throw new IllegalStateException("MEDIA_REFERENCE_CHECKER_REQUIRED");
        }
    }

    private static boolean isValid(MediaReference reference) {
        if (reference == null
                || reference.referenceType() == null
                || reference.referenceId() == null) {
            return false;
        }
        String referenceType = reference.referenceType();
        return !referenceType.isBlank() && referenceType.equals(referenceType.trim());
    }

    private static IllegalStateException checkFailed() {
        return new IllegalStateException("MEDIA_REFERENCE_CHECK_FAILED");
    }
}
