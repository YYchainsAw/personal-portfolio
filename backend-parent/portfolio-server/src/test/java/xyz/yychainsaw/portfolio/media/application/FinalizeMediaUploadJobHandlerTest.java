package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;

class FinalizeMediaUploadJobHandlerTest {
    private static final UUID ASSET_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void handleAcceptsOnlyTheExactCanonicalAssetIdPayload() throws Exception {
        MediaFinalizationService finalization = mock(MediaFinalizationService.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        FinalizeMediaUploadJobHandler handler =
                new FinalizeMediaUploadJobHandler(finalization, assets);

        handler.handle(json.readTree("{\"assetId\":\"" + ASSET_ID + "\"}"));
        verify(finalization).finalizeAsset(ASSET_ID);

        for (JsonNode invalid : List.of(
                json.readTree("null"),
                json.readTree("[]"),
                json.readTree("{}"),
                json.readTree("{\"assetId\":null}"),
                json.readTree("{\"assetId\":1}"),
                json.readTree("{\"assetId\":\"11111111-2222-3333-4444-55555555555A\"}"),
                json.readTree("{\"assetId\":\"" + ASSET_ID + "\",\"key\":\"secret\"}"))) {
            assertThatThrownBy(() -> handler.handle(invalid))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("MEDIA_FINALIZER_PAYLOAD_INVALID")
                    .hasNoCause();
        }
        verifyNoInteractions(assets);
    }

    @Test
    void handlerPropagatesInterruptedExceptionUnchanged() throws Exception {
        MediaFinalizationService finalization = mock(MediaFinalizationService.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        FinalizeMediaUploadJobHandler handler =
                new FinalizeMediaUploadJobHandler(finalization, assets);
        InterruptedException interruption = new InterruptedException("shutdown");
        doThrow(interruption).when(finalization).finalizeAsset(ASSET_ID);

        assertThatThrownBy(() -> handler.handle(
                        json.readTree("{\"assetId\":\"" + ASSET_ID + "\"}")))
                .isSameAs(interruption);
    }

    @Test
    void deadLetterRequiresAmbientTransactionAndPerformsOnlyProcessingCas()
            throws Exception {
        MediaFinalizationService finalization = mock(MediaFinalizationService.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        FinalizeMediaUploadJobHandler handler =
                new FinalizeMediaUploadJobHandler(finalization, assets);
        JsonNode valid = json.readTree("{\"assetId\":\"" + ASSET_ID + "\"}");

        assertThatThrownBy(() -> handler.onDeadLetter(valid, "JOB_HANDLER_FAILED"))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_FINALIZER_DEAD_LETTER_TRANSACTION_REQUIRED")
                .hasNoCause();
        verifyNoInteractions(assets, finalization);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            handler.onDeadLetter(valid, "JOB_HANDLER_FAILED");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(assets).markFailedIfProcessing(ASSET_ID);
        verifyNoInteractions(finalization);
    }

    @Test
    void malformedDeadPayloadIsNoOpAndRepositoryFailurePropagates() throws Exception {
        MediaFinalizationService finalization = mock(MediaFinalizationService.class);
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        FinalizeMediaUploadJobHandler handler =
                new FinalizeMediaUploadJobHandler(finalization, assets);

        handler.onDeadLetter(json.readTree("{}"), "JOB_HANDLER_FAILED");
        handler.onDeadLetter(
                json.readTree("{\"assetId\":\"" + ASSET_ID + "\",\"extra\":1}"),
                "JOB_HANDLER_FAILED");
        verifyNoInteractions(assets, finalization);

        IllegalStateException databaseFailure = new IllegalStateException("database secret");
        when(assets.markFailedIfProcessing(ASSET_ID)).thenThrow(databaseFailure);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> handler.onDeadLetter(
                            json.readTree("{\"assetId\":\"" + ASSET_ID + "\"}"),
                            "JOB_HANDLER_FAILED"))
                    .isSameAs(databaseFailure);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(finalization, never()).finalizeAsset(ASSET_ID);
    }
}
