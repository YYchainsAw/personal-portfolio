package xyz.yychainsaw.portfolio.message.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.yychainsaw.portfolio.auth.session.TrustedClientAddressResolver;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.message.application.ContactMessageService;
import xyz.yychainsaw.portfolio.message.application.SubmitContactCommand;

@RestController
@RequestMapping("/api/public")
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class PublicContactController {
    private final PublicContactBodyReader bodyReader;
    private final TrustedClientAddressResolver addresses;
    private final ContactRateLimitSubjectHasher rateSubjects;
    private final ContactMessageService contacts;

    public PublicContactController(
            PublicContactBodyReader bodyReader,
            TrustedClientAddressResolver addresses,
            ContactRateLimitSubjectHasher rateSubjects,
            ContactMessageService contacts) {
        this.bodyReader = Objects.requireNonNull(bodyReader, "contact body reader is required");
        this.addresses = Objects.requireNonNull(addresses, "client addresses are required");
        this.rateSubjects = Objects.requireNonNull(
                rateSubjects, "contact rate subjects are required");
        this.contacts = Objects.requireNonNull(contacts, "contact service is required");
    }

    @PostMapping(
            value = "/contact",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ContactAcceptedResponse> submit(
            HttpServletRequest request, HttpServletResponse response) {
        PublicContactRequest body = bodyReader.read(request);
        String website = body.website() == null ? "" : body.website();
        if (!website.isBlank()) {
            return accepted();
        }

        String rateLimitSubject = rateSubjects.hash(addresses.resolve(request));
        SubmitContactCommand command = new SubmitContactCommand(
                body.name(),
                body.email(),
                body.subject(),
                body.message(),
                website,
                body.privacyAccepted(),
                rateLimitSubject);
        try {
            contacts.submit(command);
            return accepted();
        } catch (DomainException expected) {
            copyRetryAfter(expected, response);
            throw expected;
        }
    }

    private static ResponseEntity<ContactAcceptedResponse> accepted() {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new ContactAcceptedResponse(true));
    }

    private static void copyRetryAfter(
            DomainException failure, HttpServletResponse response) {
        if (!"CONTACT_RATE_LIMITED".equals(failure.code())) {
            return;
        }
        String value = failure.fieldErrors().get("retryAfterSeconds");
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) {
            return;
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, value);
    }

    private record ContactAcceptedResponse(boolean accepted) {
    }
}
