package xyz.yychainsaw.portfolio.media.storage;

import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public record StorageLocation(StorageProvider provider, String bucket, String region) {
    private static final int MAXIMUM_LOCATION_PART_LENGTH = 64;
    private static final String INVALID = "Storage location is invalid";

    public StorageLocation {
        if (provider == null) {
            throw invalid();
        }
        if (provider == StorageProvider.LOCAL) {
            if (bucket != null || region != null) {
                throw invalid();
            }
        } else if (!validPart(bucket) || !validPart(region)) {
            throw invalid();
        }
    }

    private static boolean validPart(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > MAXIMUM_LOCATION_PART_LENGTH
                || !value.equals(value.trim())) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
}
