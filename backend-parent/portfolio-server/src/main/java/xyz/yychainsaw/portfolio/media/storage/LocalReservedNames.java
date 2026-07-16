package xyz.yychainsaw.portfolio.media.storage;

import java.nio.file.Path;
import java.util.UUID;

final class LocalReservedNames {
    private static final String PART_PREFIX = "@part-";
    private static final String IDENTITY_PREFIX = "@identity-";

    private LocalReservedNames() {}

    static Path newPart(Path directory) {
        return directory.resolve(PART_PREFIX + UUID.randomUUID());
    }

    static Path newIdentity(Path directory) {
        return directory.resolve(IDENTITY_PREFIX + UUID.randomUUID());
    }
}
