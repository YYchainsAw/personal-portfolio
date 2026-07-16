package xyz.yychainsaw.portfolio.media.storage;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public final class DefaultStorageRouter implements StorageRouter {
    private final Map<StorageProvider, StorageService> services;
    private final StorageProvider defaultProvider;

    public DefaultStorageRouter(Collection<StorageService> services, StorageDefaults defaults) {
        if (services == null) {
            throw new IllegalStateException("Storage services are required");
        }
        if (defaults == null) {
            throw new IllegalStateException("Storage defaults are required");
        }

        EnumMap<StorageProvider, StorageService> indexed = new EnumMap<>(StorageProvider.class);
        for (StorageService service : services) {
            if (service == null) {
                throw new IllegalStateException("Storage service is required");
            }
            StorageProvider provider = service.provider();
            if (provider == null) {
                throw new IllegalStateException("Storage service provider is required");
            }
            if (indexed.putIfAbsent(provider, service) != null) {
                throw new IllegalStateException("Duplicate storage provider");
            }
        }
        if (!indexed.containsKey(defaults.defaultProvider())) {
            throw new IllegalStateException("Default storage provider is not configured");
        }

        this.services = Collections.unmodifiableMap(new EnumMap<>(indexed));
        this.defaultProvider = defaults.defaultProvider();
    }

    @Override
    public StorageService defaultWriter() {
        return require(defaultProvider);
    }

    @Override
    public StorageService require(StorageProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Storage provider is required");
        }
        StorageService service = services.get(provider);
        if (service == null) {
            throw new IllegalStateException("Storage provider is not configured");
        }
        return service;
    }
}
