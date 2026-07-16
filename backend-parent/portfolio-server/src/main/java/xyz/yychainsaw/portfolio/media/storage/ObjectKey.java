package xyz.yychainsaw.portfolio.media.storage;

import java.util.Locale;
import java.util.Set;

final class ObjectKey {
    private static final int MAX_KEY_LENGTH = 1024;
    private static final int MAX_SEGMENT_LENGTH = 255;
    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final String INVALID_KEY = "Invalid storage object key";

    private final String value;
    private final String[] segments;

    private ObjectKey(String value, String[] segments) {
        this.value = value;
        this.segments = segments;
    }

    static ObjectKey parse(String candidate) {
        if (candidate == null
                || candidate.isEmpty()
                || candidate.length() > MAX_KEY_LENGTH
                || candidate.charAt(0) == '/'
                || candidate.charAt(candidate.length() - 1) == '/') {
            throw invalid();
        }

        String[] segments = candidate.split("/", -1);
        for (String segment : segments) {
            validateSegment(segment);
        }
        return new ObjectKey(candidate, segments);
    }

    private static void validateSegment(String segment) {
        if (segment.isEmpty()
                || segment.length() > MAX_SEGMENT_LENGTH
                || segment.equals(".")
                || segment.equals("..")
                || segment.endsWith(".")) {
            throw invalid();
        }
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            boolean safe = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-'
                    || character == '_'
                    || character == '.';
            if (!safe) {
                throw invalid();
            }
        }

        int extension = segment.indexOf('.');
        String baseName = (extension < 0 ? segment : segment.substring(0, extension))
                .toUpperCase(Locale.ROOT);
        if (WINDOWS_DEVICE_NAMES.contains(baseName)) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_KEY);
    }

    String value() {
        return value;
    }

    String[] segments() {
        return segments.clone();
    }
}
