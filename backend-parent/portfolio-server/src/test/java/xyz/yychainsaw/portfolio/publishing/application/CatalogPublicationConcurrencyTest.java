package xyz.yychainsaw.portfolio.publishing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import xyz.yychainsaw.portfolio.api.admin.media.MediaAssetView;
import xyz.yychainsaw.portfolio.api.admin.media.MediaTranslationInput;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.media.application.MediaChangeType;
import xyz.yychainsaw.portfolio.media.application.MediaManagementService;
import xyz.yychainsaw.portfolio.publishing.api.ArchiveProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PublicationResult;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.PublicationRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.snapshot.SnapshotCodec;
import xyz.yychainsaw.portfolio.publishing.snapshot.v1.ProjectCatalogSnapshotV1;
import xyz.yychainsaw.portfolio.publishing.support.PublishingTestFixture;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
@ActiveProfiles("test")
@Isolated
@Import({
    PublishingTestFixture.class,
    CatalogPublicationConcurrencyTest.FixedAdminConfiguration.class
})
class CatalogPublicationConcurrencyTest extends PostgresIntegrationTestBase {
    private static final UUID CATALOG_ID = PublicationService.PROJECT_CATALOG_ID;

    @Autowired PublicationService service;
    @Autowired PublishingRepository publishing;
    @Autowired PublishingTestFixture fixture;
    @Autowired MediaManagementService mediaManagement;
    @Autowired SnapshotCodec codec;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;

    @MockitoSpyBean ContentMediaChangeListener mediaChangeListener;

    @BeforeEach
    void ensureRealAdminRow() {
        fixture.ensureAdmin();
    }

    @Test
    void sameExpectedCatalogVersionYieldsExactlyOneAtomicWinnerWithoutDeadlock()
            throws Exception {
        ProjectWorkspaceDto first = fixture.persistReadyProject();
        ProjectWorkspaceDto second = fixture.persistReadyProject();
        PublicationRow catalogBefore = publishing
                .find(AggregateType.PROJECT_CATALOG, CATALOG_ID)
                .orElseThrow();

        List<Attempt> attempts = publishBehindCatalogHolder(
                List.of(first, second), catalogBefore.version());

        List<Attempt> successes = attempts.stream()
                .filter(attempt -> attempt.result() != null)
                .toList();
        List<Attempt> failures = attempts.stream()
                .filter(attempt -> attempt.failure() != null)
                .toList();
        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertCatalogConflict(failures.get(0).failure());
        assertNoSqlState(failures.get(0).failure(), "40P01");

        UUID winnerId = successes.get(0).projectId();
        UUID loserId = failures.get(0).projectId();
        PublicationResult winner = successes.get(0).result();
        PublicationRow catalogAfter = publishing
                .find(AggregateType.PROJECT_CATALOG, CATALOG_ID)
                .orElseThrow();
        assertThat(catalogAfter.version()).isEqualTo(catalogBefore.version() + 1L);
        assertThat(catalogAfter.currentRevisionId()).isEqualTo(winner.catalogRevisionId());
        assertThat(winner.catalogVersion()).isEqualTo(catalogAfter.version());

        List<UUID> currentProjectIds = publishing.findPublishedProjects().stream()
                .map(PublicationRow::aggregateId)
                .toList();
        ProjectCatalogSnapshotV1 currentCatalog = currentCatalogSnapshot(catalogAfter);
        assertThat(currentCatalog.projects())
                .extracting(ProjectCatalogSnapshotV1.Card::projectId)
                .containsExactlyElementsOf(currentProjectIds);
        assertThat(currentProjectIds).contains(winnerId).doesNotContain(loserId);

        assertThat(publishing.find(AggregateType.PROJECT, winnerId))
                .get()
                .extracting(PublicationRow::status, PublicationRow::currentRevisionId)
                .containsExactly("PUBLISHED", winner.revisionId());
        assertThat(publishing.find(AggregateType.PROJECT, loserId)).isEmpty();
        assertThat(revisionCount(winnerId)).isEqualTo(1L);
        assertThat(revisionCount(loserId)).isZero();
        assertThat(projectDirty(winnerId)).isFalse();
        assertThat(projectDirty(loserId)).isTrue();
    }

