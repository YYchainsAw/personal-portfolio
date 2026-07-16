package xyz.yychainsaw.portfolio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaReferenceResolverTest {
    private static final UUID ASSET_ID = UUID.fromString(
            "20000000-0000-4000-8000-000000000001");
    private static final MediaReference PROJECT_REFERENCE = new MediaReference(
            "PROJECT", UUID.fromString("30000000-0000-4000-8000-000000000001"));
    private static final MediaReference REVISION_REFERENCE = new MediaReference(
            "CONTENT_REVISION", UUID.fromString("40000000-0000-4000-8000-000000000001"));

    @Test
    void findReferencesCallsEveryCheckerInOrderAndKeepsFirstSeenReferenceOrder() {
        List<String> calls = new ArrayList<>();
        MediaReferenceResolver resolver = new MediaReferenceResolver(List.of(
                assetId -> {
                    calls.add("workspace");
                    assertThat(assetId).isEqualTo(ASSET_ID);
                    return List.of(PROJECT_REFERENCE, PROJECT_REFERENCE);
                },
                assetId -> {
                    calls.add("history");
                    assertThat(assetId).isEqualTo(ASSET_ID);
                    return List.of(PROJECT_REFERENCE, REVISION_REFERENCE);
                }));

        List<MediaReference> references = resolver.findReferences(ASSET_ID);

        assertThat(calls).containsExactly("workspace", "history");
        assertThat(references).containsExactly(PROJECT_REFERENCE, REVISION_REFERENCE);
        assertThatThrownBy(() -> references.add(PROJECT_REFERENCE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void hasReferencesStillCallsCheckersAfterTheFirstMatch() {
        List<String> calls = new ArrayList<>();
        MediaReferenceResolver resolver = new MediaReferenceResolver(List.of(
                assetId -> {
                    calls.add("first");
                    return List.of(PROJECT_REFERENCE);
                },
                assetId -> {
                    calls.add("second");
                    return List.of();
                }));

        assertThat(resolver.hasReferences(ASSET_ID)).isTrue();
        assertThat(calls).containsExactly("first", "second");
    }

    @Test
    void archiveQueriesAllowNoCheckersButCleanupRequiresOne() {
        MediaReferenceResolver resolver = new MediaReferenceResolver(List.of());

        assertThat(resolver.findReferences(ASSET_ID)).isEmpty();
        assertThat(resolver.hasReferences(ASSET_ID)).isFalse();
        assertThatThrownBy(resolver::requireCheckerForCleanup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_REFERENCE_CHECKER_REQUIRED")
                .hasNoCause();
    }

    @Test
    void invalidCheckerConfigurationFailsWithOnlyTheFixedSafeCode() {
        assertSafeFailure(() -> new MediaReferenceResolver(null));

        List<MediaReferenceChecker> checkerWithNullItem = new ArrayList<>();
        checkerWithNullItem.add(null);
        assertSafeFailure(() -> new MediaReferenceResolver(checkerWithNullItem));
    }

    @Test
    void checkerFailureIsSanitizedAfterEveryCheckerWasInvoked() {
        List<String> calls = new ArrayList<>();
        MediaReferenceResolver resolver = new MediaReferenceResolver(List.of(
                assetId -> {
                    calls.add("broken");
                    throw new IllegalStateException("secret datasource detail");
                },
                assetId -> {
                    calls.add("remaining");
                    return List.of(REVISION_REFERENCE);
                }));

        assertSafeFailure(() -> resolver.findReferences(ASSET_ID));
        assertThat(calls).containsExactly("broken", "remaining");
    }

    @Test
    void nullOrInvalidCheckerResultsFailWithOnlyTheFixedSafeCode() {
        assertInvalidResult(null);
        assertInvalidResult(Arrays.asList((MediaReference) null));
        assertInvalidResult(List.of(new MediaReference(null, PROJECT_REFERENCE.referenceId())));
        assertInvalidResult(List.of(new MediaReference("", PROJECT_REFERENCE.referenceId())));
        assertInvalidResult(List.of(new MediaReference("   ", PROJECT_REFERENCE.referenceId())));
        assertInvalidResult(List.of(new MediaReference(" PROJECT", PROJECT_REFERENCE.referenceId())));
        assertInvalidResult(List.of(new MediaReference("PROJECT ", PROJECT_REFERENCE.referenceId())));
        assertInvalidResult(List.of(new MediaReference("PROJECT", null)));
        assertSafeFailure(() -> new MediaReferenceResolver(List.of()).findReferences(null));
    }

    private static void assertInvalidResult(List<MediaReference> result) {
        MediaReferenceResolver resolver = new MediaReferenceResolver(List.of(assetId -> result));
        assertSafeFailure(() -> resolver.findReferences(ASSET_ID));
    }

    private static void assertSafeFailure(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MEDIA_REFERENCE_CHECK_FAILED")
                .hasNoCause();
    }
}
