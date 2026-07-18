package xyz.yychainsaw.portfolio.content.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.TaxonomyRepository;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaCopyDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaFinalizationService;
import xyz.yychainsaw.portfolio.media.application.MediaFileInspector;
import xyz.yychainsaw.portfolio.media.application.MediaImportService;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.persistence.MediaAssetRepository;
import xyz.yychainsaw.portfolio.media.staging.LocalStagingPolicyProperties;
import xyz.yychainsaw.portfolio.media.staging.TransactionalLocalStagingObjectCleanup;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationAuthorization;
import xyz.yychainsaw.portfolio.media.storage.LocalPublicationFence;
import xyz.yychainsaw.portfolio.media.storage.LocalStorageService;
import xyz.yychainsaw.portfolio.media.storage.ReservedStagingCleanupResult;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

class PortfolioImportServiceTest {
    private static final Path VALID = Path.of(
            "src/test/resources/import/portfolio-v1-valid.json");
    private static final Path INCOMPLETE = Path.of(
            "src/test/resources/import/portfolio-v1-incomplete-translations.json");
    private static final Path MISMATCHED = Path.of(
            "src/test/resources/import/portfolio-v1-mismatched-media.json");
    private static final Path ASSET_ROOT = Path.of(
            "src/test/resources/import/assets");

    @Test
    void dryRunReportsFrozenWarningsAndNeverTouchesWriteDependencies() throws Exception {
        Harness harness = harness();

        ImportReport report = harness.service().dryRun(
                VALID, ASSET_ROOT, sha256(VALID));

        assertThat(report.committed()).isFalse();
        assertThat(report.hasStructureErrors()).isFalse();
        assertThat(report.projectCount()).isEqualTo(3);
        assertThat(report.mediaCount()).isEqualTo(4);
        assertThat(report.tagCount()).isEqualTo(8);
        assertThat(report.issues())
                .extracting(ImportIssue::path)
                .containsExactly(
                        "identity.email",
                        "portfolioContent.en.contact.emailLabel",
                        "portfolioContent.en.hero.visualLabel",
                        "portfolioContent.en.work.imageNotice",
                        "portfolioContent.zh-CN.contact.emailLabel",
                        "portfolioContent.zh-CN.hero.visualLabel",
                        "portfolioContent.zh-CN.work.imageNotice");
        harness.verifyNoWriteInteractions();
    }

    @Test
    void presentBlankTranslationsRemainExactlyThreeWarnings() throws Exception {
        Harness harness = harness();

        ImportReport report = harness.service().dryRun(
                INCOMPLETE, ASSET_ROOT, sha256(INCOMPLETE));

        assertThat(report.hasStructureErrors()).isFalse();
        assertThat(report.issues())
                .allMatch(issue -> issue.severity()
                        == ImportIssue.Severity.PUBLISH_WARNING)
                .allMatch(issue -> issue.code().equals(
                        "IMPORT_TRANSLATION_INCOMPLETE"))
                .extracting(ImportIssue::path)
                .containsExactly(
                        "heroAsset.alt.en",
                        "portfolioContent.en.hero.headline",
                        "portfolioContent.zh-CN.projects[0].summary");
        harness.verifyNoWriteInteractions();
    }

    @Test
    void mismatchedProjectMediaReturnsStructureErrorsWithoutAnyWriteAttempt()
            throws Exception {
        Harness harness = harness();

        ImportReport report = harness.service().commit(
                MISMATCHED, ASSET_ROOT, sha256(MISMATCHED));

        assertThat(report.committed()).isFalse();
        assertThat(report.hasStructureErrors()).isTrue();
        assertThat(report.issues())
                .extracting(ImportIssue::code)
                .contains("IMPORT_PROJECT_MEDIA_MISMATCH");
        harness.verifyNoWriteInteractions();
    }

