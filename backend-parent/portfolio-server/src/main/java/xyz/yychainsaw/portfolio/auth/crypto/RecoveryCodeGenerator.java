package xyz.yychainsaw.portfolio.auth.crypto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class RecoveryCodeGenerator {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int MAX_COUNT = 10;
    private static final int CANDIDATES_PER_CODE = 32;

    private final SecureRandom random;

    public RecoveryCodeGenerator() {
        this(new SecureRandom());
    }

    RecoveryCodeGenerator(SecureRandom random) {
        if (random == null) {
            throw new IllegalArgumentException("secure random is required");
        }
        this.random = random;
    }

    public List<String> generate(int count) {
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("recovery-code count must be between 1 and 10");
        }

        Set<String> codes = new LinkedHashSet<>();
        int collisionBudget = count * CANDIDATES_PER_CODE;
        for (int candidate = 0; candidate < collisionBudget && codes.size() < count; candidate++) {
            codes.add(generateOne());
        }
        if (codes.size() != count) {
            throw new IllegalStateException("recovery-code generation exhausted collision budget");
        }
        return List.copyOf(new ArrayList<>(codes));
    }

    private String generateOne() {
        StringBuilder value = new StringBuilder(14);
        for (int index = 0; index < 12; index++) {
            if (index == 4 || index == 8) {
                value.append('-');
            }
            value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return value.toString();
    }
}
