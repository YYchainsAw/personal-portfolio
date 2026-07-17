package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import xyz.yychainsaw.portfolio.api.admin.media.MediaPageView;
import xyz.yychainsaw.portfolio.api.admin.media.MediaTranslationInput;
import xyz.yychainsaw.portfolio.audit.AuditCommand;
import xyz.yychainsaw.portfolio.audit.AuditService;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.media.domain.MediaStatus;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetPage;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaTranslationRepository;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRecord;
import xyz.yychainsaw.portfolio.media.persistence.MediaVariantRepository;

@ExtendWith(MockitoExtension.class)
class MediaManagementServiceTest {
    private static final UUID ASSET_ID = UUID.fromString(
            "23000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN_ID = UUID.fromString(
            "23000000-0000-4000-8000-000000000002");
    private static final UUID REFERENCE_ID = UUID.fromString(
            "23000000-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
    private static final String SHA256 = "d".repeat(64);

    @Mock
    private CurrentAdminProvider currentAdmin;

    @Mock
    private MediaAssetRepository assets;

    @Mock
    private MediaVariantRepository variants;

    @Mock
    private MediaTranslationRepository translations;

    @Mock
    private MediaReferenceResolver references;

    @Mock
    private MediaChangeListener firstListener;

    @Mock
    private MediaChangeListener secondListener;

    @Mock
    private AuditService audit;

    private final TransactionOperations transactions = new TransactionOperations() {
        @Override
        public <T> T execute(
                org.springframework.transaction.support.TransactionCallback<T> callback) {
            return callback.doInTransaction(new SimpleTransactionStatus());
        }
    };

    private MediaManagementService service;

    @BeforeEach
    void setUp() {
        service = new MediaManagementService(
                currentAdmin,
                assets,
                variants,
                translations,
                references,
                List.of(firstListener, secondListener),
                audit,
                transactions);
    }

    @Test
    void listValidatesBoundsAndReturnsStableDetailedPage() {
        MediaAssetRecord ready = asset(MediaStatus.READY, 1, null);
        when(assets.findPage(0, 24, Optional.of(MediaStatus.READY)))
                .thenReturn(new MediaAssetPage(List.of(ready), 1));
        when(translations.findByAssetId(ASSET_ID)).thenReturn(List.of(
                new MediaTranslationRecord(
                        ASSET_ID, "zh-CN", "游戏截图", "", "易嘉轩", null),
                new MediaTranslationRecord(
                        ASSET_ID, "en", "Gameplay", "", "Yi Jiaxuan", null)));
        when(variants.findByAssetId(ASSET_ID)).thenReturn(List.of(variant()));

        MediaPageView page = service.list(0, 24, "READY");

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(24);
        assertThat(page.totalItems()).isOne();
        assertThat(page.totalPages()).isOne();
        assertThat(page.items()).singleElement().satisfies(view -> {
            assertThat(view.translations()).hasSize(2);
            assertThat(view.variants()).singleElement()
                    .satisfies(value -> assertThat(value.name()).isEqualTo("w640"));
        });

        assertDomainFailure(
                () -> service.list(-1, 24, null),
                "MEDIA_QUERY_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertDomainFailure(
                () -> service.list(0, 101, null),
                "MEDIA_QUERY_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertDomainFailure(
                () -> service.list(0, 24, "ready"),
                "MEDIA_QUERY_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void translationUpdateRequiresExactlyBothLocalesAndStrictHttps() {
        assertDomainFailure(
                () -> service.updateTranslations(ASSET_ID, List.of(
                        translation("en", "Gameplay", null))),
                "MEDIA_TRANSLATIONS_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertDomainFailure(
                () -> service.updateTranslations(ASSET_ID, List.of(
                        translation("en", "Gameplay", null),
                        translation("en", "Duplicate", null))),
                "MEDIA_TRANSLATIONS_INVALID",
                HttpStatus.UNPROCESSABLE_ENTITY);
        for (String sourceUrl : List.of(
                "https:///missing-host",
                "https://user:secret@example.com/path#private",
                "https://example.com:0/path",
                "https://example.com:65536/path",
                "https://example.com/path#private")) {
            assertDomainFailure(
                    () -> service.updateTranslations(ASSET_ID, List.of(
                            translation("zh-CN", "游戏截图", sourceUrl),
                            translation("en", "Gameplay", null))),
                    "MEDIA_TRANSLATIONS_INVALID",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        verify(currentAdmin, never()).requireAdminId();
        verify(translations, never()).replaceAll(any(), any());
    }

    @Test
    void translationUpdateMutatesNotifiesAndAuditsInsideOneCallback() {
        MediaAssetRecord before = asset(MediaStatus.READY, 1, null);
        MediaAssetRecord after = asset(MediaStatus.READY, 2, null);
        List<MediaTranslationInput> input = List.of(
                translation("zh-CN", "游戏截图", "https://example.com/zh"),
                translation("en", "Gameplay", "https://example.com/en"));
        when(currentAdmin.requireAdminId()).thenReturn(ADMIN_ID);
        when(assets.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(before));
        when(assets.incrementVersion(ASSET_ID, 1)).thenReturn(Optional.of(after));
        when(translations.findByAssetId(ASSET_ID)).thenReturn(List.of(
                new MediaTranslationRecord(
                        ASSET_ID, "en", "Gameplay", "", "", "https://example.com/en"),
                new MediaTranslationRecord(
                        ASSET_ID, "zh-CN", "游戏截图", "", "", "https://example.com/zh")));
        when(variants.findByAssetId(ASSET_ID)).thenReturn(List.of(variant()));

        var result = service.updateTranslations(ASSET_ID, input);

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.translations()).hasSize(2);
        InOrder order = inOrder(
                assets, translations, firstListener, secondListener, audit);
        order.verify(assets).findByIdForUpdate(ASSET_ID);
        order.verify(translations).replaceAll(any(), any());
        order.verify(assets).incrementVersion(ASSET_ID, 1);
        order.verify(firstListener).onMediaChanged(
                ASSET_ID, MediaChangeType.TRANSLATION_UPDATED);
        order.verify(secondListener).onMediaChanged(
                ASSET_ID, MediaChangeType.TRANSLATION_UPDATED);
        order.verify(audit).record(any(AuditCommand.class));

        ArgumentCaptor<AuditCommand> captured = ArgumentCaptor.forClass(AuditCommand.class);
        verify(audit).record(captured.capture());
        assertThat(captured.getValue().actorAdminId()).isEqualTo(ADMIN_ID);
        assertThat(captured.getValue().action()).isEqualTo("MEDIA_TRANSLATIONS_UPDATE");
        assertThat(captured.getValue().metadata()).isEmpty();
    }

    @Test
    void referencedAssetCannotBeArchivedAndResponseDoesNotExposeReferenceIds() {
        MediaAssetRecord ready = asset(MediaStatus.READY, 1, null);
        when(currentAdmin.requireAdminId()).thenReturn(ADMIN_ID);
        when(assets.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(ready));
        when(references.findReferences(ASSET_ID)).thenReturn(List.of(
                new MediaReference("PROJECT", REFERENCE_ID)));

        assertThatThrownBy(() -> service.archive(ASSET_ID))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("MEDIA_STILL_REFERENCED");
                    assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.fieldErrors()).isEmpty();
                    assertThat(failure.getMessage())
                            .doesNotContain(REFERENCE_ID.toString());
                });

        verify(assets, never()).archive(ASSET_ID, 1);
        verify(audit, never()).record(any());
    }

