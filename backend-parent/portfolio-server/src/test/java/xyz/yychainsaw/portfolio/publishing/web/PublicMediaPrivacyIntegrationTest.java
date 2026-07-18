package xyz.yychainsaw.portfolio.publishing.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import xyz.yychainsaw.portfolio.auth.CurrentAdminProvider;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.common.error.GlobalProblemHandler;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;
import xyz.yychainsaw.portfolio.media.domain.StorageProvider;
import xyz.yychainsaw.portfolio.media.storage.StorageLocation;
import xyz.yychainsaw.portfolio.media.storage.StorageRead;
import xyz.yychainsaw.portfolio.media.storage.StorageRouter;
import xyz.yychainsaw.portfolio.media.storage.StorageService;
import xyz.yychainsaw.portfolio.publicapi.PublishedEnvelope;
import xyz.yychainsaw.portfolio.publicapi.PublicProjectDto;
import xyz.yychainsaw.portfolio.publishing.api.ArchiveProjectCommand;
import xyz.yychainsaw.portfolio.publishing.api.PublicationResult;
import xyz.yychainsaw.portfolio.publishing.api.PublishProjectCommand;
import xyz.yychainsaw.portfolio.publishing.application.PublicSnapshotQueryService;
import xyz.yychainsaw.portfolio.publishing.application.PublicationService;
import xyz.yychainsaw.portfolio.publishing.persistence.PublicMediaReferenceRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository;
import xyz.yychainsaw.portfolio.publishing.persistence.PublishingRepository.PublicationRow;
import xyz.yychainsaw.portfolio.publishing.snapshot.AggregateType;
import xyz.yychainsaw.portfolio.publishing.support.PublishingTestFixture;

@SpringBootTest
@Tag("integration")
@ActiveProfiles("test")
@Isolated
@Import({
    PublishingTestFixture.class,
    PublicMediaPrivacyIntegrationTest.FixedAdminConfiguration.class
})
class PublicMediaPrivacyIntegrationTest {
    private static final String CURRENT_VARIANT = "w640";
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-bookworm")
                    .withDatabaseName("portfolio_test")
                    .withUsername("test_owner")
                    .withPassword("test_owner_password")
                    .withInitScript("db/test/00-test-roles.sql");

    static {
        POSTGRES.start();
    }

