package xyz.yychainsaw.portfolio.media.storage;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingPolicyProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    LocalStorageProperties.class,
    LocalStagingPolicyProperties.class
})
public class StorageConfiguration {

    @Bean(destroyMethod = "close")
    LocalStorageService localStorageService(
            LocalStorageProperties properties,
            LocalStagingPolicyProperties stagingPolicy) {
        return new LocalStorageService(
                properties, stagingPolicy.scanEntryCeiling());
    }

    @Bean
    StorageDefaults storageDefaults(
            @Value("${portfolio.storage.default-provider:LOCAL}")
            StorageProvider defaultProvider) {
        return new StorageDefaults(defaultProvider);
    }

    @Bean
    StorageRouter storageRouter(
            Collection<StorageService> services, StorageDefaults defaults) {
        return new DefaultStorageRouter(services, defaults);
    }

    @Bean
    @Profile("prod")
    TencentCosProperties tencentCosProperties(
            @Value("${COS_REGION}") String region,
            @Value("${COS_BUCKET}") String bucket,
            @Value("${COS_SECRET_ID}") String secretId,
            @Value("${COS_SECRET_KEY}") String secretKey,
            @Value("${COS_SESSION_TOKEN:}") String sessionToken) {
        return new TencentCosProperties(region, bucket, secretId, secretKey, sessionToken);
    }

    @Bean(destroyMethod = "close")
    @Profile("prod")
    @ConditionalOnMissingBean(CosSdkLogSilencer.class)
    CosSdkLogSilencer cosSdkLogSilencer() {
        LoggingSystem loggingSystem = LoggingSystem.get(
                StorageConfiguration.class.getClassLoader());
        return new CosSdkLogSilencer(loggingSystem, false);
    }

    @Bean
    @Profile("prod")
    @ConditionalOnMissingBean(CosAdapterFactory.class)
    CosAdapterFactory cosAdapterFactory() {
        return QcloudCosClientAdapter::create;
    }

    @Bean(destroyMethod = "close")
    @Profile("prod")
    QcloudCosClientAdapter tencentCosClient(
            TencentCosProperties properties,
            CosSdkLogSilencer logSilencer,
            CosAdapterFactory adapterFactory,
            @Qualifier("cosStagingRoot") Path stagingRoot) {
        if (stagingRoot == null) {
            throw new StorageException("COS_WRITE_FAILED");
        }
        logSilencer.silence();
        logSilencer.blockRestoreUntilClientStops();
        QcloudCosClientAdapter adapter = adapterFactory.create(properties);
        adapter.onShutdownSuccess(logSilencer::clientStopped);
        return adapter;
    }

    @Bean(name = "cosStagingRoot")
    @Profile("prod")
    Path cosStagingRootPath(
            @Value("${portfolio.storage.cos.staging-root}") String configuredRoot,
            LocalStorageProperties localProperties) {
        return cosStagingRoot(configuredRoot, localProperties);
    }

    @Bean(destroyMethod = "close")
    @Profile("prod")
    TencentCosStorageService tencentCosStorageService(
            QcloudCosClientAdapter client,
            TencentCosProperties properties,
            Clock clock,
            @Qualifier("cosStagingRoot") Path stagingRoot,
            @Value("${portfolio.media.staging-cleanup.max-scan-entries:100000}")
            int maximumScanEntries) {
        return new TencentCosStorageService(
                client,
                properties,
                clock,
                stagingRoot,
                TencentCosStorageService.StagingObserver.NOOP,
                maximumScanEntries);
    }

    static Path cosStagingRoot(
            String configuredRoot, LocalStorageProperties properties) {
        if (configuredRoot == null
                || configuredRoot.isBlank()
                || configuredRoot.contains("${")
                || configuredRoot.contains("}")
                || properties == null) {
            throw new StorageException("COS_WRITE_FAILED");
        }
        Path localRoot;
        Path stagingRoot;
        try {
            localRoot = properties.root().toAbsolutePath().normalize();
            Path configuredPath = Path.of(configuredRoot);
            if (!configuredPath.isAbsolute()) {
                throw new StorageException("COS_WRITE_FAILED");
            }
            stagingRoot = configuredPath.normalize();
        } catch (StorageException fixedFailure) {
            throw fixedFailure;
        } catch (RuntimeException invalidPath) {
            throw new StorageException("COS_WRITE_FAILED");
        }
        if (stagingRoot.equals(localRoot)
                || stagingRoot.startsWith(localRoot)
                || localRoot.startsWith(stagingRoot)) {
            throw new StorageException("COS_WRITE_FAILED");
        }
        return stagingRoot;
    }
}

@FunctionalInterface
interface CosAdapterFactory {
    QcloudCosClientAdapter create(TencentCosProperties properties);
}

@FunctionalInterface
interface CosSdkLogLevelSetter {
    void setLogLevel(String loggerName, LogLevel level);
}

final class CosSdkLogSilencer implements AutoCloseable {
    private static final List<String> SDK_LOGGERS = List.of(
            "com.qcloud.cos",
            "com.qcloud.cos.http.DefaultCosHttpClient",
            "com.qcloud.cos.COSClient",
            "org.apache.http",
            "org.apache.http.headers",
            "org.apache.http.wire",
            "org.apache.http.impl.execchain.MainClientExec",
            "org.apache.http.impl.execchain.ProtocolExec");

