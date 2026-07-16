package xyz.yychainsaw.portfolio.media.storage;

import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public interface StorageRouter {
    StorageService defaultWriter();

    StorageService require(StorageProvider provider);
}