    @Test
    void slugChangeCreatesRedirectAndAnotherProjectCannotClaimCurrentOrHistoricalSlug() {
        ProjectWorkspaceDto original = fixture.persistReadyProject();
        long catalogVersion = catalogPointer().version();
        PublicationResult first = service.publishProject(new PublishProjectCommand(
                original.id(), original.version(), 0L, catalogVersion));
        String historicalSlug = original.slug();

        String currentSlug = uniqueSlug("current");
        ProjectWorkspaceDto renamed = fixture.editProjectSlug(original.id(), currentSlug);
        PublicationResult second = service.publishProject(new PublishProjectCommand(
                original.id(),
                renamed.version(),
                first.aggregateVersion(),
                first.catalogVersion()));

        assertThat(publishing.redirectTarget(historicalSlug)).contains(currentSlug);
        PublicationRow currentPointer = publishing
                .find(AggregateType.PROJECT, original.id())
                .orElseThrow();
        assertThat(currentPointer.currentSlug()).isEqualTo(currentSlug);
        assertThat(currentPointer.currentRevisionId()).isEqualTo(second.revisionId());
        Instant redirectCreatedAt = jdbc.sql("""
                        select created_at
                        from portfolio.slug_redirect
                        where old_slug=:oldSlug
                        """)
                .param("oldSlug", historicalSlug)
                .query(Instant.class)
                .single();
        assertThat(redirectCreatedAt).isEqualTo(currentPointer.publishedAt());

        // Free the current public slug in the workspace unique index while retaining
        // it on the immutable publication pointer.
        fixture.editProjectSlug(original.id(), uniqueSlug("draft"));
        ProjectWorkspaceDto competitor = fixture.persistReadyProject();
        ProjectWorkspaceDto claimingCurrent =
                fixture.editProjectSlug(competitor.id(), currentSlug);
        long stableCatalogVersion = catalogPointer().version();
        assertCatalogConflictCode(
                catchThrowable(() -> service.publishProject(new PublishProjectCommand(
                        competitor.id(),
                        claimingCurrent.version(),
                        0L,
                        stableCatalogVersion))),
                "PROJECT_SLUG_CONFLICT");

        ProjectWorkspaceDto claimingHistorical =
                fixture.editProjectSlug(competitor.id(), historicalSlug);
        assertCatalogConflictCode(
                catchThrowable(() -> service.publishProject(new PublishProjectCommand(
                        competitor.id(),
                        claimingHistorical.version(),
                        0L,
                        stableCatalogVersion))),
                "PROJECT_SLUG_CONFLICT");

        assertThat(catalogPointer().version()).isEqualTo(stableCatalogVersion);
        assertThat(publishing.find(AggregateType.PROJECT, competitor.id())).isEmpty();
        assertThat(revisionCount(competitor.id())).isZero();
        assertThat(publishing.redirectTarget(historicalSlug)).contains(currentSlug);
        assertThat(publishing.find(AggregateType.PROJECT, original.id()))
                .get()
                .extracting(PublicationRow::currentSlug, PublicationRow::currentRevisionId)
                .containsExactly(currentSlug, second.revisionId());
    }

