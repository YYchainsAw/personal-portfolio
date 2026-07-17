package xyz.yychainsaw.portfolio.media.application;

import java.util.UUID;

public interface MediaChangeListener {
    void onMediaChanged(UUID assetId, MediaChangeType changeType);
}
