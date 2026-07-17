package xyz.yychainsaw.portfolio.media.application;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Constrains re-entrant media reads to a transaction's already acquired lock
 * plan. Queries remain unrestricted when no scope is active so ordinary media
 * management and public reads are unaffected.
 */
@Component
public final class MediaQueryAccessGuard {
    private static final String OUTSIDE_PLAN =
            "media query is outside the active publication plan";
    private static final Scope NO_OP_SCOPE = () -> {};

    private final boolean scopesEnabled;
    private final ThreadLocal<Deque<ScopeState>> scopes = new ThreadLocal<>();

    public MediaQueryAccessGuard() {
        this(true);
    }

    private MediaQueryAccessGuard(boolean scopesEnabled) {
        this.scopesEnabled = scopesEnabled;
    }

    static MediaQueryAccessGuard unrestricted() {
        return new MediaQueryAccessGuard(false);
    }

    public Scope openScope(Set<UUID> assetIds, Set<VariantKey> variants) {
        if (!scopesEnabled) {
            return NO_OP_SCOPE;
        }
        ScopeState state = new ScopeState(assetIds, variants);
        Deque<ScopeState> stack = scopes.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            scopes.set(stack);
        }
        stack.push(state);
        return new ScopeHandle(state, Thread.currentThread());
    }

    public void checkAsset(UUID assetId) {
        ScopeState active = activeScope();
        if (active != null && !active.assetIds().contains(assetId)) {
            throw new IllegalStateException(OUTSIDE_PLAN);
        }
    }

    public void checkVariant(UUID assetId, String variantName) {
        ScopeState active = activeScope();
        if (active != null
                && !active.variants().contains(new VariantKey(assetId, variantName))) {
            throw new IllegalStateException(OUTSIDE_PLAN);
        }
    }

    private ScopeState activeScope() {
        if (!scopesEnabled) {
            return null;
        }
        Deque<ScopeState> stack = scopes.get();
        return stack == null ? null : stack.peek();
    }

    public record VariantKey(UUID assetId, String variantName) {
        public VariantKey {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(variantName, "variantName");
            if (variantName.isBlank()) {
                throw new IllegalArgumentException("variantName must not be blank");
            }
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private record ScopeState(Set<UUID> assetIds, Set<VariantKey> variants) {
        private ScopeState {
            assetIds = Set.copyOf(Objects.requireNonNull(assetIds, "assetIds"));
            variants = Set.copyOf(Objects.requireNonNull(variants, "variants"));
            for (VariantKey variant : variants) {
                if (!assetIds.contains(variant.assetId())) {
                    throw new IllegalArgumentException(
                            "variant asset must belong to the asset plan");
                }
            }
        }
    }

    private final class ScopeHandle implements Scope {
        private final ScopeState state;
        private final Thread owner;
        private boolean closed;

        private ScopeHandle(ScopeState state, Thread owner) {
            this.state = state;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException(
                        "media query access scope must close on its owner thread");
            }
            Deque<ScopeState> stack = scopes.get();
            if (stack == null || stack.peek() != state) {
                throw new IllegalStateException(
                        "media query access scopes must close in reverse order");
            }
            stack.pop();
            closed = true;
            if (stack.isEmpty()) {
                scopes.remove();
            }
        }
    }
}
