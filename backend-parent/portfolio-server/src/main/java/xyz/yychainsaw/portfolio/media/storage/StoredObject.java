package xyz.yychainsaw.portfolio.media.storage;

import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public record StoredObject(
        StorageProvider provider,
        String bucket,
        String region,
        String objectKey,
        long contentLength,
        String contentType,
        String etag) {

    public StoredObject {
        if (provider == null) {
            throw new IllegalArgumentException("Storage provider is required");
        }
        ObjectKey.parse(objectKey);
        if (contentLength <= 0) {
            throw new IllegalArgumentException("Invalid storage content length");
        }
        contentType = requireText(contentType, "Invalid storage content type");
        etag = requireText(etag, "Storage ETag is required");
        if (provider == StorageProvider.LOCAL) {
            if (bucket != null || region != null) {
                throw new IllegalArgumentException("Local storage location is invalid");
            }
        } else {
            bucket = requireText(bucket, "Storage bucket is required");
            region = requireText(region, "Storage region is required");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