    private static final Object PRODUCTION_MONITOR = new Object();
    private static final Map<String, LogLevel> ORIGINAL_LEVELS = new LinkedHashMap<>();
    private static LoggingSystem restoringLoggingSystem;
    private static int activeProductionSilencers;
    private static boolean productionGroupRestorable = true;

    private final LoggingSystem loggingSystem;
    private final CosSdkLogLevelSetter testLogLevelSetter;
    private final boolean restoreOriginalLevelsOnClose;
    private boolean active;
    private boolean restoreBlockedByClient;

    CosSdkLogSilencer(LoggingSystem loggingSystem) {
        this(loggingSystem, true);
    }

    CosSdkLogSilencer(LoggingSystem loggingSystem, boolean restoreOriginalLevelsOnClose) {
        if (loggingSystem == null) {
            throw new IllegalArgumentException("COS SDK logging system is required");
        }
        this.loggingSystem = loggingSystem;
        this.testLogLevelSetter = null;
        this.restoreOriginalLevelsOnClose = restoreOriginalLevelsOnClose;
    }

    CosSdkLogSilencer(CosSdkLogLevelSetter logLevelSetter) {
        if (logLevelSetter == null) {
            throw new IllegalArgumentException("COS SDK log-level setter is required");
        }
        this.loggingSystem = null;
        this.testLogLevelSetter = logLevelSetter;
        this.restoreOriginalLevelsOnClose = false;
    }

    void silence() {
        if (loggingSystem == null) {
            silenceTestLogger();
            return;
        }

        synchronized (PRODUCTION_MONITOR) {
            if (active) {
                return;
            }

            boolean firstSilencer = activeProductionSilencers == 0;
            if (firstSilencer) {
                captureOriginalLevels(loggingSystem);
            }

            try {
                setAllLevels(loggingSystem::setLogLevel, LogLevel.OFF);
            } catch (RuntimeException failure) {
                if (firstSilencer) {
                    RuntimeException restorationFailure = restoreOriginalLevels();
                    clearProductionState();
                    if (restorationFailure != null) {
                        failure.addSuppressed(restorationFailure);
                    }
                } else {
                    restoreOffLevels(failure);
                }
                throw failure;
            }

            activeProductionSilencers++;
            productionGroupRestorable = firstSilencer
                    ? restoreOriginalLevelsOnClose
                    : productionGroupRestorable && restoreOriginalLevelsOnClose;
            active = true;
        }
    }

    private synchronized void silenceTestLogger() {
        if (active) {
            return;
        }
        setAllLevels(testLogLevelSetter, LogLevel.OFF);
        active = true;
    }

    void blockRestoreUntilClientStops() {
        if (loggingSystem == null) {
            synchronized (this) {
                requireActive();
                restoreBlockedByClient = true;
            }
            return;
        }
        synchronized (PRODUCTION_MONITOR) {
            requireActive();
            restoreBlockedByClient = true;
        }
    }

    void clientStopped() {
        if (loggingSystem == null) {
            synchronized (this) {
                restoreBlockedByClient = false;
            }
            return;
        }
        synchronized (PRODUCTION_MONITOR) {
            restoreBlockedByClient = false;
        }
    }

    @Override
    public void close() {
        if (loggingSystem == null) {
            synchronized (this) {
                if (restoreBlockedByClient) {
                    return;
                }
                active = false;
            }
            return;
        }

        synchronized (PRODUCTION_MONITOR) {
            if (!active) {
                return;
            }
            if (restoreBlockedByClient) {
                return;
            }
            active = false;
            activeProductionSilencers--;
            if (activeProductionSilencers > 0) {
                return;
            }

            RuntimeException failure = productionGroupRestorable
                    ? restoreOriginalLevels()
                    : null;
            clearProductionState();
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static void captureOriginalLevels(LoggingSystem system) {
        ORIGINAL_LEVELS.clear();
        restoringLoggingSystem = system;
        for (String loggerName : SDK_LOGGERS) {
            var configuration = system.getLoggerConfiguration(loggerName);
            ORIGINAL_LEVELS.put(
                    loggerName,
                    configuration == null ? null : configuration.getConfiguredLevel());
        }
    }

    private static void setAllLevels(CosSdkLogLevelSetter setter, LogLevel level) {
        for (String loggerName : SDK_LOGGERS) {
            setter.setLogLevel(loggerName, level);
        }
    }

    private static void restoreOffLevels(RuntimeException originalFailure) {
        for (String loggerName : SDK_LOGGERS) {
            try {
                restoringLoggingSystem.setLogLevel(loggerName, LogLevel.OFF);
            } catch (RuntimeException restorationFailure) {
                originalFailure.addSuppressed(restorationFailure);
            }
        }
    }

    private static RuntimeException restoreOriginalLevels() {
        RuntimeException firstFailure = null;
        if (restoringLoggingSystem == null) {
            return null;
        }
        for (Map.Entry<String, LogLevel> entry : ORIGINAL_LEVELS.entrySet()) {
            try {
                restoringLoggingSystem.setLogLevel(entry.getKey(), entry.getValue());
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        return firstFailure;
    }

    private static void clearProductionState() {
        activeProductionSilencers = 0;
        productionGroupRestorable = true;
        ORIGINAL_LEVELS.clear();
        restoringLoggingSystem = null;
    }

    private void requireActive() {
        if (!active) {
            throw new IllegalStateException("COS SDK log guard is not active");
        }
    }
}
