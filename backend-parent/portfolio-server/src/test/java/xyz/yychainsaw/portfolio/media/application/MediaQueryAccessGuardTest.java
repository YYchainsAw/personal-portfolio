package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaQueryAccessGuardTest {
    private static final UUID FIRST =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SECOND =
            UUID.fromString("20000000-0000-4000-8000-000000000001");

    private final MediaQueryAccessGuard guard = new MediaQueryAccessGuard();

    @Test
    void queriesAreUnrestrictedOutsideAnActivePublicationScope() {
        assertThatCode(() -> guard.checkAsset(FIRST)).doesNotThrowAnyException();
        assertThatCode(() -> guard.checkVariant(FIRST, "card"))
                .doesNotThrowAnyException();
    }

    @Test
    void activeScopeAllowsOnlyThePrelockedAssetAndVariantPlan() {
        MediaQueryAccessGuard.VariantKey card =
                new MediaQueryAccessGuard.VariantKey(FIRST, "card");

        try (MediaQueryAccessGuard.Scope ignored =
                guard.openScope(Set.of(FIRST), Set.of(card))) {
            assertThatCode(() -> guard.checkAsset(FIRST)).doesNotThrowAnyException();
            assertThatCode(() -> guard.checkVariant(FIRST, "card"))
                    .doesNotThrowAnyException();
            assertOutsidePlan(() -> guard.checkAsset(SECOND));
            assertOutsidePlan(() -> guard.checkVariant(FIRST, "desktop"));
            assertOutsidePlan(() -> guard.checkVariant(SECOND, "card"));
        }

        assertThatCode(() -> guard.checkAsset(SECOND)).doesNotThrowAnyException();
    }

    @Test
    void nestedScopesRestoreTheOuterPlanAndCloseWithoutLeakingThreadState() {
        MediaQueryAccessGuard.VariantKey firstCard =
                new MediaQueryAccessGuard.VariantKey(FIRST, "card");
        MediaQueryAccessGuard.VariantKey secondDesktop =
                new MediaQueryAccessGuard.VariantKey(SECOND, "desktop");

        try (MediaQueryAccessGuard.Scope outer =
                guard.openScope(Set.of(FIRST), Set.of(firstCard))) {
            try (MediaQueryAccessGuard.Scope inner =
                    guard.openScope(Set.of(SECOND), Set.of(secondDesktop))) {
                assertThatCode(() -> guard.checkAsset(SECOND))
                        .doesNotThrowAnyException();
                assertOutsidePlan(() -> guard.checkAsset(FIRST));
            }
            assertThatCode(() -> guard.checkAsset(FIRST)).doesNotThrowAnyException();
            assertOutsidePlan(() -> guard.checkAsset(SECOND));
        }

        assertThatCode(() -> guard.checkAsset(FIRST)).doesNotThrowAnyException();
        assertThatCode(() -> guard.checkAsset(SECOND)).doesNotThrowAnyException();
    }

    private static void assertOutsidePlan(Runnable query) {
        assertThatThrownBy(query::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("media query is outside the active publication plan");
    }
}