    @Autowired PublicationService publications;
    @Autowired PublicSnapshotQueryService publicQueries;
    @Autowired PublicMediaReferenceRepository publicMedia;
    @Autowired PublishingRepository publishing;
    @Autowired PublishingTestFixture fixture;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = portfolioJdbcUrl(POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "test_runtime");
        registry.add("spring.datasource.password", () -> "runtime_test_password");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> "test_migrator");
        registry.add("spring.flyway.password", () -> "migrator_test_password");
        registry.add("portfolio.recovery.host", POSTGRES::getHost);
        registry.add("portfolio.recovery.port", POSTGRES::getFirstMappedPort);
        registry.add("portfolio.recovery.database", POSTGRES::getDatabaseName);
        registry.add("portfolio.recovery.username", () -> "test_migrator");
        registry.add("portfolio.recovery.password", () -> "migrator_test_password");
        registry.add("portfolio.security.totp.active-key-version", () -> "1");
        registry.add("portfolio.security.totp.key-ring",
                () -> "1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        registry.add("portfolio.media.local-staging.active-capacity", () -> "3");
        registry.add("portfolio.media.local-staging.scan-entry-ceiling", () -> "64");
        registry.add("portfolio.media.local-staging.reserved-headroom", () -> "16");
        registry.add("server.servlet.session.cookie.secure", () -> "false");
    }

    @BeforeEach
    void ensureRealAdminRow() {
        fixture.ensureAdmin();
    }

    @Test
    void publicProjectRemainsOnItsImmutableRevisionUntilRepublishAndThenArchives()
            throws Exception {
        ProjectWorkspaceDto project = fixture.persistReadyProject();
        project = fixture.editProjectEnglishTitle(project.id(), "Published title A");
        String publicSlug = project.slug();
        boolean archived = false;
        try {
            PublicationRow catalogBefore = catalogPointer();
            PublicationResult first = publications.publishProject(new PublishProjectCommand(
                    project.id(), project.version(), 0L, catalogBefore.version()));

            PublishedEnvelope<PublicProjectDto> initiallyPublic =
                    publicQueries.project(publicSlug, LocaleCode.EN);
            assertThat(initiallyPublic.revisionVersion())
                    .isEqualTo(first.aggregateVersion());
            assertThat(initiallyPublic.checksum()).isEqualTo(first.checksum());
            assertThat(initiallyPublic.data().title()).isEqualTo("Published title A");

            long revisionCountBeforeDraft = projectRevisionCount(project.id());
            PublicationRow pointerBeforeDraft = projectPointer(project.id());
            ProjectWorkspaceDto draft = fixture.editProjectEnglishTitle(
                    project.id(), "Draft title B");

            PublishedEnvelope<PublicProjectDto> whileDraftExists =
                    publicQueries.project(publicSlug, LocaleCode.EN);
            assertThat(whileDraftExists).isEqualTo(initiallyPublic);
            assertThat(whileDraftExists.data().title()).isEqualTo("Published title A");
            assertThat(projectRevisionCount(project.id()))
                    .as("a public read must not create a revision")
                    .isEqualTo(revisionCountBeforeDraft);
            assertThat(projectPointer(project.id()))
                    .as("a public read must not mutate the publication pointer")
                    .isEqualTo(pointerBeforeDraft);
            assertThat(fixture.project(project.id()).version())
                    .as("a public read must not mutate the workspace")
                    .isEqualTo(draft.version());

            PublicationResult second = publications.publishProject(new PublishProjectCommand(
                    project.id(),
                    draft.version(),
                    pointerBeforeDraft.version(),
                    catalogPointer().version()));
            PublishedEnvelope<PublicProjectDto> republished =
                    publicQueries.project(publicSlug, LocaleCode.EN);
            assertThat(republished.revisionVersion())
                    .isEqualTo(second.aggregateVersion());
            assertThat(republished.checksum()).isEqualTo(second.checksum());
            assertThat(republished.checksum()).isNotEqualTo(initiallyPublic.checksum());
            assertThat(republished.data().title()).isEqualTo("Draft title B");

            PublicationRow projectBeforeArchive = projectPointer(project.id());
            publications.archiveProject(new ArchiveProjectCommand(
                    project.id(),
                    projectBeforeArchive.version(),
                    catalogPointer().version()));
            archived = true;

            assertThatThrownBy(() ->
                            publicQueries.project(publicSlug, LocaleCode.EN))
                    .isInstanceOfSatisfying(DomainException.class, failure ->
                            assertThat(failure.code()).isEqualTo("PROJECT_NOT_FOUND"));
        } finally {
            if (!archived) {
                archiveIfStillPublished(project.id());
            }
        }
    }

    @Test
    void committedPointerMoveAndArchiveHonorTheApprovedInFlightBoundary()
            throws Exception {
        CommittedMove move = transactions.execute(status -> {
            MediaFixture first = insertReadyMedia("a".repeat(64));
            MediaFixture second = insertReadyMedia("b".repeat(64));
            UUID aggregateId = UUID.randomUUID();
            UUID firstRevision = insertRevision(aggregateId, 1L);
            UUID secondRevision = insertRevision(aggregateId, 2L);
            insertReference(firstRevision, first.assetId(), CURRENT_VARIANT);
            insertReference(secondRevision, second.assetId(), CURRENT_VARIANT);
            insertPublication(aggregateId, firstRevision);
            return new CommittedMove(
                    aggregateId,
                    first.assetId(),
                    second.assetId(),
                    second.sha256(),
                    secondRevision);
        });
        assertThat(move).isNotNull();

        assertThat(publicMedia.isCurrentlyPublished(
                        move.firstAssetId(), CURRENT_VARIANT))
                .isTrue();
        assertThat(publicMedia.isCurrentlyPublished(
                        move.secondAssetId(), CURRENT_VARIANT))
                .isFalse();

        transactions.executeWithoutResult(status -> {
            int moved = jdbc.sql("""
                            update portfolio.publication
                            set current_revision_id=:revisionId, version=version+1,
                                published_at=clock_timestamp()
                            where aggregate_type='PROJECT'
                              and aggregate_id=:aggregateId
                              and status='PUBLISHED'
                            """)
                    .param("revisionId", move.secondRevisionId())
                    .param("aggregateId", move.aggregateId())
                    .update();
            assertThat(moved).isOne();
        });

        assertThat(publicMedia.isCurrentlyPublished(
                        move.firstAssetId(), CURRENT_VARIANT))
                .isFalse();
        assertThat(publicMedia.isCurrentlyPublished(
                        move.secondAssetId(), CURRENT_VARIANT))
                .isTrue();

        CountDownLatch authorizationObserved = new CountDownLatch(1);
        CountDownLatch archiveCommitted = new CountDownLatch(1);
        PublicMediaReferenceRepository blockingGate =
                mock(PublicMediaReferenceRepository.class);
        when(blockingGate.isCurrentlyPublished(
                        move.secondAssetId(), CURRENT_VARIANT))
                .thenAnswer(invocation -> {
                    boolean allowed = publicMedia.isCurrentlyPublished(
                            move.secondAssetId(), CURRENT_VARIANT);
                    authorizationObserved.countDown();
                    if (!archiveCommitted.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("archive barrier timed out");
                    }
                    return allowed;
                });

        byte[] body = {1, 2, 3, 4};
        String objectKey = "public-privacy/" + move.secondAssetId() + "/w640.png";
        MediaVariantDescriptor descriptor = new MediaVariantDescriptor(
                move.secondAssetId(),
                CURRENT_VARIANT,
                "READY",
                StorageProvider.LOCAL,
                null,
                null,
                objectKey,
                "image/png",
                body.length,
                move.secondSha256(),
                640,
                360);
        MediaQueryService inFlightMedia = mock(MediaQueryService.class);
        StorageRouter inFlightRouter = mock(StorageRouter.class);
        StorageService inFlightStorage = mock(StorageService.class);
        when(inFlightMedia.requireReadyVariant(
                        move.secondAssetId(), CURRENT_VARIANT))
                .thenReturn(descriptor);
        when(inFlightRouter.require(StorageProvider.LOCAL))
                .thenReturn(inFlightStorage);
        when(inFlightStorage.provider()).thenReturn(StorageProvider.LOCAL);
        when(inFlightStorage.location()).thenReturn(
                new StorageLocation(StorageProvider.LOCAL, null, null));
        AtomicInteger closeCount = new AtomicInteger();
        ByteArrayInputStream trackedInput = new ByteArrayInputStream(body) {
            @Override
            public void close() {
                closeCount.incrementAndGet();
            }
        };
        when(inFlightStorage.open(objectKey, Optional.empty())).thenReturn(new StorageRead(
                trackedInput,
                body.length,
                Optional.empty(),
                body.length,
                "image/png",
                move.secondSha256()));

        PublicMediaController inFlightController = new PublicMediaController(
                blockingGate, inFlightMedia, inFlightRouter);
        MockHttpServletResponse inFlightResponse = new MockHttpServletResponse();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Void> inFlight = executor.submit(() -> {
            inFlightController.read(
                    move.secondAssetId(),
                    CURRENT_VARIANT,
                    new HttpHeaders(),
                    inFlightResponse);
            return null;
        });
        try {
            assertThat(authorizationObserved.await(10, TimeUnit.SECONDS))
                    .as("the in-flight request reached the committed authorization point")
                    .isTrue();
            transactions.executeWithoutResult(status -> {
                int archived = jdbc.sql("""
                                update portfolio.publication
                                set status='ARCHIVED', version=version+1,
                                    published_at=clock_timestamp()
                                where aggregate_type='PROJECT'
                                  and aggregate_id=:aggregateId
                                  and status='PUBLISHED'
                                """)
                        .param("aggregateId", move.aggregateId())
                        .update();
                assertThat(archived).isOne();
            });
            archiveCommitted.countDown();
            inFlight.get(10, TimeUnit.SECONDS);
        } finally {
            archiveCommitted.countDown();
            executor.shutdownNow();
        }
        assertThat(inFlightResponse.getStatus()).isEqualTo(200);
        assertThat(inFlightResponse.getContentAsByteArray()).containsExactly(body);
        assertThat(closeCount).hasValue(1);

        MediaQueryService media = mock(MediaQueryService.class);
        StorageRouter storage = mock(StorageRouter.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new PublicMediaController(publicMedia, media, storage))
                .setControllerAdvice(new GlobalProblemHandler())
                .build();
        mvc.perform(get(
                        "/api/public/media/{assetId}/{variant}",
                        move.secondAssetId(),
                        CURRENT_VARIANT)
                        .header(
                                HttpHeaders.IF_NONE_MATCH,
                                '"' + move.secondSha256() + '"'))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
        verifyNoInteractions(media, storage);
    }

    private PublicationRow projectPointer(java.util.UUID projectId) {
        return publishing.find(AggregateType.PROJECT, projectId).orElseThrow();
    }

    private PublicationRow catalogPointer() {
        return publishing.find(
                        AggregateType.PROJECT_CATALOG,
                        PublicationService.PROJECT_CATALOG_ID)
                .orElseThrow();
    }

    private long projectRevisionCount(java.util.UUID projectId) {
        return jdbc.sql("""
                        select count(*)
                        from portfolio.content_revision
                        where aggregate_type='PROJECT' and aggregate_id=:projectId
                        """)
                .param("projectId", projectId)
                .query(Long.class)
                .single();
    }

    private void archiveIfStillPublished(java.util.UUID projectId) {
        publishing.find(AggregateType.PROJECT, projectId)
                .filter(pointer -> "PUBLISHED".equals(pointer.status()))
                .ifPresent(pointer -> publications.archiveProject(new ArchiveProjectCommand(
                        projectId, pointer.version(), catalogPointer().version())));
    }

    private MediaFixture insertReadyMedia(String variantSha256) {
        UUID assetId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.media_asset(
                            id, provider, object_key, original_filename, mime_type,
                            byte_size, width, height, sha256, status)
                        values (:id, 'LOCAL', :objectKey, 'privacy-test.png',
                                'image/png', 4, 640, 360, :sha256, 'READY')
                        """)
                .param("id", assetId)
                .param("objectKey", "public-privacy/" + assetId + "/original.png")
                .param("sha256", digest(assetId))
                .update();
        jdbc.sql("""
                        insert into portfolio.media_variant(
                            id, asset_id, variant_name, format, object_key, mime_type,
                            byte_size, width, height, sha256, status)
                        values (:id, :assetId, 'w640', 'PNG', :objectKey,
                                'image/png', 4, 640, 360, :sha256, 'READY')
                        """)
                .param("id", UUID.randomUUID())
                .param("assetId", assetId)
                .param("objectKey", "public-privacy/" + assetId + "/w640.png")
                .param("sha256", variantSha256)
                .update();
        return new MediaFixture(assetId, variantSha256);
    }

    private UUID insertRevision(UUID aggregateId, long version) {
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio.content_revision(
                            id, aggregate_type, aggregate_id, version,
                            snapshot_schema_version, snapshot, checksum, published_by)
                        values (:id, 'PROJECT', :aggregateId, :version,
                                1, '{}'::jsonb, :checksum, :adminId)
                        """)
                .param("id", revisionId)
                .param("aggregateId", aggregateId)
                .param("version", version)
                .param("checksum", digest(revisionId))
                .param("adminId", PublishingTestFixture.ADMIN_ID)
                .update();
        return revisionId;
    }

    private void insertReference(UUID revisionId, UUID assetId, String variantName) {
        jdbc.sql("""
                        insert into portfolio.revision_media_reference(
                            revision_id, asset_id, variant_name, usage)
                        values (:revisionId, :assetId, :variantName, 'DETAIL')
                        """)
                .param("revisionId", revisionId)
                .param("assetId", assetId)
                .param("variantName", variantName)
                .update();
    }

    private void insertPublication(UUID aggregateId, UUID revisionId) {
        jdbc.sql("""
                        insert into portfolio.publication(
                            aggregate_type, aggregate_id, status, current_revision_id,
                            current_slug, version, published_at)
                        values ('PROJECT', :aggregateId, 'PUBLISHED', :revisionId,
                                null, 1, clock_timestamp())
                        """)
                .param("aggregateId", aggregateId)
                .param("revisionId", revisionId)
                .update();
    }

    private static String digest(UUID value) {
        return value.toString().replace("-", "").repeat(2);
    }

    private static String portfolioJdbcUrl(String jdbcUrl) {
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "currentSchema=portfolio";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedAdminConfiguration {
        @Bean
        @Primary
        CurrentAdminProvider publicPrivacyCurrentAdmin() {
            return () -> PublishingTestFixture.ADMIN_ID;
        }
    }

    private record MediaFixture(UUID assetId, String sha256) { }

    private record CommittedMove(
            UUID aggregateId,
            UUID firstAssetId,
            UUID secondAssetId,
            String secondSha256,
            UUID secondRevisionId) { }
}