    @Test
    void corruptMediaIsRejectedBeforeAnyDomainWrite(@TempDir Path temporaryDirectory)
            throws Exception {
        Path assets = copyAssetFixture(temporaryDirectory);
        Files.writeString(
                assets.resolve("images/ue-environment-study.jpg"),
                "this is not a JPEG");
        Harness harness = harness();

        ImportReport report = harness.service().commit(VALID, assets, sha256(VALID));

        assertThat(report.committed()).isFalse();
        assertThat(report.hasStructureErrors()).isTrue();
        assertThat(report.issues())
                .extracting(ImportIssue::path, ImportIssue::code)
                .contains(org.assertj.core.groups.Tuple.tuple(
                        "projectAssets[0].image", "IMPORT_ASSET_PATH_INVALID"));
        harness.verifyNoWriteInteractions();
    }

    @Test
    void uppercaseHttpsSourceIsRejectedBeforeAnyDomainWrite(
            @TempDir Path temporaryDirectory) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode root = (ObjectNode) objectMapper.readTree(VALID.toFile());
        ((ObjectNode) root.get("heroAsset"))
                .put("sourceUrl", "HTTPS://example.com/portfolio-source");
        Path input = temporaryDirectory.resolve("uppercase-https.json");
        objectMapper.writeValue(input.toFile(), root);
        Harness harness = harness();

        ImportReport report = harness.service().commit(input, ASSET_ROOT, sha256(input));

