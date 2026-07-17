package xyz.yychainsaw.portfolio.media.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.CopyObjectRequest;
import com.qcloud.cos.model.CopyObjectResult;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.region.Region;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;

public final class QcloudCosClientAdapter implements CosClientPort, AutoCloseable {
    private static final String NO_OVERWRITE_HEADER = "x-cos-forbid-overwrite";
    private static final String PRIVATE_ACL_HEADER = "x-cos-acl";
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String PDF_DISPOSITION = "attachment; filename=\"document.pdf\"";

    private final QcloudCosSdk sdk;
    private final TencentCosProperties properties;
    private final ReentrantLock lifecycleLock = new ReentrantLock(true);
    private final Condition noActiveOperations = lifecycleLock.newCondition();
    private int activeOperations;
    private boolean closed;
    private boolean shutdownListenerRegistered;
    private Runnable shutdownSuccess = () -> {};

    QcloudCosClientAdapter(QcloudCosSdk sdk, TencentCosProperties properties) {
        if (sdk == null || properties == null) {
            throw new IllegalArgumentException("COS adapter configuration is required");
        }
        this.sdk = sdk;
        this.properties = properties;
    }

    static QcloudCosClientAdapter create(TencentCosProperties properties) {
        COSClient client = new COSClient(credentials(properties), clientConfig(properties));
        return new QcloudCosClientAdapter(new RealQcloudCosSdk(client), properties);
    }

    void onShutdownSuccess(Runnable listener) {
        if (listener == null) {
            throw new IllegalArgumentException("COS shutdown listener is required");
        }
        lifecycleLock.lock();
        try {
            if (closed || shutdownListenerRegistered) {
                throw new IllegalStateException("COS shutdown listener cannot be registered");
            }
            shutdownListenerRegistered = true;
            shutdownSuccess = listener;
        } finally {
            lifecycleLock.unlock();
        }
    }

