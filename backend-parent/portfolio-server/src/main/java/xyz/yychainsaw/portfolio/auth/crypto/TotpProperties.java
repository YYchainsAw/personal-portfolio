package xyz.yychainsaw.portfolio.auth.crypto;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("portfolio.security.totp")
public record TotpProperties(
        int activeKeyVersion,
        String keyRing,
        String issuer,
        Duration pendingLifetime,
        int maxSecondFactorAttempts) {
    public TotpProperties {
        if (activeKeyVersion < 1 || keyRing == null || keyRing.isBlank()) {
            throw new IllegalArgumentException("a versioned TOTP key ring is required");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("TOTP issuer is required");
        }
        if (pendingLifetime == null
                || pendingLifetime.isZero()
                || pendingLifetime.isNegative()
                || maxSecondFactorAttempts < 1) {
            throw new IllegalArgumentException("invalid second-factor limits");
        }
        keyRing = keyRing.trim();
        issuer = issuer.trim();
    }

    @Override
    public String toString() {
        return "TotpProperties[activeKeyVersion=" + activeKeyVersion
                + ", keyRing=<redacted>, issuer=" + issuer
                + ", pendingLifetime=" + pendingLifetime
                + ", maxSecondFactorAttempts=" + maxSecondFactorAttempts + "]";
    }
}