    @Test
    void unreferencedReadyAssetIsArchivedAndAuditedWithoutStorageDeletion() {
        MediaAssetRecord ready = asset(MediaStatus.READY, 1, null);
        MediaAssetRecord archived = asset(MediaStatus.ARCHIVED, 2, NOW);
        when(currentAdmin.requireAdminId()).thenReturn(ADMIN_ID);
        when(assets.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(ready));
        when(references.findReferences(ASSET_ID)).thenReturn(List.of());
        when(assets.archive(ASSET_ID, 1)).thenReturn(Optional.of(archived));

        service.archive(ASSET_ID);

        verify(assets).archive(ASSET_ID, 1);
        ArgumentCaptor<AuditCommand> captured = ArgumentCaptor.forClass(AuditCommand.class);
        verify(audit).record(captured.capture());
        assertThat(captured.getValue().action()).isEqualTo("MEDIA_ARCHIVE");
        assertThat(captured.getValue().targetId()).isEqualTo(ASSET_ID.toString());
        assertThat(captured.getValue().metadata()).isEmpty();
    }

    private static MediaTranslationInput translation(
            String locale, String alt, String sourceUrl) {
        return new MediaTranslationInput(locale, alt, "", "", sourceUrl);
    }

    private static MediaAssetRecord asset(
            MediaStatus status, long version, Instant archivedAt) {
        return new MediaAssetRecord(
                ASSET_ID,
                StorageProvider.LOCAL,
                null,
                null,
                MediaObjectKeys.originalKey(ASSET_ID, SHA256, "image/png"),
                "work.png",
                "image/png",
                100,
                1920,
                1080,
                SHA256,
                status,
                archivedAt,
                version,
                NOW,
                NOW);
    }

    private static MediaVariantRecord variant() {
        return new MediaVariantRecord(
                UUID.fromString("23000000-0000-4000-8000-000000000004"),
                ASSET_ID,
                "w640",
                "PNG",
                MediaObjectKeys.variantKey(ASSET_ID, "w640", SHA256, "image/png"),
                "image/png",
                50,
                640,
                360,
                SHA256,
                "READY",
                NOW);
    }

    private static void assertDomainFailure(
            Runnable invocation, String code, HttpStatus status) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.status()).isEqualTo(status);
                });
    }
}