    @Test
    void archivedProjectRetainsItsCurrentSlugReservation() {
        ProjectWorkspaceDto original = fixture.persistReadyProject();
        PublicationResult published = service.publishProject(new PublishProjectCommand(
                original.id(), original.version(), 0L, catalogPointer().version()));
        String archivedSlug = original.slug();

        PublicationResult archived = service.archiveProject(new ArchiveProjectCommand(
                original.id(), published.aggregateVersion(), published.catalogVersion()));
        assertThat(publishing.find(AggregateType.PROJECT, original.id()))
                .get()
                .extracting(PublicationRow::status, PublicationRow::currentSlug)
                .containsExactly("ARCHIVED", archivedSlug);

        // Release only the editable-workspace unique key. The archived publication
        // pointer must continue reserving the last public slug.
        fixture.editProjectSlug(original.id(), uniqueSlug("archived-draft"));
        ProjectWorkspaceDto competitor = fixture.persistReadyProject();
        ProjectWorkspaceDto claimingArchived =
                fixture.editProjectSlug(competitor.id(), archivedSlug);
        long stableCatalogVersion = catalogPointer().version();

        assertCatalogConflictCode(
                catchThrowable(() -> service.publishProject(new PublishProjectCommand(
                        competitor.id(),
                        claimingArchived.version(),
                        0L,
                        stableCatalogVersion))),
                "PROJECT_SLUG_CONFLICT");

        assertThat(catalogPointer().version()).isEqualTo(stableCatalogVersion);
        assertThat(publishing.find(AggregateType.PROJECT, competitor.id())).isEmpty();
        assertThat(revisionCount(competitor.id())).isZero();
        assertThat(publishing.find(AggregateType.PROJECT, original.id()))
                .get()
                .extracting(
                        PublicationRow::status,
                        PublicationRow::currentSlug,
                        PublicationRow::version)
                .containsExactly("ARCHIVED", archivedSlug, archived.aggregateVersion());
    }

