package xyz.yychainsaw.portfolio.media.application;

import java.io.InputStream;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;

interface LocalMediaIngestSession extends AutoCloseable {
    void prepareOuterTransaction();

    StoredObject publish(InputStream input, long contentLength);

    void cleanupKnownRollback();

    @Override
    void close();
}
