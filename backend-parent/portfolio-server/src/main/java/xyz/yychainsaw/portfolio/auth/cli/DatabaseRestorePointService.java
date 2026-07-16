package xyz.yychainsaw.portfolio.auth.cli;

import java.nio.file.Path;
import java.util.regex.Pattern;

public interface DatabaseRestorePointService {
    RestorePoint create();

    record RestorePoint(Path path, String sha256) {
        private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

        public RestorePoint {
            if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
                throw new IllegalArgumentException("restore-point path must be absolute and normalized");
            }
            if (sha256 == null || !SHA_256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("restore-point checksum must be lowercase SHA-256");
            }
        }

        @Override
        public String toString() {
            return "RestorePoint[path=<redacted>, sha256=" + sha256 + ']';
        }
    }
}
