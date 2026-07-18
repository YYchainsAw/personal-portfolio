package xyz.yychainsaw.portfolio.message.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitContactCommand(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 160) String subject,
        @NotBlank @Size(max = 5000) String body,
        @Size(max = 200) String website,
        @AssertTrue boolean privacyAccepted,
        String rateLimitSubject) {

    @Override
    public String toString() {
        return "SubmitContactCommand[name=<redacted>, email=<redacted>, "
                + "subject=<redacted>, body=<redacted>, website=<redacted>, "
                + "privacyAccepted=<redacted>, rateLimitSubject=<redacted>]";
    }
}
