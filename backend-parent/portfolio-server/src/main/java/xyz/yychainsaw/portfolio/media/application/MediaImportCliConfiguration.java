package xyz.yychainsaw.portfolio.media.application;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingPolicyProperties;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservationRepository;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservationService;
import xyz.yychainsaw.portfolio.media.staging.TransactionalLocalStagingObjectCleanup;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationFence;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;
import xyz.yychainsaw.portfolio.system.job.ScheduledJobInserter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnNotWebApplication
@ConditionalOnProperty(name = "portfolio.cli.command", havingValue = "import")
class MediaImportCliConfiguration {
    @Bean
    LocalPublicationFence mediaImportLocalPublicationFence(
            DataSource dataSource,
            LocalStorageService localStorage,
            @Value("${portfolio.media.local-publication.max-concurrency:2}")
            String maximumConcurrency,
            @Value("${portfolio.media.local-publication.connection-headroom:2}")
            String connectionHeadroom,
            @Value("${portfolio.media.local-publication.acquire-timeout:PT10S}")
            String acquireTimeout,
            @Value("${portfolio.media.local-publication.operation-timeout:PT2M}")
            String operationTimeout) {
        return new LocalPublicationFence(
                dataSource,
                localStorage,
                maximumConcurrency,
                connectionHeadroom,
                acquireTimeout,
                operationTimeout);
    }

    @Bean
    LocalStagingReservationService mediaImportLocalStagingReservationService(
            LocalStagingReservationRepository repository,
            ScheduledJobInserter jobs,
            LocalStorageService storage,
            LocalStagingPolicyProperties properties,
            PlatformTransactionManager transactionManager) {
        return new LocalStagingReservationService(
                repository, jobs, storage, properties, transactionManager);
    }

    @Bean
    TransactionalLocalStagingObjectCleanup mediaImportLocalStagingObjectCleanup(
            LocalStorageService storage,
            LocalPublicationFence fence,
            LocalStagingReservationRepository reservations,
            MediaAssetRepository assets,
            PlatformTransactionManager transactionManager) {
        return new TransactionalLocalStagingObjectCleanup(
                storage, fence, reservations, assets, transactionManager);
    }

    @Bean
    LocalMediaIngestCoordinator mediaImportLocalIngestCoordinator(
            LocalStorageService localStorage,
            LocalStagingReservationService reservations,
            LocalPublicationFence publicationFence,
            TransactionalLocalStagingObjectCleanup rollbackCleanup) {
        return new LocalMediaIngestCoordinator(
                localStorage, reservations, publicationFence, rollbackCleanup);
    }

    @Bean
    MediaImportService mediaImportService(
            MediaFileInspector inspector,
            StorageRouter storageRouter,
            MediaAssetRepository assets,
            MediaVariantRepository variants,
            MediaTranslationRepository translations,
            BackgroundJobService jobs,
            LocalMediaIngestCoordinator localIngest,
            PlatformTransactionManager transactionManager) {
        return new DefaultMediaImportService(
                inspector,
                storageRouter,
                assets,
                variants,
                translations,
                jobs,
                localIngest,
                transactionManager);
    }
}
