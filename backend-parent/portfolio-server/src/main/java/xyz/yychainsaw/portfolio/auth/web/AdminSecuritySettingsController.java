package xyz.yychainsaw.portfolio.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService.ActiveSession;
import xyz.yychainsaw.portfolio.auth.web.AdminSecuritySettingsService.EnrollmentDelivery;
import xyz.yychainsaw.portfolio.auth.web.AdminSecuritySettingsService.EnrollmentMaterial;
import xyz.yychainsaw.portfolio.auth.web.AdminSecuritySettingsService.RecoveryCodesDelivery;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@RestController
@RequestMapping("/api/admin/security")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class AdminSecuritySettingsController {
    private final SecurityCurrentAdminProvider current;
    private final AdminSecuritySettingsService settings;
    private final ParentMutationGate parentMutations;

    public AdminSecuritySettingsController(
            SecurityCurrentAdminProvider current,
            AdminSecuritySettingsService settings,
            ParentMutationGate parentMutations) {
        this.current = Objects.requireNonNull(
                current, "current administrator is required");
        this.settings = Objects.requireNonNull(
                settings, "security-settings service is required");
        this.parentMutations = Objects.requireNonNull(
                parentMutations, "parent-mutation gate is required");
    }

    @PostMapping("/password")
    public ResponseEntity<Void> password(
            @Valid @RequestBody PasswordChangeRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        Objects.requireNonNull(body, "password-change request is required");
        AdminPrincipal principal = current.requirePrincipal();
        ActiveSession active = requireCurrentActive(request, principal.id());
        try (ParentMutationGate.Lease ignored = parentMutations.acquire(response)) {
            settings.changePassword(
                    principal.id(),
                    active,
                    body.currentPassword(),
                    body.currentTotp(),
                    body.newPassword(),
                    request);
            return ResponseEntity.noContent()
                    .cacheControl(CacheControl.noStore())
                    .build();
        } catch (DomainException expected) {
            copyRetryAfter(expected, response);
            throw expected;
        }
    }

    @PostMapping("/totp/enrollment")
    public ResponseEntity<TotpEnrollmentResponse> enrollment(
            @Valid @RequestBody ReauthenticationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        Objects.requireNonNull(body, "reauthentication request is required");
        AdminPrincipal principal = current.requirePrincipal();
        ActiveSession active = requireCurrentActive(request, principal.id());
        try (ParentMutationGate.Lease ignored = parentMutations.acquire(response);
                EnrollmentDelivery delivery = settings.beginTotpEnrollment(
                        principal.id(),
                        active,
                        body.currentPassword(),
                        body.currentTotp(),
                        request)) {
            EnrollmentMaterial material = Objects.requireNonNull(
                    delivery.take(), "enrollment delivery returned no material");
            return noStore(new TotpEnrollmentResponse(
                    material.enrollmentId(),
                    material.provisioningUri(),
                    material.expiresAt()));
        } catch (DomainException expected) {
            copyRetryAfter(expected, response);
            throw expected;
        }
    }

    @PostMapping("/totp/confirm")
    public ResponseEntity<RecoveryCodesResponse> confirm(
            @Valid @RequestBody TotpConfirmRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        Objects.requireNonNull(body, "TOTP-confirmation request is required");
        AdminPrincipal principal = current.requirePrincipal();
        ActiveSession active = requireCurrentActive(request, principal.id());
        try (ParentMutationGate.Lease ignored = parentMutations.acquire(response);
                RecoveryCodesDelivery delivery = settings.confirmTotp(
                        principal.id(),
                        active,
                        body.enrollmentId(),
                        body.newTotp(),
                        request)) {
            return noStore(new RecoveryCodesResponse(delivery.take()));
        } catch (DomainException expected) {
            copyRetryAfter(expected, response);
            throw expected;
        }
    }

    @PostMapping("/recovery-codes/regenerate")
    public ResponseEntity<RecoveryCodesResponse> regenerate(
            @Valid @RequestBody ReauthenticationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        Objects.requireNonNull(body, "reauthentication request is required");
        AdminPrincipal principal = current.requirePrincipal();
        ActiveSession active = requireCurrentActive(request, principal.id());
        try (ParentMutationGate.Lease ignored = parentMutations.acquire(response);
                RecoveryCodesDelivery delivery = settings.regenerateRecoveryCodes(
                        principal.id(),
                        active,
                        body.currentPassword(),
                        body.currentTotp(),
                        request)) {
            return noStore(new RecoveryCodesResponse(delivery.take()));
        } catch (DomainException expected) {
            copyRetryAfter(expected, response);
            throw expected;
        }
    }

    private static ActiveSession requireCurrentActive(
            HttpServletRequest request, UUID adminId) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(adminId, "administrator id is required");
        Object value = request.getAttribute(
                SessionMetadataEnforcementFilter.ACTIVE_SESSION_ATTRIBUTE);
        if (!(value instanceof ActiveSession active) || !active.adminId().equals(adminId)) {
            throw new DomainException(
                    "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, Map.of());
        }
        return active;
    }

    private static void copyRetryAfter(
            DomainException failure, HttpServletResponse response) {
        if (!"RATE_LIMITED".equals(failure.code())) {
            return;
        }
        String value = failure.fieldErrors().get("retryAfterSeconds");
        if (value != null && value.matches("[1-9][0-9]{0,9}")) {
            response.setHeader(HttpHeaders.RETRY_AFTER, value);
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Objects.requireNonNull(body, "response body is required"));
    }

    private static String bounded(String value, int maximum, String name) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(
                    name + " must be at most " + maximum + " UTF-16 units");
        }
        return value;
    }

    public record PasswordChangeRequest(
            @Size(max = 256, message = "must be at most 256 UTF-16 units")
                    String currentPassword,
            @Size(max = 64, message = "must be at most 64 UTF-16 units")
                    String currentTotp,
            @Size(max = 256, message = "must be at most 256 UTF-16 units")
                    String newPassword) {
        public PasswordChangeRequest {
            currentPassword = bounded(currentPassword, 256, "current password");
            currentTotp = bounded(currentTotp, 64, "current TOTP");
            newPassword = bounded(newPassword, 256, "new password");
        }

        @Override
        public String toString() {
            return "PasswordChangeRequest[currentPassword=<redacted>"
                    + ", currentTotp=<redacted>, newPassword=<redacted>]";
        }
    }

    public record ReauthenticationRequest(
            @Size(max = 256, message = "must be at most 256 UTF-16 units")
                    String currentPassword,
            @Size(max = 64, message = "must be at most 64 UTF-16 units")
                    String currentTotp) {
        public ReauthenticationRequest {
            currentPassword = bounded(currentPassword, 256, "current password");
            currentTotp = bounded(currentTotp, 64, "current TOTP");
        }

        @Override
        public String toString() {
            return "ReauthenticationRequest[currentPassword=<redacted>"
                    + ", currentTotp=<redacted>]";
        }
    }

    public record TotpConfirmRequest(
            @Size(max = 64, message = "must be at most 64 UTF-16 units")
                    String enrollmentId,
            @Size(max = 64, message = "must be at most 64 UTF-16 units")
                    String newTotp) {
        public TotpConfirmRequest {
            enrollmentId = bounded(enrollmentId, 64, "enrollment id");
            newTotp = bounded(newTotp, 64, "new TOTP");
        }

        @Override
        public String toString() {
            return "TotpConfirmRequest[enrollmentId=<redacted>, newTotp=<redacted>]";
        }
    }

    public record TotpEnrollmentResponse(
            UUID enrollmentId, String provisioningUri, Instant expiresAt) {
        public TotpEnrollmentResponse {
            Objects.requireNonNull(enrollmentId, "enrollment id is required");
            if (provisioningUri == null || provisioningUri.isBlank()) {
                throw new IllegalArgumentException("provisioning URI is invalid");
            }
            Objects.requireNonNull(expiresAt, "expiry timestamp is required");
        }

        @Override
        public String toString() {
            return "TotpEnrollmentResponse[enrollmentId=<redacted>"
                    + ", provisioningUri=<redacted>, expiresAt=" + expiresAt + ']';
        }
    }

    public record RecoveryCodesResponse(List<String> recoveryCodes) {
        public RecoveryCodesResponse {
            recoveryCodes = List.copyOf(
                    Objects.requireNonNull(recoveryCodes, "recovery codes are required"));
        }

        @Override
        public String toString() {
            return "RecoveryCodesResponse[recoveryCodes=<redacted>]";
        }
    }
}
