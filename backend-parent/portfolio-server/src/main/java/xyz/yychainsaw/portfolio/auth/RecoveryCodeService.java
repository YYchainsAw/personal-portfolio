package xyz.yychainsaw.portfolio.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository;
import xyz.yychainsaw.portfolio.auth.persistence.RecoveryCodeRepository.StoredCode;

@Service
public class RecoveryCodeService {
    private static final Pattern RECOVERY_CODE =
            Pattern.compile("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");

    private final RecoveryCodeRepository repository;
    private final PasswordEncoder encoder;

    public RecoveryCodeService(RecoveryCodeRepository repository, PasswordEncoder encoder) {
        this.repository = Objects.requireNonNull(
                repository, "recovery-code repository is required");
        this.encoder = Objects.requireNonNull(encoder, "password encoder is required");
    }

    public List<String> hashAll(List<String> plaintextCodes) {
        if (plaintextCodes == null || plaintextCodes.size() < 1 || plaintextCodes.size() > 10) {
            throw new IllegalArgumentException("recovery-code count must be between 1 and 10");
        }

        List<String> normalizedCodes = new ArrayList<>(plaintextCodes.size());
        Set<String> distinctCodes = new HashSet<>();
        for (String plaintext : plaintextCodes) {
            String normalized = normalizeIfValid(plaintext);
            if (normalized == null) {
                throw new IllegalArgumentException("recovery code format is invalid");
            }
            if (!distinctCodes.add(normalized)) {
                throw new IllegalArgumentException("recovery codes must be distinct");
            }
            normalizedCodes.add(normalized);
        }

        List<String> hashes = new ArrayList<>(normalizedCodes.size());
        Set<String> distinctHashes = new HashSet<>();
        for (String normalized : normalizedCodes) {
            String hash;
            try {
                hash = encoder.encode(normalized);
            } catch (RuntimeException providerFailure) {
                throw new IllegalStateException("recovery-code hashing failed");
            }
            if (hash == null || hash.isBlank() || hash.length() > 255) {
                throw new IllegalStateException("recovery-code provider returned an invalid hash");
            }
            if (!distinctHashes.add(hash)) {
                throw new IllegalStateException("recovery-code provider returned duplicate hashes");
            }
            hashes.add(hash);
        }
        return List.copyOf(hashes);
    }

    @Transactional
    public boolean consume(UUID adminId, String plaintextCode) {
        Objects.requireNonNull(adminId, "admin id is required");
        if (plaintextCode == null || plaintextCode.length() > 64) {
            return false;
        }
        String normalized = normalizeIfValid(plaintextCode);
        if (normalized == null) {
            return false;
        }

        List<StoredCode> storedCodes = List.copyOf(repository.findUnused(adminId));
        StoredCode matched = null;
        boolean multipleMatches = false;
        for (StoredCode stored : storedCodes) {
            boolean current;
            try {
                current = encoder.matches(normalized, stored.hash());
            } catch (RuntimeException providerFailure) {
                throw new IllegalStateException("recovery-code verification failed");
            }
            if (current) {
                if (matched == null) {
                    matched = stored;
                } else {
                    multipleMatches = true;
                }
            }
        }
        if (multipleMatches) {
            throw new IllegalStateException("multiple recovery-code hashes matched");
        }
        return matched != null && repository.markUsed(adminId, matched.id());
    }

    private static String normalizeIfValid(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        int start = 0;
        int end = plaintext.length();
        while (start < end && isAsciiWhitespace(plaintext.charAt(start))) {
            start++;
        }
        while (end > start && isAsciiWhitespace(plaintext.charAt(end - 1))) {
            end--;
        }
        String normalized = plaintext.substring(start, end).toUpperCase(Locale.ROOT);
        return RECOVERY_CODE.matcher(normalized).matches() ? normalized : null;
    }

    private static boolean isAsciiWhitespace(char character) {
        return character == ' ' || character >= '\t' && character <= '\r';
    }
}