    @Test
    void translationUpdateAndRepublishRespectMediaBeforeContentLockOrder()
            throws Exception {
        ProjectWorkspaceDto project = fixture.persistReadyProject();
        UUID assetId = fixture.coverAssetId(project.id());
        PublicationRow initialCatalog = catalogPointer();
        PublicationResult initial = service.publishProject(new PublishProjectCommand(
                project.id(), project.version(), 0L, initialCatalog.version()));
        ProjectWorkspaceDto edited = fixture.editProjectEnglishTitle(
                project.id(), "Edited before concurrent republish");
        PublicationRow projectBefore = publishing
                .find(AggregateType.PROJECT, project.id())
                .orElseThrow();
        PublicationRow catalogBefore = catalogPointer();
        long mediaVersionBefore = mediaVersion(assetId);
        List<MediaTranslationInput> translationsBefore = currentTranslations(assetId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            lockProject(holder, project.id());

            Future<Operation<PublicationResult>> republishFuture = executor.submit(() ->
                    capture(() -> service.publishProject(new PublishProjectCommand(
                            project.id(),
                            edited.version(),
                            projectBefore.version(),
                            catalogBefore.version()))));
            int publisherPid = awaitUniqueDirectWaiter(
                    holderPid, "publisher did not block directly on the held project row");

            Future<Operation<MediaAssetView>> translationFuture = executor.submit(() ->
                    capture(() -> mediaManagement.updateTranslations(
                            assetId, mediaVersionBefore, translationsBefore)));
            int mediaUpdaterPid = awaitUniqueDirectWaiter(
                    publisherPid,
                    "media updater did not block directly on the publisher's asset lock");
            assertThat(directBlockers(publisherPid)).containsExactly(holderPid);
            assertThat(directBlockers(mediaUpdaterPid)).containsExactly(publisherPid);
            verify(mediaChangeListener, never())
                    .onMediaChanged(assetId, MediaChangeType.TRANSLATION_UPDATED);
            holder.commit();

            Operation<PublicationResult> republish =
                    republishFuture.get(30, TimeUnit.SECONDS);
            Operation<MediaAssetView> translation =
                    translationFuture.get(30, TimeUnit.SECONDS);
            assertNoSqlState(republish.failure(), "40P01");
            assertNoSqlState(translation.failure(), "40P01");
            assertThat(republish.failure()).isNull();
            assertThat(translation.failure()).isNull();
            assertThat(republish.result()).isNotNull();
            assertThat(translation.result()).isNotNull();
            verify(mediaChangeListener, times(1))
                    .onMediaChanged(assetId, MediaChangeType.TRANSLATION_UPDATED);

            PublicationRow projectAfter = publishing
                    .find(AggregateType.PROJECT, project.id())
                    .orElseThrow();
            PublicationRow catalogAfter = catalogPointer();
            assertThat(projectAfter.version()).isEqualTo(projectBefore.version() + 1L);
            assertThat(projectAfter.currentRevisionId())
                    .isEqualTo(republish.result().revisionId());
            assertThat(projectAfter.currentRevisionId()).isNotEqualTo(initial.revisionId());
            assertThat(catalogAfter.version()).isEqualTo(catalogBefore.version() + 1L);
            assertThat(catalogAfter.currentRevisionId())
                    .isEqualTo(republish.result().catalogRevisionId());

            ProjectWorkspaceDto finalWorkspace = fixture.project(project.id());
            // Republish clears the dirty flag (+1), then the real media listener marks
            // the workspace dirty after the translation transaction wins the lock (+1).
            assertThat(finalWorkspace.version()).isEqualTo(edited.version() + 2L);
            assertThat(finalWorkspace.publicationDirty()).isTrue();
            assertThat(mediaVersion(assetId)).isEqualTo(mediaVersionBefore + 1L);
            assertThat(translation.result().version()).isEqualTo(mediaVersionBefore + 1L);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<Attempt> publishBehindCatalogHolder(
            List<ProjectWorkspaceDto> projects, long expectedCatalogVersion)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(projects.size());
        List<Future<Attempt>> futures;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            lockCatalog(holder);
            futures = projects.stream()
                    .map(project -> executor.submit(
                            () -> attemptPublication(project, expectedCatalogVersion, start)))
                    .toList();
            start.countDown();
            awaitBlockedBy(holderPid, projects.size());
            holder.commit();

            List<Attempt> attempts = new java.util.ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(30, TimeUnit.SECONDS));
            }
            return List.copyOf(attempts);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Attempt attemptPublication(
            ProjectWorkspaceDto project,
            long expectedCatalogVersion,
            CountDownLatch start) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent publication start gate timed out");
            }
            return new Attempt(
                    project.id(),
                    service.publishProject(new PublishProjectCommand(
                            project.id(), project.version(), 0L, expectedCatalogVersion)),
                    null);
        } catch (Throwable failure) {
            return new Attempt(project.id(), null, failure);
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                        "select pg_catalog.pg_backend_pid()");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("catalog holder has no backend pid");
            }
            return result.getInt(1);
        }
    }

    private static void lockCatalog(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                        select aggregate_id
                        from portfolio.publication
                        where aggregate_type='PROJECT_CATALOG' and aggregate_id=?
                        for update
                        """)) {
            statement.setObject(1, CATALOG_ID);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("catalog pointer does not exist");
                }
            }
        }
    }

    private static void lockProject(Connection connection, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                        select id
                        from portfolio.project
                        where id=?
                        for update
                        """)) {
            statement.setObject(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("project workspace does not exist");
                }
            }
        }
    }

    private void awaitBlockedBy(int holderPid, int expectedWaiters)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            long blocked = migratorJdbc().sql("""
                            with recursive holder_waiters(pid) as (
                                select activity.pid
                                from pg_catalog.pg_stat_activity activity
                                where :holderPid = any(
                                    pg_catalog.pg_blocking_pids(activity.pid))
                                union
                                select activity.pid
                                from pg_catalog.pg_stat_activity activity
                                join holder_waiters blocker
                                  on blocker.pid = any(
                                      pg_catalog.pg_blocking_pids(activity.pid))
                            )
                            select count(*) from holder_waiters
                            """)
                    .param("holderPid", holderPid)
                    .query(Long.class)
                    .single();
            if (blocked >= expectedWaiters) {
                return;
            }
            Thread.sleep(25L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(
                "both publication transactions did not overlap behind the catalog holder");
    }

    private int awaitUniqueDirectWaiter(int blockerPid, String failureMessage)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            List<Integer> waiters = migratorJdbc().sql("""
                            select activity.pid
                            from pg_catalog.pg_stat_activity activity
                            where :blockerPid = any(
                                pg_catalog.pg_blocking_pids(activity.pid))
                            order by activity.pid
                            """)
                    .param("blockerPid", blockerPid)
                    .query(Integer.class)
                    .list();
            if (waiters.size() == 1) {
                return waiters.get(0);
            }
            if (waiters.size() > 1) {
                throw new AssertionError(
                        failureMessage + "; expected one direct waiter but found " + waiters);
            }
            Thread.sleep(25L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(failureMessage);
    }

    private List<Integer> directBlockers(int waiterPid) {
        return migratorJdbc().sql("""
                        select blocker.pid
                        from unnest(pg_catalog.pg_blocking_pids(:waiterPid)) blocker(pid)
                        order by blocker.pid
                        """)
                .param("waiterPid", waiterPid)
                .query(Integer.class)
                .list();
    }

    private ProjectCatalogSnapshotV1 currentCatalogSnapshot(PublicationRow pointer) {
        var revision = publishing.requireRevision(pointer.currentRevisionId());
        assertThat(revision.type()).isEqualTo(AggregateType.PROJECT_CATALOG);
        return codec.decode(revision.json(), ProjectCatalogSnapshotV1.class);
    }

    private PublicationRow catalogPointer() {
        return publishing.find(AggregateType.PROJECT_CATALOG, CATALOG_ID).orElseThrow();
    }

    private long revisionCount(UUID projectId) {
        return jdbc.sql("""
                        select count(*)
                        from portfolio.content_revision
                        where aggregate_type='PROJECT' and aggregate_id=:projectId
                        """)
                .param("projectId", projectId)
                .query(Long.class)
                .single();
    }

    private boolean projectDirty(UUID projectId) {
        return jdbc.sql("""
                        select publication_dirty
                        from portfolio.project
                        where id=:projectId
                        """)
                .param("projectId", projectId)
                .query(Boolean.class)
                .single();
    }

    private long mediaVersion(UUID assetId) {
        return jdbc.sql("""
                        select version
                        from portfolio.media_asset
                        where id=:assetId
                        """)
                .param("assetId", assetId)
                .query(Long.class)
                .single();
    }

    private List<MediaTranslationInput> currentTranslations(UUID assetId) {
        List<MediaTranslationInput> translations = jdbc.sql("""
                        select locale, alt_text, caption, credit, source_url
                        from portfolio.media_translation
                        where asset_id=:assetId
                        order by locale
                        """)
                .param("assetId", assetId)
                .query((result, rowNumber) -> new MediaTranslationInput(
                        result.getString("locale"),
                        result.getString("alt_text"),
                        result.getString("caption"),
                        result.getString("credit"),
                        result.getString("source_url")))
                .list();
        assertThat(translations).hasSize(2);
        return translations;
    }

    private static <T> Operation<T> capture(Callable<T> operation) {
        try {
            return new Operation<>(operation.call(), null);
        } catch (Throwable failure) {
            return new Operation<>(null, failure);
        }
    }

    private static void assertCatalogConflict(Throwable failure) {
        assertCatalogConflictCode(failure, "CATALOG_VERSION_CONFLICT");
    }

    private static void assertCatalogConflictCode(Throwable failure, String code) {
        assertThat(failure)
                .isInstanceOf(DomainException.class)
                .satisfies(error -> {
                    DomainException domain = (DomainException) error;
                    assertThat(domain.code()).isEqualTo(code);
                    assertThat(domain.status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    private static void assertNoSqlState(Throwable failure, String prohibitedState) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql) {
                assertThat(sql.getSQLState()).isNotEqualTo(prohibitedState);
            }
            current = current.getCause();
        }
    }

    private static String uniqueSlug(String prefix) {
        return prefix + '-' + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedAdminConfiguration {
        @Bean
        @Primary
        CurrentAdminProvider catalogConcurrencyCurrentAdmin() {
            return () -> PublishingTestFixture.ADMIN_ID;
        }
    }

    private record Attempt(UUID projectId, PublicationResult result, Throwable failure) { }

    private record Operation<T>(T result, Throwable failure) { }
}
