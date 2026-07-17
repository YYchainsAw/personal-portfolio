package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingKnownRollbackCleanup;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservation;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservationReceipt;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingReservationService;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationAuthorization;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationFence;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.media.storage.StorageException;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.media.storage.StoredObject;
import xyz.yychainsaw.portfolio.system.job.BackgroundJobService;

class MediaIngestLocalJournalTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ACTOR_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID CLEANUP_JOB_ID =
            UUID.fromString("77777777-8888-4999-aaaa-bbbbbbbbbbbb");
    private static final byte[] PDF =
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final String SHA256 = sha256(PDF);
    private static final String MIME_TYPE = "application/pdf";
    private static final StorageLocation LOCAL_LOCATION =
            new StorageLocation(StorageProvider.LOCAL, null, null);

    @TempDir
    Path temporaryDirectory;

    @Test
    void localSuccessKeepsFenceAcrossTempCloseAndExactOuterCompletion() throws Exception {
        Harness harness = harness(TransactionMode.NORMAL, false);

        MediaAssetView view;
        try {
            view = harness.service().ingest(
                    command(new ByteArrayInputStream(PDF)), ACTOR_ID);
        } catch (DomainException failure) {
            throw new AssertionError("local ingest events: " + harness.events(), failure);
        }

        assertThat(view.id()).isEqualTo(ASSET_ID);
        assertThat(harness.events()).containsExactly(
                "reserve",
                "fence.acquire",
                "tx.begin",
                "reservation.lock",
                "authorization.reauthenticate",
                "media.openStream",
                "reserved.put",
                "media.close",
                "tx.body",
                "tx.commit",
                "tx.afterCompletion",
                "fence.close");
        verify(harness.localStorage(), never()).put(
                anyString(), any(), anyLong(), anyString());
        verifyNoInteractions(harness.cleanup());
    }

    @Test
    void capacityFailureClosesInboundOnceAndMakesZeroStorageOrFenceCalls() throws Exception {
        MediaFileInspector inspector = new MediaFileInspector(temporaryDirectory);
        StorageRouter router = mock(StorageRouter.class);
        LocalStorageService localStorage = mock(LocalStorageService.class);
        LocalStagingReservationService reservations =
                mock(LocalStagingReservationService.class);
        LocalPublicationFence fence = mock(LocalPublicationFence.class);
        LocalStagingKnownRollbackCleanup cleanup =
                mock(LocalStagingKnownRollbackCleanup.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        AuditService audit = mock(AuditService.class);
        when(router.defaultWriter()).thenReturn(localStorage);
        when(localStorage.provider()).thenReturn(StorageProvider.LOCAL);
        when(localStorage.location()).thenReturn(LOCAL_LOCATION);
        when(reservations.reserve(ASSET_ID, SHA256, MIME_TYPE))
                .thenThrow(new IllegalStateException("LOCAL_STAGING_CAPACITY_EXHAUSTED"));
        LocalMediaIngestCoordinator coordinator = new LocalMediaIngestCoordinator(
                localStorage, reservations, fence, cleanup);
        MediaIngestService service = new MediaIngestService(
                inspector,
                router,
                assets,
                jobs,
                audit,
                new TransactionTemplate(new RecordingTransactionManager(
                        TransactionMode.NORMAL, new ArrayList<>())),
                () -> ASSET_ID,
                coordinator);
        CloseCountingInputStream inbound = new CloseCountingInputStream(PDF);

        assertUploadFailed(() -> service.ingest(command(inbound), ACTOR_ID));

        assertThat(inbound.closeCalls()).isOne();
        verify(localStorage, never()).put(anyString(), any(), anyLong(), anyString());
        verify(localStorage, never()).putReservedStaging(any(), any(), any(), anyLong());
        verifyNoInteractions(fence, cleanup, assets, jobs, audit);
        assertDirectoryEmpty();
    }

    @Test
    void fenceFailureAfterReservationRetainsSlotAndDoesNotOpenOrPublishMedia() throws Exception {
        Harness harness = harness(TransactionMode.NORMAL, false);
        doAnswer(invocation -> {
                    harness.events().add("fence.acquire");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    throw new StorageException("private fence failure");
                })
                .when(harness.fence()).acquire(any());

        assertUploadFailed(() -> harness.service().ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        assertThat(harness.events()).containsExactly("reserve", "fence.acquire", "media.close");
        verify(harness.media(), never()).openStream();
        verify(harness.localStorage(), never()).put(
                anyString(), any(), anyLong(), anyString());
        verify(harness.localStorage(), never()).putReservedStaging(
                any(), any(), any(), anyLong());
        verifyNoInteractions(harness.cleanup(), harness.assets(), harness.jobs(), harness.audit());
    }

    @Test
    void providerLocalForgedWriterFailsBeforeReservationAndNeverUsesPlainPut()
            throws Exception {
        Harness harness = harness(TransactionMode.NORMAL, false);
        StorageService forged = mock(StorageService.class);
        when(forged.provider()).thenReturn(StorageProvider.LOCAL);
        when(forged.location()).thenReturn(LOCAL_LOCATION);
        when(harness.router().defaultWriter()).thenReturn(forged);

        assertUploadFailed(() -> harness.service().ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        verifyNoInteractions(harness.reservations(), harness.fence(), harness.cleanup());
        verify(forged, never()).put(anyString(), any(), anyLong(), anyString());
        verify(harness.localStorage(), never()).putReservedStaging(
                any(), any(), any(), anyLong());
        verifyNoInteractions(harness.assets(), harness.jobs(), harness.audit());
    }

    @Test
    void mediaCloseFailurePerformsKnownRollbackCleanupBeforeFenceRelease() throws Exception {
        Harness harness = harness(TransactionMode.NORMAL, false);
        AtomicBoolean firstClose = new AtomicBoolean(true);
        doAnswer(invocation -> {
                    harness.events().add("media.close");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isEqualTo(firstClose.getAndSet(false));
                    throw new IOException("private temp delete failure");
                })
                .when(harness.media()).close();
        when(harness.cleanup().cleanupKnownRollback(any(), any()))
                .thenAnswer(invocation -> {
                    harness.events().add("known.cleanup");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return true;
                });

        assertUploadFailed(() -> harness.service().ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        assertThat(harness.events()).startsWith(
                "reserve",
                "fence.acquire",
                "tx.begin",
                "reservation.lock",
                "authorization.reauthenticate",
                "media.openStream",
                "reserved.put",
                "media.close",
                "tx.rollback",
                "tx.afterCompletion",
                "known.cleanup",
                "fence.close");
        verify(harness.cleanup()).cleanupKnownRollback(
                harness.authorization(), harness.reservation());
        verifyNoInteractions(harness.assets(), harness.jobs(), harness.audit());
    }

    @Test
    void transactionBeginFailurePerformsKnownRollbackCleanupBeforeFenceRelease()
            throws Exception {
        Harness harness = harness(TransactionMode.BEGIN_FAIL, false);
        when(harness.cleanup().cleanupKnownRollback(any(), any()))
                .thenAnswer(invocation -> {
                    harness.events().add("known.cleanup");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return true;
                });

        assertUploadFailed(() -> harness.service().ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        assertThat(harness.events()).containsSubsequence(
                "tx.begin", "known.cleanup", "fence.close");
        verify(harness.cleanup()).cleanupKnownRollback(
                harness.authorization(), harness.reservation());
        verifyNoInteractions(harness.assets(), harness.jobs(), harness.audit());
    }

    @Test
    void exactReservationRowLockFailureHappensBeforePublicationOrDatabaseWrites()
            throws Exception {
        Harness harness = harness(TransactionMode.NORMAL, false);
        doAnswer(invocation -> {
                    harness.events().add("reservation.lock");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    throw new IllegalStateException("LOCAL_STAGING_RESERVATION_INVALID");
                })
                .when(harness.reservations())
                .lockCurrentForOuterTransaction(harness.reservation());
        when(harness.cleanup().cleanupKnownRollback(any(), any()))
                .thenAnswer(invocation -> {
                    harness.events().add("known.cleanup");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return false;
                });

        assertUploadFailed(() -> harness.service().ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        assertThat(harness.events()).containsSubsequence(
                "tx.begin", "reservation.lock", "tx.rollback", "known.cleanup", "fence.close");
        verify(harness.media(), never()).openStream();
        verify(harness.localStorage(), never()).putReservedStaging(
                any(), any(), any(), anyLong());
        verifyNoInteractions(harness.assets(), harness.jobs(), harness.audit());
    }

    @Test
    void knownRollbackCleanupFailureDoesNotMaskTheFixedUploadFailure()
            throws Exception {
        Harness harness = harness(TransactionMode.BEGIN_FAIL, false);
        when(harness.cleanup().cleanupKnownRollback(any(), any()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    throw new IllegalStateException("private cleanup failure");
                });

        assertUploadFailed(() -> harness.service().ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        verify(harness.cleanup()).cleanupKnownRollback(
                harness.authorization(), harness.reservation());
        assertThat(harness.events()).containsSubsequence("tx.begin", "fence.close");
    }

    @Test
    void knownOuterRollbackCleansOnlyAfterAfterCompletionAndBeforeFenceRelease()
            throws Exception {
        Harness harness = harness(TransactionMode.NORMAL, true);
        when(harness.cleanup().cleanupKnownRollback(any(), any()))
                .thenAnswer(invocation -> {
                    harness.events().add("known.cleanup");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return true;
                });

        assertUploadFailed(() -> harness.service().ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        assertThat(harness.events()).containsSubsequence(
                "tx.rollback",
                "tx.afterCompletion",
                "known.cleanup",
                "fence.close");
        verify(harness.cleanup()).cleanupKnownRollback(
                harness.authorization(), harness.reservation());
    }

    @Test
    void unknownCommitOutcomeRetainsReservationAndStillReleasesFence() throws Exception {
        Harness harness = harness(TransactionMode.COMMIT_FAIL, false);

        assertUploadFailed(() -> harness.service().ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        verifyNoInteractions(harness.cleanup());
        assertThat(harness.events()).containsSubsequence(
                "tx.body", "tx.commit", "fence.close");
    }

    @Test
    void storedObjectMismatchRetainsReservationAndNeverRunsKnownCleanup() throws Exception {
        Harness harness = harness(TransactionMode.NORMAL, false);
        doAnswer(invocation -> {
                    harness.events().add("reserved.put");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    close(invocation.getArgument(2));
                    return new StoredObject(
                            StorageProvider.LOCAL,
                            null,
                            null,
                            "staging/other/" + SHA256 + ".pdf",
                            PDF.length,
                            MIME_TYPE,
                            "etag");
                })
                .when(harness.localStorage())
                .putReservedStaging(any(), any(), any(), anyLong());

        assertUploadFailed(() -> harness.service().ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        verifyNoInteractions(harness.cleanup(), harness.assets(), harness.jobs(), harness.audit());
        assertThat(harness.events()).containsSubsequence("reserved.put", "fence.close");
    }

    @Test
    void reservedPutFailureHasUnknownOutcomeAndRetainsReservation() throws Exception {
        Harness harness = harness(TransactionMode.NORMAL, false);
        doAnswer(invocation -> {
                    harness.events().add("reserved.put");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    close(invocation.getArgument(2));
                    throw new StorageException("private unknown publication outcome");
                })
                .when(harness.localStorage())
                .putReservedStaging(any(), any(), any(), anyLong());

        assertUploadFailed(() -> harness.service().ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        verifyNoInteractions(harness.cleanup(), harness.assets(), harness.jobs(), harness.audit());
        assertThat(harness.events()).containsSubsequence(
                "reserved.put", "tx.rollback", "fence.close");
    }

    @Test
    void fenceCloseFailureReturnsOnlyTheFixedUploadFailureAndRetainsReservation()
            throws Exception {
        Harness harness = harness(TransactionMode.NORMAL, false);
        doAnswer(invocation -> {
                    harness.events().add("fence.close");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    throw new StorageException("private release failure");
                })
                .when(harness.authorization()).close();

        assertUploadFailed(() -> harness.service().ingest(
                command(new ByteArrayInputStream(PDF)), ACTOR_ID));

        verifyNoInteractions(harness.cleanup());
        verify(harness.assets()).insertProcessing(any());
        verify(harness.jobs()).enqueue(anyString(), anyString(), anyMap());
        verify(harness.audit()).record(any());
    }

    @Test
    void cosWriterUsesTheExistingProviderNeutralPathWithoutLocalJournalInteractions()
            throws Exception {
        MediaFileInspector inspector = mock(MediaFileInspector.class);
        InspectedMedia media = mock(InspectedMedia.class);
        StorageRouter router = mock(StorageRouter.class);
        StorageService cos = mock(StorageService.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        AuditService audit = mock(AuditService.class);
        LocalMediaIngestCoordinator localCoordinator = mock(LocalMediaIngestCoordinator.class);
        StorageLocation cosLocation = new StorageLocation(
                StorageProvider.TENCENT_COS,
                "portfolio-1234567890",
                "ap-guangzhou");
        String stagingKey = MediaObjectKeys.stagingKey(ASSET_ID, SHA256, MIME_TYPE);
        when(inspector.inspect(any())).thenReturn(media);
        when(media.mimeType()).thenReturn(MIME_TYPE);
        when(media.sha256()).thenReturn(SHA256);
        when(media.byteSize()).thenReturn((long) PDF.length);
        when(media.originalFilename()).thenReturn("document.pdf");
        when(media.width()).thenReturn(null);
        when(media.height()).thenReturn(null);
        when(media.openStream()).thenReturn(new ByteArrayInputStream(PDF));
        when(router.defaultWriter()).thenReturn(cos);
        when(cos.provider()).thenReturn(StorageProvider.TENCENT_COS);
        when(cos.location()).thenReturn(cosLocation);
        when(cos.put(eq(stagingKey), any(), anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    close(invocation.getArgument(1));
                    return new StoredObject(
                            StorageProvider.TENCENT_COS,
                            cosLocation.bucket(),
                            cosLocation.region(),
                            stagingKey,
                            PDF.length,
                            MIME_TYPE,
                            "etag");
                });
        when(assets.insertProcessing(any())).thenAnswer(invocation ->
                complete(invocation.getArgument(0)));
        when(assets.toView(any())).thenAnswer(invocation ->
                ((MediaAssetRecord) invocation.getArgument(0)).toView());
        when(jobs.enqueue(anyString(), anyString(), anyMap())).thenReturn(UUID.randomUUID());
        MediaIngestService service = new MediaIngestService(
                inspector,
                router,
                assets,
                jobs,
                audit,
                new TransactionTemplate(new RecordingTransactionManager(
                        TransactionMode.NORMAL, new ArrayList<>())),
                () -> ASSET_ID,
                localCoordinator);

        MediaAssetView view = service.ingest(command(new ByteArrayInputStream(PDF)), ACTOR_ID);

        assertThat(view.id()).isEqualTo(ASSET_ID);
        verify(cos).put(eq(stagingKey), any(), anyLong(), anyString());
        verifyNoInteractions(localCoordinator);
    }

    @Test
    void productionTransactionTemplateHasASeparateStrictWholeSecondTimeout() {
        RecordingTransactionManager manager = new RecordingTransactionManager(
                TransactionMode.NORMAL, new ArrayList<>());

        TransactionTemplate oneSecond = MediaIngestService.newTransactions(manager, "PT1S");
        TransactionTemplate defaultTimeout = MediaIngestService.newTransactions(manager, "PT30S");
        TransactionTemplate maximum = MediaIngestService.newTransactions(manager, "PT120S");

        assertThat(oneSecond.getTimeout()).isEqualTo(1);
        assertThat(defaultTimeout.getTimeout()).isEqualTo(30);
        assertThat(maximum.getTimeout()).isEqualTo(120);
        assertThat(defaultTimeout.getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(defaultTimeout.getName()).isEqualTo("media-ingest");
    }

    @Test
    void productionTransactionTimeoutRejectsNonIsoFractionalAndOutOfRangeValues() {
        RecordingTransactionManager manager = new RecordingTransactionManager(
                TransactionMode.NORMAL, new ArrayList<>());

        for (String invalid : new String[] {
            "", "30", " PT30S", "PT30S ", "PT0S", "PT0.5S", "PT121S", "-PT1S"
        }) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MediaIngestService.newTransactions(manager, invalid))
                    .withMessage("media ingest transaction timeout is invalid")
                    .withNoCause();
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MediaIngestService.newTransactions(manager, null))
                .withMessage("media ingest transaction timeout is invalid")
                .withNoCause();
    }

    @Test
    void nonServletMaintenanceContextDoesNotCreateIngestOrLocalCoordinator() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        MediaIngestService.class, LocalMediaIngestCoordinator.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MediaIngestService.class)
                        .doesNotHaveBean(LocalMediaIngestCoordinator.class));
    }

    private Harness harness(TransactionMode mode, boolean rollbackAfterCallback)
            throws Exception {
        List<String> events = new ArrayList<>();
        MediaFileInspector inspector = mock(MediaFileInspector.class);
        InspectedMedia media = mock(InspectedMedia.class);
        StorageRouter router = mock(StorageRouter.class);
        LocalStorageService localStorage = mock(LocalStorageService.class);
        LocalStagingReservationService reservations =
                mock(LocalStagingReservationService.class);
        LocalPublicationFence fence = mock(LocalPublicationFence.class);
        LocalStagingKnownRollbackCleanup cleanup =
                mock(LocalStagingKnownRollbackCleanup.class);
        LocalPublicationAuthorization authorization =
                mock(LocalPublicationAuthorization.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        AuditService audit = mock(AuditService.class);
        LocalStagingReservation reservation = reservation();
        LocalStagingReservationReceipt receipt =
                mock(LocalStagingReservationReceipt.class);
        String stagingKey = MediaObjectKeys.stagingKey(ASSET_ID, SHA256, MIME_TYPE);

        when(inspector.inspect(any())).thenReturn(media);
        when(media.mimeType()).thenReturn(MIME_TYPE);
        when(media.sha256()).thenReturn(SHA256);
        when(media.byteSize()).thenReturn((long) PDF.length);
        when(media.originalFilename()).thenReturn("document.pdf");
        when(media.width()).thenReturn(null);
        when(media.height()).thenReturn(null);
        when(media.openStream()).thenAnswer(invocation -> {
            events.add("media.openStream");
            return new ByteArrayInputStream(PDF);
        });
        doAnswer(invocation -> {
                    events.add("media.close");
                    return null;
                })
                .when(media).close();
        when(router.defaultWriter()).thenReturn(localStorage);
        when(localStorage.provider()).thenReturn(StorageProvider.LOCAL);
        when(localStorage.location()).thenReturn(LOCAL_LOCATION);
        when(receipt.reservation()).thenReturn(reservation);
        when(reservations.reserve(ASSET_ID, SHA256, MIME_TYPE)).thenAnswer(invocation -> {
            events.add("reserve");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return receipt;
        });
        when(reservations.lockCurrentForOuterTransaction(reservation))
                .thenAnswer(invocation -> {
                    events.add("reservation.lock");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    return reservation;
                });
        when(fence.acquire(any())).thenAnswer(invocation -> {
            events.add("fence.acquire");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return authorization;
        });
        doAnswer(invocation -> {
                    events.add("authorization.reauthenticate");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    return null;
                })
                .when(authorization).reauthenticate();
        when(localStorage.putReservedStaging(any(), any(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    events.add("reserved.put");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    close(invocation.getArgument(2));
                    return new StoredObject(
                            StorageProvider.LOCAL,
                            null,
                            null,
                            stagingKey,
                            PDF.length,
                            MIME_TYPE,
                            "etag");
                });
        doAnswer(invocation -> {
                    events.add("fence.close");
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return null;
                })
                .when(authorization).close();
        when(assets.insertProcessing(any())).thenAnswer(invocation -> {
            events.add("tx.body");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            return complete(invocation.getArgument(0));
        });
        when(assets.toView(any())).thenAnswer(invocation ->
                ((MediaAssetRecord) invocation.getArgument(0)).toView());
        when(jobs.enqueue(anyString(), anyString(), anyMap())).thenReturn(UUID.randomUUID());

        RecordingTransactionManager manager = new RecordingTransactionManager(mode, events);
        TransactionTemplate base = rollbackAfterCallback
                ? new RollbackAfterCallbackTemplate(manager)
                : new TransactionTemplate(manager);
        TransactionTemplate transactions = new CompletionRecordingTemplate(base, events);
        LocalMediaIngestCoordinator coordinator = new LocalMediaIngestCoordinator(
                localStorage, reservations, fence, cleanup);
        MediaIngestService service = new MediaIngestService(
                inspector,
                router,
                assets,
                jobs,
                audit,
                transactions,
                () -> ASSET_ID,
                coordinator);
        return new Harness(
                service,
                inspector,
                media,
                router,
                localStorage,
                reservations,
                fence,
                cleanup,
                authorization,
                reservation,
                assets,
                jobs,
                audit,
                events);
    }

    private static MediaAssetRecord complete(MediaAssetRecord.Insert insert) {
        OffsetDateTime now = OffsetDateTime.of(
                2026, 7, 17, 0, 0, 0, 0, ZoneOffset.UTC);
        return new MediaAssetRecord(
                insert.id(),
                insert.provider(),
                insert.bucket(),
                insert.region(),
                insert.objectKey(),
                insert.originalFilename(),
                insert.mimeType(),
                insert.byteSize(),
                insert.width(),
                insert.height(),
                insert.sha256(),
                MediaStatus.PROCESSING,
                null,
                0,
                now.toInstant(),
                now.toInstant());
    }

    private static LocalStagingReservation reservation() {
        OffsetDateTime reservedAt = OffsetDateTime.of(
                2026, 7, 17, 0, 0, 0, 0, ZoneOffset.UTC);
        return new LocalStagingReservation(
                ASSET_ID,
                SHA256,
                MIME_TYPE,
                0,
                CLEANUP_JOB_ID,
                reservedAt,
                reservedAt.plusHours(24));
    }

    private static UploadMediaCommand command(InputStream input) {
        return new UploadMediaCommand(
                "C:\\request\\document.exe", MIME_TYPE, PDF.length, input);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void close(InputStream input) {
        try {
            input.close();
        } catch (IOException failure) {
            throw new IllegalStateException("test input close failed", failure);
        }
    }

    private void assertDirectoryEmpty() throws IOException {
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).isEmpty();
        }
    }

    private static void assertUploadFailed(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isExactlyInstanceOf(DomainException.class)
                .satisfies(failure -> {
                    DomainException domain = (DomainException) failure;
                    assertThat(domain.code()).isEqualTo("MEDIA_UPLOAD_FAILED");
                    assertThat(domain.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(domain.fieldErrors()).isEmpty();
                    assertThat(domain).hasNoCause();
                    assertThat(domain.getSuppressed()).isEmpty();
                    assertThat(domain.getMessage()).isEqualTo("MEDIA_UPLOAD_FAILED");
                });
    }

    private record Harness(
            MediaIngestService service,
            MediaFileInspector inspector,
            InspectedMedia media,
            StorageRouter router,
            LocalStorageService localStorage,
            LocalStagingReservationService reservations,
            LocalPublicationFence fence,
            LocalStagingKnownRollbackCleanup cleanup,
            LocalPublicationAuthorization authorization,
            LocalStagingReservation reservation,
            MediaAssetRepository assets,
            BackgroundJobService jobs,
            AuditService audit,
            List<String> events) {}

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private enum TransactionMode {
        NORMAL,
        BEGIN_FAIL,
        COMMIT_FAIL
    }

    private static final class RecordingTransactionManager
            extends AbstractPlatformTransactionManager {
        private final TransactionMode mode;
        private final List<String> events;

        private RecordingTransactionManager(TransactionMode mode, List<String> events) {
            this.mode = mode;
            this.events = events;
            setRollbackOnCommitFailure(false);
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("tx.begin");
            if (mode == TransactionMode.BEGIN_FAIL) {
                throw new CannotCreateTransactionException("private begin failure");
            }
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("tx.commit");
            if (mode == TransactionMode.COMMIT_FAIL) {
                throw new TransactionSystemException("private commit failure");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("tx.rollback");
        }
    }

    private static final class RollbackAfterCallbackTemplate extends TransactionTemplate {
        private RollbackAfterCallbackTemplate(RecordingTransactionManager manager) {
            super(manager);
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            return super.execute(status -> {
                T result = action.doInTransaction(status);
                status.setRollbackOnly();
                return result;
            });
        }
    }

    private static final class CompletionRecordingTemplate extends TransactionTemplate {
        private final TransactionTemplate delegate;
        private final List<String> events;

        private CompletionRecordingTemplate(
                TransactionTemplate delegate, List<String> events) {
            this.delegate = delegate;
            this.events = events;
            setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            AtomicBoolean callbackEntered = new AtomicBoolean();
            try {
                return delegate.execute(status -> {
                    callbackEntered.set(true);
                    return action.doInTransaction(status);
                });
            } finally {
                if (callbackEntered.get()) {
                    events.add("tx.afterCompletion");
                }
            }
        }
    }

    private static final class CloseCountingInputStream extends FilterInputStream {
        private int closeCalls;

        private CloseCountingInputStream(byte[] bytes) {
            super(new ByteArrayInputStream(bytes));
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            super.close();
        }

        private int closeCalls() {
            return closeCalls;
        }
    }
}
