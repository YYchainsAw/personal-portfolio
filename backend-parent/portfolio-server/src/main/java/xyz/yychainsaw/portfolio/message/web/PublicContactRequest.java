package xyz.yychainsaw.portfolio.message.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublicContactRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 160) String subject,
        @NotBlank @Size(max = 5000) String message,
        @Size(max = 200) String website,
        @AssertTrue boolean privacyAccepted) {

    @Override
    public String toString() {
        return "PublicContactRequest[name=<redacted>, email=<redacted>, "
                + "subject=<redacted>, message=<redacted>, website=<redacted>, "
                + "privacyAccepted=<redacted>]";
    }
}
