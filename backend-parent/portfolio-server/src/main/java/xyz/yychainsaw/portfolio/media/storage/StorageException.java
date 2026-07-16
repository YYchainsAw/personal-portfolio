package xyz.yychainsaw.portfolio.media.storage;

public final class StorageException extends RuntimeException {
    private final String code;

    public StorageException(String code) {
        this(code, null);
    }

    public StorageException(String code, Throwable cause) {
        super(requireCode(code), cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Storage error code is required");
        }
        return code;
    }
}
