package xyz.yychainsaw.portfolio.media.storage;

import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public record StorageDefaults(StorageProvider defaultProvider) {
    public StorageDefaults {
        if (defaultProvider == null) {
            throw new IllegalArgumentException("Default storage provider is required");
        }
    }
}
