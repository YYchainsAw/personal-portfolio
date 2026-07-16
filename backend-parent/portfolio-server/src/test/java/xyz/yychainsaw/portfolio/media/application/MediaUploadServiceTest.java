package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;

class MediaUploadServiceTest {
    private static final UUID ACTOR_ID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void resolvesActorBeforeTransferringOwnedInputToCore() {
        CurrentAdminProvider currentAdmin = mock(CurrentAdminProvider.class);
        MediaIngestService ingest = mock(MediaIngestService.class);
        MediaUploadService service = new MediaUploadService(currentAdmin, ingest);
        UploadMediaCommand command = new UploadMediaCommand(
                "document.pdf", "application/pdf", 9, new ByteArrayInputStream(new byte[9]));
        MediaAssetView expected = view();
        when(currentAdmin.requireAdminId()).thenReturn(ACTOR_ID);
        when(ingest.ingest(command, ACTOR_ID)).thenReturn(expected);

        assertThat(service.upload(command)).isSameAs(expected);

        InOrder order = inOrder(currentAdmin, ingest);
        order.verify(currentAdmin).requireAdminId();
        order.verify(ingest).ingest(command, ACTOR_ID);
    }

    @Test
    void actorFailureClosesInputExactlyOnceAndPreservesAuthenticationFailure() {
        CurrentAdminProvider currentAdmin = mock(CurrentAdminProvider.class);
        MediaIngestService ingest = mock(MediaIngestService.class);
        MediaUploadService service = new MediaUploadService(currentAdmin, ingest);
        CloseCountingInputStream input = new CloseCountingInputStream();
        UploadMediaCommand command =
                new UploadMediaCommand("private.pdf", "application/pdf", 1, input);
        DomainException authenticationFailure = new DomainException(
                "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
        when(currentAdmin.requireAdminId()).thenThrow(authenticationFailure);

        assertThatThrownBy(() -> service.upload(command)).isSameAs(authenticationFailure);
        assertThat(input.closeCalls()).isOne();
        assertThat(authenticationFailure.getSuppressed()).isEmpty();
        verify(ingest, never()).ingest(command, ACTOR_ID);
    }

    @Test
    void nullCommandIsRejectedBeforeActorLookupWithFrozenFailure() {
        CurrentAdminProvider currentAdmin = mock(CurrentAdminProvider.class);
        MediaIngestService ingest = mock(MediaIngestService.class);
        MediaUploadService service = new MediaUploadService(currentAdmin, ingest);

        assertThatThrownBy(() -> service.upload(null))
                .isExactlyInstanceOf(DomainException.class)
                .satisfies(failure -> {
                    DomainException domain = (DomainException) failure;
                    assertThat(domain.code()).isEqualTo("MEDIA_REQUEST_INVALID");
                    assertThat(domain.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(domain.fieldErrors()).isEmpty();
                    assertThat(domain).hasNoCause();
                    assertThat(domain.getSuppressed()).isEmpty();
                });
        verify(currentAdmin, never()).requireAdminId();
    }

    @Test
    void nonNullCommandResolvesActorBeforeNullInputValidation() {
        CurrentAdminProvider currentAdmin = mock(CurrentAdminProvider.class);
        MediaIngestService ingest = mock(MediaIngestService.class);
        MediaUploadService service = new MediaUploadService(currentAdmin, ingest);
        UploadMediaCommand command =
                new UploadMediaCommand("private.pdf", null, -1, null);
        when(currentAdmin.requireAdminId()).thenReturn(ACTOR_ID);

        assertRequestInvalid(() -> service.upload(command));
        verify(currentAdmin).requireAdminId();
        verify(ingest, never()).ingest(command, ACTOR_ID);
    }

    @Test
    void actorFailurePreservesAuthenticationWhenOwnedInputCloseAlsoFails() {
        CurrentAdminProvider currentAdmin = mock(CurrentAdminProvider.class);
        MediaIngestService ingest = mock(MediaIngestService.class);
        MediaUploadService service = new MediaUploadService(currentAdmin, ingest);
        CloseFailingInputStream input = new CloseFailingInputStream();
        UploadMediaCommand command =
                new UploadMediaCommand("private.pdf", "application/pdf", 1, input);
        DomainException authenticationFailure = new DomainException(
                "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
        when(currentAdmin.requireAdminId()).thenThrow(authenticationFailure);

        assertThatThrownBy(() -> service.upload(command)).isSameAs(authenticationFailure);
        assertThat(input.closeCalls()).isOne();
        assertThat(authenticationFailure).hasNoCause();
        assertThat(authenticationFailure.getSuppressed()).isEmpty();
    }

    @Test
    void successfulActorTransfersOwnershipWithoutFacadeDoubleClose() {
        CurrentAdminProvider currentAdmin = mock(CurrentAdminProvider.class);
        MediaIngestService ingest = mock(MediaIngestService.class);
        MediaUploadService service = new MediaUploadService(currentAdmin, ingest);
        CloseCountingInputStream input = new CloseCountingInputStream();
        UploadMediaCommand command =
                new UploadMediaCommand("private.pdf", "application/pdf", 1, input);
        DomainException downstream = new DomainException(
                "MEDIA_UPLOAD_FAILED", HttpStatus.INTERNAL_SERVER_ERROR, Map.of());
        when(currentAdmin.requireAdminId()).thenReturn(ACTOR_ID);
        when(ingest.ingest(command, ACTOR_ID)).thenAnswer(invocation -> {
            input.close();
            throw downstream;
        });

        assertThatThrownBy(() -> service.upload(command)).isSameAs(downstream);
        assertThat(input.closeCalls()).isOne();
    }

    @Test
    void servletContextRegistersFacadeAndNonWebContextDoesNot() {
        new ApplicationContextRunner()
                .withUserConfiguration(MediaUploadService.class)
                .run(context -> assertThat(context).doesNotHaveBean(MediaUploadService.class));

        new WebApplicationContextRunner()
                .withUserConfiguration(MediaUploadService.class)
                .withBean(CurrentAdminProvider.class, () -> mock(CurrentAdminProvider.class))
                .withBean(MediaIngestService.class, () -> mock(MediaIngestService.class))
                .run(context -> assertThat(context).hasSingleBean(MediaUploadService.class));
    }

    private static void assertRequestInvalid(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isExactlyInstanceOf(DomainException.class)
                .satisfies(failure -> {
                    DomainException domain = (DomainException) failure;
                    assertThat(domain.code()).isEqualTo("MEDIA_REQUEST_INVALID");
                    assertThat(domain.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(domain.fieldErrors()).isEmpty();
                    assertThat(domain).hasNoCause();
                    assertThat(domain.getSuppressed()).isEmpty();
                });
    }

    private static MediaAssetView view() {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        return new MediaAssetView(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                "document.pdf",
                "application/pdf",
                9,
                null,
                null,
                "a".repeat(64),
                "PROCESSING",
                0,
                now,
                now);
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static class CloseCountingInputStream extends FilterInputStream {
        private int closeCalls;

        protected CloseCountingInputStream() {
            super(new ByteArrayInputStream(new byte[] {1}));
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            super.close();
        }

        protected int closeCalls() {
            return closeCalls;
        }
    }

    private static final class CloseFailingInputStream extends CloseCountingInputStream {
        @Override
        public void close() throws IOException {
            super.close();
            throw new IOException("provider close secret");
        }
    }
}