    static ClientConfig clientConfig(TencentCosProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("COS properties are required");
        }
        ClientConfig config = new ClientConfig(new Region(properties.region()));
        config.setHttpProtocol(HttpProtocol.https);
        config.setErrorLogStatusCodeThresh(Integer.MAX_VALUE);
        config.setPrintShutdownStackTrace(false);
        return config;
    }

    static COSCredentials credentials(TencentCosProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("COS properties are required");
        }
        if (properties.sessionToken() == null) {
            return new BasicCOSCredentials(properties.secretId(), properties.secretKey());
        }
        return new BasicSessionCredentials(
                properties.secretId(), properties.secretKey(), properties.sessionToken());
    }

    @Override
    public StoredObject putCreateOnly(
            String bucket,
            String keyValue,
            InputStream input,
            long contentLength,
            String contentType) {
        beginOperation();
        try {
            requireConfiguredBucket(bucket);
            if (input == null) {
                throw new IllegalArgumentException("Storage input is required");
            }
            ObjectKey key = ObjectKey.parse(keyValue);
            StorageObjectContract.validateContentLength(contentLength);
            String normalized = StorageObjectContract.normalizeContentType(key, contentType);

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(contentLength);
            metadata.setContentType(normalized);
            if (PDF_CONTENT_TYPE.equals(normalized)) {
                metadata.setContentDisposition(PDF_DISPOSITION);
            }
            PutObjectRequest request = new PutObjectRequest(
                    bucket, key.value(), input, metadata);
            applyCreateOnlyHeaders(request);

            String etag;
            try {
                etag = sdk.put(request);
            } catch (CosServiceException exception) {
                if (exception.getStatusCode() == 412) {
                    throw objectExists();
                }
                throw new StorageException("COS_WRITE_FAILED");
            } catch (RuntimeException exception) {
                throw new StorageException("COS_WRITE_FAILED");
            }
            if (!validText(etag)) {
                throw invalidResponse();
            }
            return new StoredObject(
                    StorageProvider.TENCENT_COS,
                    bucket,
                    properties.region(),
                    key.value(),
                    contentLength,
                    normalized,
                    etag);
        } finally {
            endOperation();
        }
    }

    @Override
    public StorageRead open(String bucket, String keyValue, Optional<ByteRange> requestedRange) {
        beginOperation();
        boolean leaseTransferred = false;
        try {
            requireConfiguredBucket(bucket);
            ObjectKey key = ObjectKey.parse(keyValue);
            if (requestedRange == null) {
                throw new IllegalArgumentException("Storage range is required");
            }

            SdkHeadMetadata head = null;
            long totalLength = -1;
            try {
                if (requestedRange.isPresent()) {
                    head = sdk.head(bucket, key.value());
                    if (!validHead(key, head)) {
                        throw invalidResponse();
                    }
                    totalLength = head.contentLength();
                    StorageObjectContract.validateRange(requestedRange, totalLength);
                }

                GetObjectRequest request = new GetObjectRequest(bucket, key.value());
                if (requestedRange.isPresent()) {
                    ByteRange range = requestedRange.orElseThrow();
                    request.setRange(range.startInclusive(), range.endInclusive());
                    request.setMatchingETagConstraints(List.of(head.etag()));
                }
                SdkReadResponse response = sdk.get(request);
                if (!validRead(key, requestedRange, totalLength, head, response)) {
                    closeQuietly(response);
                    throw invalidResponse();
                }

                long resolvedTotal = requestedRange.isPresent()
                        ? totalLength
                        : response.instanceLength();
                try {
                    BoundedInputStream bounded = new BoundedInputStream(
                            new OperationLeaseInputStream(
                                    new SdkResponseInputStream(response), this::endOperation),
                            response.responseLength());
                    StorageRead read = new StorageRead(
                            bounded,
                            resolvedTotal,
                            requestedRange,
                            response.responseLength(),
                            response.contentType(),
                            response.etag());
                    leaseTransferred = true;
                    return read;
                } catch (IllegalArgumentException exception) {
                    closeQuietly(response);
                    throw invalidResponse();
                }
            } catch (StorageRangeNotSatisfiableException exception) {
                throw new StorageRangeNotSatisfiableException(exception.totalLength());
            } catch (StorageException exception) {
                if ("COS_INVALID_RESPONSE".equals(exception.code())) {
                    throw invalidResponse();
                }
                throw new StorageException("COS_READ_FAILED");
            } catch (RuntimeException exception) {
                throw new StorageException("COS_READ_FAILED");
            }
        } finally {
            if (!leaseTransferred) {
                endOperation();
            }
        }
    }

    @Override
    public URI signGet(String bucket, String keyValue, Instant expiresAt) {
        beginOperation();
        try {
            requireConfiguredBucket(bucket);
            ObjectKey key = ObjectKey.parse(keyValue);
            if (expiresAt == null) {
                throw new IllegalArgumentException("COS signed GET expiration is required");
            }
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                    bucket, key.value(), HttpMethodName.GET);
            try {
                request.setExpiration(Date.from(expiresAt));
                URL signed = sdk.sign(request, true);
                URI uri = signed == null ? null : signed.toURI();
                requireOfficialUri(bucket, key, uri);
                return uri;
            } catch (DateTimeException | ArithmeticException | URISyntaxException exception) {
                throw new StorageException("COS_SIGN_FAILED");
            } catch (StorageException exception) {
                if ("COS_INVALID_RESPONSE".equals(exception.code())) {
                    throw invalidResponse();
                }
                throw new StorageException("COS_SIGN_FAILED");
            } catch (RuntimeException exception) {
                throw new StorageException("COS_SIGN_FAILED");
            }
        } finally {
            endOperation();
        }
    }

    @Override
    public boolean exists(String bucket, String keyValue) {
        beginOperation();
        try {
            requireConfiguredBucket(bucket);
            ObjectKey key = ObjectKey.parse(keyValue);
            try {
                return sdk.exists(bucket, key.value());
            } catch (RuntimeException exception) {
                throw new StorageException("COS_EXISTS_FAILED");
            }
        } finally {
            endOperation();
        }
    }

    @Override
    public void copyCreateOnly(String bucket, String sourceValue, String targetValue) {
        beginOperation();
        try {
            requireConfiguredBucket(bucket);
            ObjectKey source = ObjectKey.parse(sourceValue);
            ObjectKey target = ObjectKey.parse(targetValue);
            if (!StorageObjectContract.contentType(source)
                    .equals(StorageObjectContract.contentType(target))) {
                throw new IllegalArgumentException("Invalid storage content type");
            }
            CopyObjectRequest request = new CopyObjectRequest(
                    bucket, source.value(), bucket, target.value());
            applyCreateOnlyHeaders(request);
            try {
                String etag = sdk.copy(request);
                if (etag == null) {
                    throw objectExists();
                }
                if (!validText(etag)) {
                    throw invalidResponse();
                }
            } catch (CosServiceException exception) {
                if (exception.getStatusCode() == 412) {
                    throw objectExists();
                }
                throw new StorageException("COS_COPY_FAILED");
            } catch (StorageException exception) {
                if ("STORAGE_OBJECT_ALREADY_EXISTS".equals(exception.code())) {
                    throw objectExists();
                }
                if ("COS_INVALID_RESPONSE".equals(exception.code())) {
                    throw invalidResponse();
                }
                throw new StorageException("COS_COPY_FAILED");
            } catch (RuntimeException exception) {
                throw new StorageException("COS_COPY_FAILED");
            }
        } finally {
            endOperation();
        }
    }

    @Override
    public void delete(String bucket, String keyValue) {
        beginOperation();
        try {
            requireConfiguredBucket(bucket);
            ObjectKey key = ObjectKey.parse(keyValue);
            try {
                sdk.delete(bucket, key.value());
            } catch (RuntimeException exception) {
                throw new StorageException("COS_DELETE_FAILED");
            }
        } finally {
            endOperation();
        }
    }

    @Override
    public void close() {
        Runnable listener;
        lifecycleLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            while (activeOperations > 0) {
                noActiveOperations.awaitUninterruptibly();
            }
            listener = shutdownSuccess;
        } finally {
            lifecycleLock.unlock();
        }
        try {
            sdk.shutdown();
        } catch (RuntimeException exception) {
            throw new StorageException("COS_SHUTDOWN_FAILED");
        }
        listener.run();
    }

    private void beginOperation() {
        lifecycleLock.lock();
        try {
            if (closed) {
                throw new StorageException("COS_CLIENT_CLOSED");
            }
            activeOperations++;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void endOperation() {
        lifecycleLock.lock();
        try {
            if (activeOperations <= 0) {
                throw new IllegalStateException("COS operation lease is invalid");
            }
            activeOperations--;
            if (activeOperations == 0) {
                noActiveOperations.signalAll();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private static void applyCreateOnlyHeaders(com.qcloud.cos.internal.CosServiceRequest request) {
        request.putCustomRequestHeader(NO_OVERWRITE_HEADER, "true");
        request.putCustomRequestHeader(PRIVATE_ACL_HEADER, "private");
    }

    private void requireConfiguredBucket(String bucket) {
        if (!properties.bucket().equals(bucket)) {
            throw new IllegalArgumentException("Invalid COS bucket");
        }
    }

    private static boolean validHead(ObjectKey key, SdkHeadMetadata head) {
        if (head == null || !validText(head.etag())) {
            return false;
        }
        try {
            StorageObjectContract.validateContentLength(head.contentLength());
            return StorageObjectContract.normalizeContentType(key, head.contentType())
                    .equals(head.contentType());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validRead(
            ObjectKey key,
            Optional<ByteRange> requestedRange,
            long totalLength,
            SdkHeadMetadata head,
            SdkReadResponse response) {
        if (response == null || response.input() == null || !validText(response.etag())) {
            return false;
        }
        try {
            String normalized = StorageObjectContract.normalizeContentType(
                    key, response.contentType());
            if (!normalized.equals(response.contentType())) {
                return false;
            }
            if (requestedRange.isEmpty()) {
                StorageObjectContract.validateContentLength(response.instanceLength());
                return response.responseLength() == response.instanceLength();
            }
            ByteRange range = requestedRange.orElseThrow();
            return response.responseLength() == range.length()
                    && response.instanceLength() == totalLength
                    && head.contentType().equals(response.contentType())
                    && head.etag().equals(response.etag());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void requireOfficialUri(String bucket, ObjectKey key, URI uri) {
        String expectedHost = bucket + ".cos." + properties.region() + ".myqcloud.com";
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || !expectedHost.equalsIgnoreCase(uri.getHost())
                || !expectedHost.equalsIgnoreCase(uri.getRawAuthority())
                || uri.getRawUserInfo() != null
                || uri.getPort() != -1
                || !("/" + key.value()).equals(uri.getRawPath())
                || uri.getRawQuery() == null
                || uri.getRawQuery().isBlank()
                || uri.getRawFragment() != null) {
            throw invalidResponse();
        }
    }

    private static boolean validText(String value) {
        return value != null
                && !value.isBlank()
                && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private static StorageException objectExists() {
        return new StorageException("STORAGE_OBJECT_ALREADY_EXISTS");
    }

    private static StorageException invalidResponse() {
        return new StorageException("COS_INVALID_RESPONSE");
    }

    private static void closeQuietly(SdkReadResponse response) {
        if (response == null) {
            return;
        }
        try {
            response.close();
        } catch (IOException | RuntimeException ignored) {
            // Preserve the fixed read failure.
        }
    }
}

interface QcloudCosSdk {
    String put(PutObjectRequest request);

    SdkHeadMetadata head(String bucket, String key);

    SdkReadResponse get(GetObjectRequest request);

    boolean exists(String bucket, String key);

    String copy(CopyObjectRequest request);

    URL sign(GeneratePresignedUrlRequest request, boolean signHost);

    void delete(String bucket, String key);

    void shutdown();
}

final class SdkHeadMetadata {
    private final long contentLength;
    private final String contentType;
    private final String etag;

    SdkHeadMetadata(long contentLength, String contentType, String etag) {
        this.contentLength = contentLength;
        this.contentType = contentType;
        this.etag = etag;
    }

    long contentLength() {
        return contentLength;
    }

    String contentType() {
        return contentType;
    }

    String etag() {
        return etag;
    }
}

final class SdkReadResponse implements AutoCloseable {
    private final InputStream input;
    private final long responseLength;
    private final long instanceLength;
    private final String contentType;
    private final String etag;
    private final SdkCloseAction closeAction;
    private final AtomicBoolean closed = new AtomicBoolean();

    SdkReadResponse(
            InputStream input,
            long responseLength,
            long instanceLength,
            String contentType,
            String etag,
            SdkCloseAction closeAction) {
        if (closeAction == null) {
            throw new IllegalArgumentException("COS response close action is required");
        }
        this.input = input;
        this.responseLength = responseLength;
        this.instanceLength = instanceLength;
        this.contentType = contentType;
        this.etag = etag;
        this.closeAction = closeAction;
    }

    InputStream input() {
        return input;
    }

    long responseLength() {
        return responseLength;
    }

    long instanceLength() {
        return instanceLength;
    }

    String contentType() {
        return contentType;
    }

    String etag() {
        return etag;
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            closeAction.close();
        }
    }
}

@FunctionalInterface
interface SdkCloseAction {
    void close() throws IOException;
}

final class SdkResponseInputStream extends InputStream {
    private final SdkReadResponse response;

    SdkResponseInputStream(SdkReadResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("COS response is required");
        }
        this.response = response;
    }

    @Override
    public int read() throws IOException {
        try {
            return response.input().read();
        } catch (IOException | RuntimeException exception) {
            throw readFailure();
        }
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        try {
            return response.input().read(destination, offset, length);
        } catch (IOException | RuntimeException exception) {
            throw readFailure();
        }
    }

    @Override
    public long skip(long count) throws IOException {
        try {
            return response.input().skip(count);
        } catch (IOException | RuntimeException exception) {
            throw readFailure();
        }
    }

    @Override
    public int available() throws IOException {
        try {
            return response.input().available();
        } catch (IOException | RuntimeException exception) {
            throw readFailure();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            response.close();
        } catch (IOException | RuntimeException exception) {
            throw readFailure();
        }
    }

    private static IOException readFailure() {
        return new IOException("COS_READ_FAILED");
    }
}

final class OperationLeaseInputStream extends FilterInputStream {
    private final Runnable releaseLease;
    private final AtomicBoolean closed = new AtomicBoolean();

    OperationLeaseInputStream(InputStream input, Runnable releaseLease) {
        super(input);
        if (releaseLease == null) {
            throw new IllegalArgumentException("COS operation lease is required");
        }
        this.releaseLease = releaseLease;
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            super.close();
        } finally {
            releaseLease.run();
        }
    }
}

final class RealQcloudCosSdk implements QcloudCosSdk {
    private final COSClient client;

    RealQcloudCosSdk(COSClient client) {
        this.client = client;
    }

    @Override
    public String put(PutObjectRequest request) {
        PutObjectResult result = client.putObject(request);
        return result == null ? null : result.getETag();
    }

    @Override
    public SdkHeadMetadata head(String bucket, String key) {
        ObjectMetadata metadata = client.getObjectMetadata(bucket, key);
        return metadata == null
                ? null
                : new SdkHeadMetadata(
                        metadata.getContentLength(), metadata.getContentType(), metadata.getETag());
    }

    @Override
    public SdkReadResponse get(GetObjectRequest request) {
        COSObject object = client.getObject(request);
        if (object == null) {
            return null;
        }
        return snapshot(object);
    }

    static SdkReadResponse snapshot(COSObject object) {
        if (object == null) {
            return null;
        }
        try {
            ObjectMetadata metadata = object.getObjectMetadata();
            InputStream input = object.getObjectContent();
            if (metadata == null) {
                return new SdkReadResponse(input, 0, 0, null, null, object::close);
            }
            return new SdkReadResponse(
                    input,
                    metadata.getContentLength(),
                    metadata.getInstanceLength(),
                    metadata.getContentType(),
                    metadata.getETag(),
                    object::close);
        } catch (RuntimeException exception) {
            closeObjectQuietly(object);
            throw new StorageException("COS_INVALID_RESPONSE");
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        return client.doesObjectExist(bucket, key);
    }

    @Override
    public String copy(CopyObjectRequest request) {
        CopyObjectResult result = client.copyObject(request);
        return result == null ? null : result.getETag();
    }

    @Override
    public URL sign(GeneratePresignedUrlRequest request, boolean signHost) {
        return client.generatePresignedUrl(request, signHost);
    }

    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(bucket, key);
    }

    @Override
    public void shutdown() {
        client.shutdown();
    }

    private static void closeObjectQuietly(COSObject object) {
        try {
            object.close();
        } catch (IOException | RuntimeException ignored) {
            // Preserve the SDK extraction failure.
        }
    }
}