        assertThat(report.committed()).isFalse();
        assertThat(report.hasStructureErrors()).isTrue();
        assertThat(report.issues())
                .extracting(ImportIssue::path, ImportIssue::code)
                .contains(org.assertj.core.groups.Tuple.tuple(
                        "heroAsset.sourceUrl", "IMPORT_JSON_INVALID"));
        harness.verifyNoWriteInteractions();
    }

    @Test
    void commitRejectsAnAmbientTransactionBeforeReadingOrWriting() throws Exception {
        Harness harness = harness();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> harness.service().commit(
                            VALID, ASSET_ROOT, sha256(VALID)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PORTFOLIO_IMPORT_TRANSACTION_FORBIDDEN")
                    .hasNoCause();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        harness.verifyNoWriteInteractions();
    }

    @Test
    void mapperProducesStableCompleteWorkspaceFromFreshMediaBindings() throws Exception {
        PortfolioImportV1 payload = new ObjectMapper()
                .findAndRegisterModules()
                .readValue(VALID.toFile(), PortfolioImportV1.class);
        LinkedHashMap<String, PortfolioImportMapper.ReadyMedia> media =
                new LinkedHashMap<>();
        media.put(payload.heroAsset().image(), descriptor(
                "31000000-0000-4000-8000-000000000001"));
        for (int index = 0; index < payload.projectAssets().size(); index++) {
            media.put(payload.projectAssets().get(index).image(), descriptor(
                    "31000000-0000-4000-8000-00000000000" + (index + 2)));
        }

        PortfolioImportMapper.MappedImport mapped =
                new PortfolioImportMapper().map(payload, media);

        assertThat(mapped.mediaCount()).isEqualTo(4);
        assertThat(mapped.tagCount()).isEqualTo(8);
        assertThat(mapped.site().siteId()).isEqualTo(SiteWorkspaceDto.SITE_ID);
        assertThat(mapped.site().version()).isEqualTo(1);
        assertThat(mapped.site().hero().version()).isZero();
        assertThat(mapped.site().hero().id()).isEqualTo(PortfolioImportSemantics.stableId(
                "hero", SiteWorkspaceDto.SITE_ID.toString()));
        assertThat(mapped.site().identity().get(LocaleCode.ZH_CN))
                .isEqualTo(new SiteWorkspaceDto.IdentityCopy("易嘉轩", "Jiaxuan Yi"));
        assertThat(mapped.site().identity().get(LocaleCode.EN))
                .isEqualTo(new SiteWorkspaceDto.IdentityCopy("Jiaxuan Yi", "易嘉轩"));
        assertThat(mapped.site().navigation())
                .extracting(SiteWorkspaceDto.NavigationItem::target)
                .containsExactly("about", "work", "roadmap", "contact");
        assertThat(mapped.site().facts())
                .extracting(SiteWorkspaceDto.ProfileFact::externalKey)
                .containsExactly("fact-0", "fact-1", "fact-2", "fact-3");
        assertThat(mapped.site().profileSkills())
                .extracting(SiteWorkspaceDto.ProfileSkill::externalKey)
                .containsExactly(
                        "skill-0", "skill-1", "skill-2",
                        "skill-3", "skill-4", "skill-5");
        assertThat(mapped.tags())
                .extracting(value -> value.normalizedKey())
                .containsExactly(
                        "ue5", "blueprint", "level-design", "c++",
                        "gameplay", "devlog", "git-+-lfs", "profiling");
        assertThat(mapped.projects())
                .extracting(value -> value.externalKey())
                .containsExactly(
                        "ue-environment-study", "gameplay-prototype", "development-log");
        assertThat(mapped.projects())
                .allSatisfy(project -> {
                    assertThat(project.version()).isZero();
                    assertThat(project.publicationDirty()).isTrue();
                    assertThat(project.media()).hasSize(1);
                    assertThat(project.skills()).isEmpty();
                    assertThat(project.blocks()).isEmpty();
                    assertThat(project.translations()).containsKeys(
                            LocaleCode.ZH_CN, LocaleCode.EN);
                });
    }

    private static PortfolioImportMapper.ReadyMedia descriptor(String id) {
        return new PortfolioImportMapper.ReadyMedia(
                new MediaAssetDescriptor(
                        UUID.fromString(id),
                        "READY",
                        "image/jpeg",
                        1,
                        "0".repeat(64),
                        Map.of(),
                        List.of()),
                List.of());
    }

    private static Harness harness() {
        PortfolioImportMapper mapper = mock(PortfolioImportMapper.class);
        MediaImportService imports = mock(MediaImportService.class);
        MediaFinalizationService finalization = mock(MediaFinalizationService.class);
        MediaQueryService queries = mock(MediaQueryService.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        SiteWorkspaceRepository sites = mock(SiteWorkspaceRepository.class);
        TaxonomyRepository taxonomy = mock(TaxonomyRepository.class);
        ProjectWorkspaceRepository projects = mock(ProjectWorkspaceRepository.class);
        PortfolioImportService service = new PortfolioImportService(
                new PortfolioImportReader(new ObjectMapper().findAndRegisterModules()),
                new PortfolioImportValidator(new MediaFileInspector()),
                mapper,
                imports,
                finalization,
                queries,
                transactions,
                sites,
                taxonomy,
                projects);
        return new Harness(
                service,
                mapper,
                imports,
                finalization,
                queries,
                transactions,
                sites,
                taxonomy,
                projects);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static Path copyAssetFixture(Path temporaryDirectory) throws Exception {
        Path targetRoot = temporaryDirectory.resolve("assets");
        try (var paths = Files.walk(ASSET_ROOT)) {
            for (Path source : paths.toList()) {
                Path target = targetRoot.resolve(ASSET_ROOT.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        return targetRoot;
    }

    private record Harness(
            PortfolioImportService service,
            PortfolioImportMapper mapper,
            MediaImportService imports,
            MediaFinalizationService finalization,
            MediaQueryService queries,
            TransactionTemplate transactions,
            SiteWorkspaceRepository sites,
            TaxonomyRepository taxonomy,
            ProjectWorkspaceRepository projects) {
        private void verifyNoWriteInteractions() {
            verifyNoInteractions(
                    mapper,
                    imports,
                    finalization,
                    queries,
                    transactions,
                    sites,
                    taxonomy,
                    projects);
        }
    }
}

@SpringBootTest
@Import(PortfolioImportServiceLifecycleTest.ImportCapacityConfiguration.class)
@ContextConfiguration(
        initializers = PortfolioImportServiceLifecycleTest.ImportPolicyInitializer.class)
@Isolated
class PortfolioImportServiceLifecycleTest extends PostgresIntegrationTestBase {
    private static final Path VALID = Path.of(
            "src/test/resources/import/portfolio-v1-valid.json");
    private static final Path ASSET_ROOT = Path.of(
            "src/test/resources/import/assets");
    private static final Path STORAGE_ROOT = isolatedStorageRoot();
    private static final AtomicReference<Optional<String>> SHARED_VOLUME_ID =
            new AtomicReference<>();

    @Autowired PortfolioImportService service;
    @MockitoSpyBean MediaFinalizationService finalization;
    @MockitoSpyBean MediaQueryService queries;
    @MockitoSpyBean MediaAssetRepository mediaAssets;
    @MockitoSpyBean TransactionalLocalStagingObjectCleanup rollbackCleanup;
    @MockitoSpyBean LocalPublicationFence publicationFence;
    @MockitoSpyBean LocalStorageService localStorage;

    private final JdbcClient owner = migratorJdbc();

    @DynamicPropertySource
    static void isolateLocalStorage(DynamicPropertyRegistry registry) {
        registry.add("portfolio.storage.local.root", STORAGE_ROOT::toString);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ImportCapacityConfiguration {
        @Bean
        @Primary
        LocalStagingPolicyProperties importerStagingPolicy() {
            return new LocalStagingPolicyProperties(8, 80, 16);
        }
    }

    static final class ImportPolicyInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            JdbcClient owner = migratorJdbc();
            boolean policyExists = owner.sql("""
                            select to_regclass(
                                'portfolio.local_staging_policy'
                            ) is not null
                            """)
                    .query(Boolean.class)
                    .single();
            if (SHARED_VOLUME_ID.get() == null) {
                Optional<String> sharedVolumeId = policyExists
                        ? owner.sql("""
                                        select volume_id
                                        from portfolio.local_staging_policy
                                        where singleton_key=1
                                        """)
                                .query(String.class)
                                .optional()
                        : Optional.empty();
                SHARED_VOLUME_ID.compareAndSet(null, sharedVolumeId);
            }
            owner.sql("""
                            do $$
                            begin
                                if to_regclass('portfolio.local_staging_policy') is not null then
                                    update portfolio.local_staging_policy
                                    set active_capacity=8,
                                        scan_entry_ceiling=80,
                                        worst_case_entries_per_reservation=6,
                                        reserved_headroom=16,
                                        volume_id=null
                                    where singleton_key=1;
                                end if;
                            end
                            $$
                            """)
                    .update();
        }
    }

    @BeforeEach
    void resetWorkspace() {
        clearWorkspace();
    }

    @AfterEach
    void cleanWorkspace() {
        clearWorkspace();
    }

    @AfterAll
    static void restoreSharedStagingPolicy() {
        Optional<String> sharedVolumeId = SHARED_VOLUME_ID.get();
        assertThat(sharedVolumeId).isNotNull();
        int restored = migratorJdbc().sql("""
                        update portfolio.local_staging_policy
                        set active_capacity=3,
                            scan_entry_ceiling=64,
                            worst_case_entries_per_reservation=6,
                            reserved_headroom=16,
                            volume_id=:volumeId
                        where singleton_key=1
                        """)
                .param("volumeId", sharedVolumeId.orElse(null), Types.VARCHAR)
                .update();
        assertThat(restored).isOne();
    }

    @Test
    void finalizationFailureLeavesNoContentAndRetryReusesStableMediaIds()
            throws Exception {
        doThrow(new IllegalStateException("forced finalization failure"))
                .when(finalization)
                .finalizeAsset(any(UUID.class));

        Throwable firstFailure = catchThrowable(() -> service.commit(
                VALID, ASSET_ROOT, sha256(VALID)));
        assertThat(firstFailure)
                .as(
                        "media=%s reservations=%s",
                        count("portfolio.media_asset"),
                        count("portfolio.local_staging_reservation"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced finalization failure");

        List<UUID> residueIds = mediaIds();
        assertThat(residueIds).hasSize(4);
        assertThat(owner.sql("""
                        select status
                        from portfolio.media_asset
                        order by id
                        """)
                .query(String.class)
                .list()).containsOnly("PROCESSING");
        assertNoContent();

        reset(finalization);
        ImportReport report = service.commit(VALID, ASSET_ROOT, sha256(VALID));

        assertThat(report.committed()).isTrue();
        assertThat(report.projectCount()).isEqualTo(3);
        assertThat(report.mediaCount()).isEqualTo(4);
        assertThat(report.tagCount()).isEqualTo(8);
        assertThat(mediaIds()).containsExactlyElementsOf(residueIds);
        assertThat(owner.sql("""
                        select status
                        from portfolio.media_asset
                        order by id
                        """)
                .query(String.class)
                .list()).containsOnly("READY");
        assertThat(count("portfolio.media_variant")).isGreaterThanOrEqualTo(4);
        assertThat(count("portfolio.project")).isEqualTo(3);
        assertThat(count("portfolio.tag")).isEqualTo(8);
        assertThat(owner.sql("""
                        select version
                        from portfolio.site_profile
                        where id=:siteId
                        """)
                .param("siteId", SiteWorkspaceDto.SITE_ID)
                .query(Long.class)
                .single()).isOne();

        assertThatThrownBy(() -> service.commit(
                        VALID, ASSET_ROOT, sha256(VALID)))
                .isInstanceOfSatisfying(DomainException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                "IMPORT_ALREADY_COMPLETED"));
        assertThat(mediaIds()).containsExactlyElementsOf(residueIds);
    }

    @Test
    void optimisticGuardRejectsAnyExistingTagOrSkillBeforeMediaTransactions()
            throws Exception {
        for (String table : List.of("tag", "skill")) {
            clearWorkspace();
            owner.sql("insert into portfolio." + table
                            + "(id, normalized_key) values (:id, :key)")
                    .param("id", UUID.randomUUID())
                    .param("key", "existing-" + table)
                    .update();

            assertThatThrownBy(() -> service.commit(
                            VALID, ASSET_ROOT, sha256(VALID)))
                    .isInstanceOfSatisfying(DomainException.class, failure ->
                            assertThat(failure.code()).isEqualTo(
                                    "IMPORT_ALREADY_COMPLETED"));
            assertThat(count("portfolio.media_asset")).isZero();
            assertNoContent();
        }
    }

    @Test
    void localPersistFailureRetainsDurableCleanupWhenAfterCompletionDefers()
            throws Exception {
        AtomicReference<Object> cleanupOutcome = new AtomicReference<>();
        AtomicReference<Object> storageCleanupOutcome = new AtomicReference<>();
        AtomicReference<List<Boolean>> cleanupTransactionState = new AtomicReference<>();
        AtomicReference<LocalPublicationAuthorization> authorization =
                new AtomicReference<>();
        doAnswer(invocation -> {
                    LocalPublicationAuthorization acquired =
                            (LocalPublicationAuthorization) invocation.callRealMethod();
                    LocalPublicationAuthorization observed = spy(acquired);
                    authorization.set(observed);
                    return observed;
                })
                .when(publicationFence)
                .acquire(any());
        doAnswer(invocation -> {
                    storageCleanupOutcome.set(
                            ReservedStagingCleanupResult.DEFERRED);
                    return ReservedStagingCleanupResult.DEFERRED;
                })
                .when(localStorage)
                .cleanupReservedStaging(any(), any(), any(), any(), any());
        doAnswer(invocation -> {
                    cleanupTransactionState.set(List.of(
                            TransactionSynchronizationManager
                                    .isActualTransactionActive(),
                            TransactionSynchronizationManager
                                    .isSynchronizationActive()));
                    try {
                        Object result = invocation.callRealMethod();
                        cleanupOutcome.set(result);
                        return result;
                    } catch (Throwable failure) {
                        cleanupOutcome.set(failure);
                        throw failure;
                    }
                })
                .when(rollbackCleanup)
                .cleanupKnownRollback(any(), any());
        doThrow(new IllegalStateException("forced media persistence failure"))
                .when(mediaAssets)
                .insertProcessing(any());

        assertThatThrownBy(() -> service.commit(
                        VALID, ASSET_ROOT, sha256(VALID)))
                .isInstanceOfSatisfying(DomainException.class, failure ->
                        assertThat(failure.code()).isEqualTo("MEDIA_IMPORT_FAILED"));

        assertThat(count("portfolio.media_asset")).isZero();
        assertThat(cleanupOutcome.get()).isEqualTo(false);
        assertThat(storageCleanupOutcome.get())
                .isEqualTo(ReservedStagingCleanupResult.DEFERRED);
        assertThat(cleanupTransactionState.get()).containsExactly(true, false);
        assertThat(count("portfolio.local_staging_reservation")).isOne();
        assertThat(owner.sql("""
                        select count(*)
                        from portfolio.local_staging_reservation r
                        join portfolio.background_job j on j.id=r.cleanup_job_id
                        where j.job_type='CLEAN_LOCAL_STAGING_OBJECT'
                          and j.status='PENDING'
                          and j.next_run_at=r.cleanup_after
                          and j.payload->>'assetId'=r.asset_id::text
                          and j.payload->>'sha256'=r.sha256
                          and j.payload->>'mimeType'=r.mime_type
                          and j.payload->>'generation'=r.generation::text
                        """)
                .query(Long.class)
                .single()).isOne();
        LocalPublicationAuthorization acquired = authorization.get();
        assertThat(acquired).isNotNull();
        org.mockito.InOrder completionOrder =
                org.mockito.Mockito.inOrder(rollbackCleanup, acquired);
        completionOrder.verify(rollbackCleanup).cleanupKnownRollback(any(), any());
        completionOrder.verify(acquired).close();
        assertNoContent();
    }

    @Test
    void finalContentTransactionRefusesStillProcessingMedia() throws Exception {
        doNothing().when(finalization).finalizeAsset(any(UUID.class));

        assertThatThrownBy(() -> service.commit(
                        VALID, ASSET_ROOT, sha256(VALID)))
                .isInstanceOfSatisfying(DomainException.class, failure ->
                        assertThat(failure.code()).isEqualTo("MEDIA_NOT_READY"));

        assertThat(count("portfolio.media_asset")).isEqualTo(4);
        assertThat(owner.sql("select status from portfolio.media_asset")
                .query(String.class)
                .list()).containsOnly("PROCESSING");
        assertNoContent();
    }

    @Test
    void finalContentTransactionRefusesReadyAssetWithoutVariants() throws Exception {
        doAnswer(invocation -> {
                    MediaAssetDescriptor asset =
                            (MediaAssetDescriptor) invocation.callRealMethod();
                    return new MediaAssetDescriptor(
                            asset.assetId(),
                            asset.status(),
                            asset.mimeType(),
                            asset.byteSize(),
                            asset.sha256(),
                            asset.copyByLocale(),
                            List.of());
                })
                .when(queries)
                .requireReadyAsset(any(UUID.class));

        assertThatThrownBy(() -> service.commit(
                        VALID, ASSET_ROOT, sha256(VALID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PORTFOLIO_IMPORT_MEDIA_NOT_READY");

        assertThat(count("portfolio.media_asset")).isEqualTo(4);
        assertNoContent();
    }

    @Test
    void finalContentTransactionRefusesFreshMetadataDrift() throws Exception {
        doAnswer(invocation -> {
                    MediaAssetDescriptor asset =
                            (MediaAssetDescriptor) invocation.callRealMethod();
                    Map<String, MediaCopyDescriptor> drifted =
                            new java.util.LinkedHashMap<>(asset.copyByLocale());
                    MediaCopyDescriptor english = drifted.get("en");
                    drifted.put(
                            "en",
                            new MediaCopyDescriptor(
                                    english.alt() + " drift",
                                    english.caption(),
                                    english.credit(),
                                    english.sourceUrl()));
                    return new MediaAssetDescriptor(
                            asset.assetId(),
                            asset.status(),
                            asset.mimeType(),
                            asset.byteSize(),
                            asset.sha256(),
                            drifted,
                            asset.variants());
                })
                .when(queries)
                .requireReadyAsset(any(UUID.class));

        assertThatThrownBy(() -> service.commit(
                        VALID, ASSET_ROOT, sha256(VALID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PORTFOLIO_IMPORT_MEDIA_NOT_READY");

        assertThat(count("portfolio.media_asset")).isEqualTo(4);
        assertNoContent();
    }

    @Test
    void concurrentFirstCommitsShareFourMediaAndOnlyOneWinsTheSiteCas()
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent import start timed out");
                    }
                    try {
                        return service.commit(VALID, ASSET_ROOT, sha256(VALID));
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(120, TimeUnit.SECONDS));
            }

            assertThat(outcomes)
                    .filteredOn(ImportReport.class::isInstance)
                    .singleElement()
                    .satisfies(value -> assertThat(((ImportReport) value).committed())
                            .isTrue());
            assertThat(outcomes)
                    .filteredOn(DomainException.class::isInstance)
                    .singleElement()
                    .satisfies(value -> assertThat(((DomainException) value).code())
                            .isEqualTo("IMPORT_ALREADY_COMPLETED"));
            assertThat(count("portfolio.media_asset")).isEqualTo(4);
            assertThat(mediaIds()).hasSize(4);
            assertThat(count("portfolio.project")).isEqualTo(3);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<UUID> mediaIds() {
        return owner.sql("select id from portfolio.media_asset order by id")
                .query(UUID.class)
                .list();
    }

    private void assertNoContent() {
        assertThat(count("portfolio.project")).isZero();
        assertThat(owner.sql("""
                        select version
                        from portfolio.site_profile
                        where id=:siteId
                        """)
                .param("siteId", SiteWorkspaceDto.SITE_ID)
                .query(Long.class)
                .single()).isZero();
        assertThat(count("portfolio.hero_section")).isZero();
    }

    private long count(String table) {
        return owner.sql("select count(*) from " + table)
                .query(Long.class)
                .single();
    }

    private void clearWorkspace() {
        owner.sql("delete from portfolio.project").update();
        owner.sql("delete from portfolio.tag").update();
        owner.sql("delete from portfolio.skill").update();
        owner.sql("delete from portfolio.resume_document").update();
        owner.sql("delete from portfolio.hero_section").update();
        owner.sql("delete from portfolio.site_navigation_item").update();
        owner.sql("delete from portfolio.profile_fact").update();
        owner.sql("delete from portfolio.profile_skill").update();
        owner.sql("delete from portfolio.roadmap_stage").update();
        owner.sql("delete from portfolio.site_profile_translation").update();
        owner.sql("delete from portfolio.site_seo_translation").update();
        owner.sql("delete from portfolio.site_accessibility_copy_translation").update();
        owner.sql("delete from portfolio.about_section_translation").update();
        owner.sql("delete from portfolio.work_section_translation").update();
        owner.sql("delete from portfolio.contact_section_translation").update();
        owner.sql("delete from portfolio.privacy_notice_translation").update();
        owner.sql("delete from portfolio.social_link").update();
        owner.sql("delete from portfolio.roadmap_header_translation").update();
        owner.sql("""
                        update portfolio.site_profile
                        set monogram='', email='', version=0,
                            updated_at=timestamp with time zone '2000-01-01 00:00:00+00'
                        where id=:siteId
                        """)
                .param("siteId", SiteWorkspaceDto.SITE_ID)
                .update();
        owner.sql("delete from portfolio.local_staging_reservation").update();
        owner.sql("delete from portfolio.media_translation").update();
        owner.sql("delete from portfolio.media_variant").update();
        owner.sql("delete from portfolio.media_asset").update();
        owner.sql("delete from portfolio.background_job").update();
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static Path isolatedStorageRoot() {
        String configured = System.getProperty("portfolio.storage.local.root");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("integration-test storage root is required");
        }
        return Path.of(configured)
                .toAbsolutePath()
                .normalize()
                .resolveSibling("portfolio-import-lifecycle-media");
    }
}
