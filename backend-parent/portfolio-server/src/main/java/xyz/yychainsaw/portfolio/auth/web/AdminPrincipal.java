package xyz.yychainsaw.portfolio.auth.web;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record AdminPrincipal(UUID id, String username)
        implements Principal, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final Pattern CANONICAL_USERNAME =
            Pattern.compile("[A-Za-z0-9._-]{3,64}");

    public AdminPrincipal {
        Objects.requireNonNull(id, "admin id is required");
        Objects.requireNonNull(username, "username is required");
        if (!CANONICAL_USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("administrator username is not canonical");
        }
    }

    @Override
    public String getName() {
        return username;
    }

    @Override
    public String toString() {
        return "AdminPrincipal[id=" + id + ", username=<redacted>]";
    }
}
