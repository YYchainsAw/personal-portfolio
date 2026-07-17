package xyz.yychainsaw.portfolio.content.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.ContentBlockDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.support.ContentPersistenceFixtures;
import xyz.yychainsaw.portfolio.support.PostgresIntegrationTestBase;

@SpringBootTest
class ContentWorkspaceRepositoryTest extends PostgresIntegrationTestBase {
    @Autowired SiteWorkspaceRepository sites;
    @Autowired ProjectWorkspaceRepository projects;
    @Autowired TaxonomyRepository taxonomies;

    private final JdbcClient owner = migratorJdbc();

    @BeforeEach
    void resetSiteWorkspace() {
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
        owner.sql("delete from portfolio.media_asset where object_key like 'content-persistence/%'").update();
    }

    @Test
    void normalizedWorkspaceMapperLoadsAndFixedSiteIsReadable() {
        SiteWorkspaceDto workspace = sites.require();
        assertThat(workspace.siteId()).isEqualTo(SiteWorkspaceDto.SITE_ID);
        assertThat(workspace.version()).isZero();
    }

    @Test
    void siteRoundTripsEveryV7FieldIncludingPresentHeroMediaAndResume() {
        insertReadyAsset(ContentPersistenceFixtures.HERO_ASSET_ID, "hero.png");
        insertReadyAsset(ContentPersistenceFixtures.RESUME_ASSET_ID, "resume.pdf");
        SiteWorkspaceDto input = ContentPersistenceFixtures.siteWithMedia(0L);

        sites.replace(input, 0L);

        assertThat(sites.require())
                .isEqualTo(ContentPersistenceFixtures.withVersion(input, 1L));
    }

    @Test
    void absentHeroMediaRoundTripsAsTheAuthoritativeAllNullTuple() {
        SiteWorkspaceDto input = ContentPersistenceFixtures.siteWithoutMedia(0L);

        sites.replace(input, 0L);

        SiteWorkspaceDto actual = sites.require();
        assertThat(actual.hero().mediaAssetId()).isNull();
        assertThat(actual.hero().objectPosition()).isNull();
        assertThat(actual.hero().credit()).isNull();
        assertThat(actual.hero().sourceUrl()).isNull();
        assertThat(actual)
                .isEqualTo(ContentPersistenceFixtures.withVersion(input, 1L));
    }

    @Test
    void partialHeroMediaTupleIsRejectedBeforeTheRootCas() {
        SiteWorkspaceDto input = ContentPersistenceFixtures.siteWithoutMedia(0L);
        SiteWorkspaceDto.Hero hero = input.hero();
        SiteWorkspaceDto invalid = ContentPersistenceFixtures.withHero(
                input,
                new SiteWorkspaceDto.Hero(
                        hero.id(), hero.version(), null, "50% 50%", null, null, hero.copy()));

        assertThatThrownBy(() -> sites.replace(invalid, 0L))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("CONTENT_WORKSPACE_INVALID");
                    assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        assertThat(sites.require().version()).isZero();
    }

    @Test
    void staleSiteCasLeavesThePreviouslyCommittedChildrenUntouched() {
        SiteWorkspaceDto first = ContentPersistenceFixtures.siteWithoutMedia(0L);
        sites.replace(first, 0L);
        SiteWorkspaceDto committed = sites.require();
        SiteWorkspaceDto stale = ContentPersistenceFixtures.withMonogram(first, "OLD");

        assertThatThrownBy(() -> sites.replace(stale, 0L))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("CONTENT_VERSION_CONFLICT");
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.fieldErrors()).isEqualTo(
                            Map.of("version", "workspace was changed by another request"));
                });
        assertThat(sites.require()).isEqualTo(committed);
    }

    @Test
    void rejectsAClientControlledSiteIdentityWithoutChangingTheFixedRoot() {
        SiteWorkspaceDto source = ContentPersistenceFixtures.siteWithoutMedia(0L);
        SiteWorkspaceDto invalid = new SiteWorkspaceDto(
                java.util.UUID.fromString("81000000-0000-4000-8000-000000000099"),
                source.version(), source.monogram(), source.email(), source.identity(), source.seo(),
                source.accessibility(), source.navigation(), source.hero(), source.about(), source.facts(),
                source.profileSkills(), source.work(), source.roadmap(), source.contact(), source.privacy(),
                source.socialLinks(), source.resumes());

        assertThatThrownBy(() -> sites.replace(invalid, 0L))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONTENT_IDENTITY_MISMATCH"));
        assertThat(sites.require().version()).isZero();
    }

    @Test
    void projectRoundTripsEveryV7FieldAndAllNinePayloadsThenUpdatesByCas() {
        seedTaxonomy();
        ContentPersistenceFixtures.PROJECT_ASSET_IDS.forEach(
                id -> insertReadyAsset(id, id + ".png"));
        ProjectWorkspaceDto input = ContentPersistenceFixtures.projectWithAllPayloads();

        projects.insert(input);
        assertThat(projects.require(input.id())).isEqualTo(input);

        ProjectWorkspaceDto changed = ContentPersistenceFixtures.withEnglishProjectTitle(
                input, "Updated complete project");
        projects.replace(changed, 0L);

        assertThat(projects.require(input.id())).isEqualTo(
                ContentPersistenceFixtures.withProjectVersion(changed, 1L, true));
    }

    @Test
    void projectMediaAcrossUsagesLoadsBySortOrder() {
        UUID detailAssetId = UUID.fromString("85000000-0000-4000-8000-000000000001");
        UUID coverAssetId = UUID.fromString("85000000-0000-4000-8000-000000000002");
        insertReadyAsset(detailAssetId, "ordered-detail.png");
        insertReadyAsset(coverAssetId, "ordered-cover.png");
        ProjectWorkspaceDto source = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "ordered-project-media", 0, 0L);
        ProjectWorkspaceDto input = new ProjectWorkspaceDto(
                source.id(), source.externalKey(), source.slug(), source.number(), source.sortOrder(),
                source.featured(), source.visible(), source.publicationDirty(), source.version(),
                source.translations(), source.tags(), source.skills(),
                List.of(
                        projectMedia(detailAssetId, "DETAIL", 0),
                        projectMedia(coverAssetId, "COVER", 5)),
                source.blocks());

        projects.insert(input);

        assertThat(projects.require(input.id()).media()).isEqualTo(input.media());
    }

    @Test
    void externalDownloadTargetRoundTripsAsTheFrozenXorContract() {
        ProjectWorkspaceDto source = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "external-download", 0, 0L);
        ContentBlockDto block = new ContentBlockDto(
                UUID.fromString("84000000-0000-4000-8000-000000000091"),
                0,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                new ContentBlockDto.DownloadPayload(
                        null,
                        URI.create("https://example.test/downloads/build.zip"),
                        Map.of(
                                LocaleCode.ZH_CN,
                                new ContentBlockDto.ActionCopy("Download", "External build"),
                                LocaleCode.EN,
                                new ContentBlockDto.ActionCopy("Download", "External build"))));
        ProjectWorkspaceDto input = new ProjectWorkspaceDto(
                source.id(), source.externalKey(), source.slug(), source.number(), source.sortOrder(),
                source.featured(), source.visible(), source.publicationDirty(), source.version(),
                source.translations(), source.tags(), source.skills(), source.media(), List.of(block));

        projects.insert(input);

        assertThat(projects.require(input.id())).isEqualTo(input);

        ContentBlockDto invalidBlock = new ContentBlockDto(
                block.id(), block.sortOrder(), block.visible(), block.width(), block.alignment(),
                block.emphasis(), block.columns(), new ContentBlockDto.DownloadPayload(
                        UUID.fromString("83000000-0000-4000-8000-000000000097"),
                        URI.create("https://example.test/downloads/ambiguous.zip"),
                        ((ContentBlockDto.DownloadPayload) block.payload()).copy()));
        ProjectWorkspaceDto ambiguous = new ProjectWorkspaceDto(
                input.id(), input.externalKey(), input.slug(), input.number(), input.sortOrder(),
                input.featured(), input.visible(), input.publicationDirty(), input.version(),
                input.translations(), input.tags(), input.skills(), input.media(), List.of(invalidBlock));
        assertThatThrownBy(() -> projects.replace(ambiguous, 0L))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONTENT_WORKSPACE_INVALID"));
        assertThat(projects.require(input.id())).isEqualTo(input);
    }

    @Test
    void actionReadsRejectTargetOrOpenNewTabRowsThatViolateThePayloadContract() {
        ProjectWorkspaceDto source = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "action-corruption", 0, 0L);
        projects.insert(source);
        UUID downloadBlock = UUID.fromString("84000000-0000-4000-8000-000000000092");
        owner.sql("""
                        insert into portfolio.project_content_block(id,project_id,block_type,sort_order)
                        values (:blockId,:projectId,'DOWNLOAD',0)
                        """)
                .param("blockId", downloadBlock)
                .param("projectId", source.id())
                .update();
        owner.sql("""
                        insert into portfolio.content_block_action(
                            block_id,action_type,target_type,url,open_new_tab)
                        values (:blockId,'DOWNLOAD','EXTERNAL','https://example.test/file.zip',false)
                        """)
                .param("blockId", downloadBlock)
                .update();

        assertPersistenceCorrupt(() -> projects.require(source.id()));

        owner.sql("delete from portfolio.project_content_block where id=:blockId")
                .param("blockId", downloadBlock)
                .update();
        UUID assetId = UUID.fromString("83000000-0000-4000-8000-000000000098");
        insertReadyAsset(assetId, "link-target.png");
        UUID linkBlock = UUID.fromString("84000000-0000-4000-8000-000000000093");
        owner.sql("""
                        insert into portfolio.project_content_block(id,project_id,block_type,sort_order)
                        values (:blockId,:projectId,'LINK',0)
                        """)
                .param("blockId", linkBlock)
                .param("projectId", source.id())
                .update();
        owner.sql("""
                        insert into portfolio.content_block_action(
                            block_id,action_type,target_type,media_asset_id,open_new_tab)
                        values (:blockId,'LINK','MEDIA',:assetId,true)
                        """)
                .param("blockId", linkBlock)
                .param("assetId", assetId)
                .update();

        assertPersistenceCorrupt(() -> projects.require(source.id()));

        owner.sql("delete from portfolio.project_content_block where id=:blockId")
                .param("blockId", linkBlock)
                .update();
        UUID mismatchedBlock = UUID.fromString("84000000-0000-4000-8000-000000000097");
        owner.sql("""
                        insert into portfolio.project_content_block(id,project_id,block_type,sort_order)
                        values (:blockId,:projectId,'MARKDOWN',0)
                        """)
                .param("blockId", mismatchedBlock)
                .param("projectId", source.id())
                .update();
        owner.sql("""
                        insert into portfolio.content_block_video(block_id,provider,url)
                        values (:blockId,'YOUTUBE','https://example.test/not-markdown')
                        """)
                .param("blockId", mismatchedBlock)
                .update();

        assertPersistenceCorrupt(() -> projects.require(source.id()));
    }

    @ParameterizedTest(name = "stored {0} payload with missing required shape is corrupt")
    @EnumSource(CorruptPayloadShape.class)
    void storedPayloadMissingRequiredCardinalityOrLocalesIsCorrupt(
            CorruptPayloadShape shape) {
        ProjectWorkspaceDto source = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "payload-shape-corruption", 0, 0L);
        projects.insert(source);
        UUID blockId = UUID.fromString(String.format(
                "86000000-0000-4000-8000-%012d", shape.ordinal() + 1));
        owner.sql("""
                        insert into portfolio.project_content_block(
                            id,project_id,block_type,sort_order)
                        values (:blockId,:projectId,:blockType,0)
                        """)
                .param("blockId", blockId)
                .param("projectId", source.id())
                .param("blockType", shape.blockType)
                .update();

        switch (shape) {
            case MARKDOWN_MISSING_LOCALE -> owner.sql("""
                            insert into portfolio.content_block_markdown_translation(
                                block_id,locale,markdown)
                            values (:blockId,'en','English only')
                            """)
                    .param("blockId", blockId)
                    .update();
            case QUOTE_MISSING_LOCALE -> owner.sql("""
                            insert into portfolio.content_block_quote_translation(
                                block_id,locale,quote_text,source_text)
                            values (:blockId,'en','English only','Source')
                            """)
                    .param("blockId", blockId)
                    .update();
            case GALLERY_TOO_SMALL -> {
                UUID assetId = UUID.fromString("83000000-0000-4000-8000-000000000096");
                insertReadyAsset(assetId, "one-gallery-image.png");
                owner.sql("""
                                insert into portfolio.content_block_media(
                                    block_id,media_asset_id,role,sort_order)
                                values (:blockId,:assetId,'GALLERY',0)
                                """)
                        .param("blockId", blockId)
                        .param("assetId", assetId)
                        .update();
            }
            case METRICS_EMPTY -> {
                // The missing metric rows are the corruption under test.
            }
            case METRIC_COPY_MISSING_LOCALE -> {
                UUID metricId = UUID.fromString(String.format(
                        "87000000-0000-4000-8000-%012d", shape.ordinal() + 1));
                owner.sql("""
                                insert into portfolio.content_block_metric(
                                    id,block_id,sort_order,numeric_value)
                                values (:metricId,:blockId,0,1)
                                """)
                        .param("metricId", metricId)
                        .param("blockId", blockId)
                        .update();
                owner.sql("""
                                insert into portfolio.content_block_metric_translation(
                                    metric_id,locale,label,value_text,suffix)
                                values (:metricId,'en','English only','One','')
                                """)
                        .param("metricId", metricId)
                        .update();
            }
            case VIDEO_COPY_MISSING_LOCALE -> {
                owner.sql("""
                                insert into portfolio.content_block_video(block_id,provider,url)
                                values (:blockId,'YOUTUBE','https://example.test/video')
                                """)
                        .param("blockId", blockId)
                        .update();
                insertEnglishBlockCopy(blockId);
            }
            case CODE_COPY_MISSING_LOCALE -> {
                owner.sql("""
                                insert into portfolio.content_block_code(
                                    block_id,code_text,language,show_line_numbers)
                                values (:blockId,'return 0;','cpp',true)
                                """)
                        .param("blockId", blockId)
                        .update();
                insertEnglishBlockCopy(blockId);
            }
            case DOWNLOAD_COPY_MISSING_LOCALE -> {
                owner.sql("""
                                insert into portfolio.content_block_action(
                                    block_id,action_type,target_type,url,open_new_tab)
                                values (
                                    :blockId,'DOWNLOAD','EXTERNAL',
                                    'https://example.test/download.zip',true)
                                """)
                        .param("blockId", blockId)
                        .update();
                insertEnglishBlockCopy(blockId);
            }
            case LINK_COPY_MISSING_LOCALE -> {
                owner.sql("""
                                insert into portfolio.content_block_action(
                                    block_id,action_type,target_type,url,open_new_tab)
                                values (
                                    :blockId,'LINK','EXTERNAL',
                                    'https://example.test/link',false)
                                """)
                        .param("blockId", blockId)
                        .update();
                insertEnglishBlockCopy(blockId);
            }
        }

        assertPersistenceCorrupt(() -> projects.require(source.id()));
    }

    @Test
    void staleProjectCasDoesNotReplacePreviouslyCommittedChildren() {
        ProjectWorkspaceDto input = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "stale-project", 0, 0L);
        projects.insert(input);
        ProjectWorkspaceDto changed = ContentPersistenceFixtures.withEnglishProjectTitle(input, "Committed");
        projects.replace(changed, 0L);
        ProjectWorkspaceDto committed = projects.require(input.id());

        assertThatThrownBy(() -> projects.replace(input, 0L))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONTENT_VERSION_CONFLICT"));
        assertThat(projects.require(input.id())).isEqualTo(committed);
    }

    @Test
    void missingTaxonomyIsRejectedBeforeProjectRootCasAndChildReplacement() {
        ProjectWorkspaceDto input = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "taxonomy-validation", 0, 0L);
        projects.insert(input);
        ProjectWorkspaceDto committed = projects.require(input.id());
        UUID missingTag = UUID.fromString("82000000-0000-4000-8000-000000000098");
        ProjectWorkspaceDto invalid = new ProjectWorkspaceDto(
                input.id(), input.externalKey(), input.slug(), input.number(), input.sortOrder(),
                input.featured(), input.visible(), input.publicationDirty(), input.version(),
                ContentPersistenceFixtures.withEnglishProjectTitle(input, "Must not persist").translations(),
                List.of(new ProjectWorkspaceDto.TaxonomyRef(
                        missingTag,
                        "client-controlled-name",
                        0,
                        Map.of(LocaleCode.EN, "Client controlled name"))),
                input.skills(), input.media(), input.blocks());

        assertThatThrownBy(() -> projects.replace(invalid, 0L))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("CONTENT_WORKSPACE_INVALID");
                    assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.fieldErrors()).containsKey("tags.id");
                });
        assertThat(projects.require(input.id())).isEqualTo(committed);
    }

    @Test
    void nestedProjectIdentitiesCannotCrossAggregateOwnership() {
        UUID ownerId = UUID.fromString("85000000-0000-4000-8000-000000000031");
        UUID candidateId = UUID.fromString("85000000-0000-4000-8000-000000000032");
        UUID blockId = UUID.fromString("84000000-0000-4000-8000-000000000094");
        UUID metricId = UUID.fromString("84000000-0000-4000-8000-000000000095");
        ContentBlockDto.Metric metric = new ContentBlockDto.Metric(
                metricId,
                0,
                null,
                Map.of(
                        LocaleCode.ZH_CN,
                        new ContentBlockDto.MetricCopy("指标", "一", ""),
                        LocaleCode.EN,
                        new ContentBlockDto.MetricCopy("Metric", "One", "")));
        ContentBlockDto ownedBlock = new ContentBlockDto(
                blockId,
                0,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                new ContentBlockDto.MetricsPayload(List.of(metric)));
        ProjectWorkspaceDto ownerProject = withBlocks(
                ContentPersistenceFixtures.simpleProject(ownerId, "identity-owner", 0, 0L),
                List.of(ownedBlock));
        projects.insert(ownerProject);

        ProjectWorkspaceDto candidate = ContentPersistenceFixtures.simpleProject(
                candidateId, "identity-candidate", 1, 0L);
        assertThatThrownBy(() -> projects.insert(withBlocks(candidate, List.of(ownedBlock))))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("CONTENT_WORKSPACE_INVALID");
                    assertThat(error.fieldErrors()).containsKey("blocks.id");
                });

        ContentBlockDto foreignMetricBlock = new ContentBlockDto(
                UUID.fromString("84000000-0000-4000-8000-000000000096"),
                0,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                new ContentBlockDto.MetricsPayload(List.of(metric)));
        assertThatThrownBy(() -> projects.insert(withBlocks(candidate, List.of(foreignMetricBlock))))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("CONTENT_WORKSPACE_INVALID");
                    assertThat(error.fieldErrors()).containsKey("blocks.metrics.id");
                });

        assertThat(projects.find(candidateId)).isEmpty();
        assertThat(projects.require(ownerId)).isEqualTo(ownerProject);
    }

    @Test
    void missingProjectUsesTheStableNotFoundProblem() {
        UUID missing = UUID.fromString("82000000-0000-4000-8000-000000000099");

        assertThat(projects.find(missing)).isEmpty();
        assertThatThrownBy(() -> projects.require(missing))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("PROJECT_NOT_FOUND");
                    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    void catalogSwapAndPartialOrderUseCollisionFreeContiguousPositions() {
        UUID first = UUID.fromString("85000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("85000000-0000-4000-8000-000000000002");
        UUID third = UUID.fromString("85000000-0000-4000-8000-000000000003");
        projects.insert(ContentPersistenceFixtures.simpleProject(first, "catalog-one", 0, 0L));
        projects.insert(ContentPersistenceFixtures.simpleProject(second, "catalog-two", 1, 0L));
        projects.insert(ContentPersistenceFixtures.simpleProject(third, "catalog-three", 2, 0L));

        projects.updateCatalogOrder(List.of(third, first));

        List<ProjectWorkspaceDto> ordered = projects.findAll();
        assertThat(ordered).extracting(ProjectWorkspaceDto::id)
                .containsExactly(third, first, second);
        assertThat(ordered).extracting(ProjectWorkspaceDto::sortOrder)
                .containsExactly(0, 1, 2);
        assertThat(ordered).extracting(ProjectWorkspaceDto::version)
                .containsOnly(1L);
    }

    @Test
    void catalogRejectsDuplicateAndMissingIdentitiesBeforeMovingAnyRows() {
        UUID first = UUID.fromString("85000000-0000-4000-8000-000000000021");
        UUID second = UUID.fromString("85000000-0000-4000-8000-000000000022");
        UUID missing = UUID.fromString("85000000-0000-4000-8000-000000000099");
        projects.insert(ContentPersistenceFixtures.simpleProject(first, "catalog-valid-one", 0, 0L));
        projects.insert(ContentPersistenceFixtures.simpleProject(second, "catalog-valid-two", 1, 0L));

        assertThatThrownBy(() -> projects.updateCatalogOrder(List.of(second, second)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONTENT_WORKSPACE_INVALID"));
        assertThatThrownBy(() -> projects.updateCatalogOrder(List.of(missing, second)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("PROJECT_NOT_FOUND"));

        assertThat(projects.findAll()).satisfiesExactly(
                project -> {
                    assertThat(project.id()).isEqualTo(first);
                    assertThat(project.sortOrder()).isZero();
                    assertThat(project.version()).isZero();
                },
                project -> {
                    assertThat(project.id()).isEqualTo(second);
                    assertThat(project.sortOrder()).isEqualTo(1);
                    assertThat(project.version()).isZero();
                });
    }

    @Test
    void taxonomyCasReplacesNamesAndBumpsOnlyLinkedProjectVersion() {
        seedTaxonomy();
        ProjectWorkspaceDto linked = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "taxonomy-linked", 0, 0L);
        linked = new ProjectWorkspaceDto(
                linked.id(), linked.externalKey(), linked.slug(), linked.number(), linked.sortOrder(),
                linked.featured(), linked.visible(), linked.publicationDirty(), linked.version(),
                linked.translations(),
                List.of(new ProjectWorkspaceDto.TaxonomyRef(
                        ContentPersistenceFixtures.TAG_ID, "gameplay", 0,
                        Map.of(LocaleCode.ZH_CN, "玩法", LocaleCode.EN, "Gameplay"))),
                List.of(), linked.media(), linked.blocks());
        projects.insert(linked);

        Map<LocaleCode, String> renamed =
                Map.of(LocaleCode.ZH_CN, "交互玩法", LocaleCode.EN, "Interactive gameplay");
        taxonomies.updateTag(ContentPersistenceFixtures.TAG_ID, renamed, 0L);

        assertThat(taxonomies.findTags()).singleElement().satisfies(tag -> {
            assertThat(tag.version()).isEqualTo(1L);
            assertThat(tag.names()).isEqualTo(renamed);
        });
        ProjectWorkspaceDto updated = projects.require(linked.id());
        assertThat(updated.version()).isEqualTo(1L);
        assertThat(updated.publicationDirty()).isTrue();
        assertThat(updated.tags().get(0).names()).isEqualTo(renamed);

        assertThatThrownBy(() -> taxonomies.updateTag(
                        ContentPersistenceFixtures.TAG_ID,
                        Map.of(LocaleCode.ZH_CN, "旧", LocaleCode.EN, "Stale"),
                        0L))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONTENT_VERSION_CONFLICT"));
        assertThat(projects.require(linked.id()).version()).isEqualTo(1L);
    }

    @Test
    void skillCasUsesOneTimestampAndMissingTaxonomyUsesStableNotFoundProblem() {
        seedTaxonomy();
        ProjectWorkspaceDto source = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "skill-linked", 0, 0L);
        ProjectWorkspaceDto linked = copyProject(
                source,
                source.externalKey(),
                source.slug(),
                source.sortOrder(),
                source.publicationDirty(),
                source.version(),
                source.tags(),
                List.of(new ProjectWorkspaceDto.TaxonomyRef(
                        ContentPersistenceFixtures.SKILL_ID,
                        "request-key-is-not-authoritative",
                        0,
                        Map.of(LocaleCode.EN, "Request name is not authoritative"))));
        projects.insert(linked);
        Instant mutationTime = Instant.parse("2035-05-06T07:08:09Z");
        Map<LocaleCode, String> renamed = Map.of(
                LocaleCode.ZH_CN, "UE engine",
                LocaleCode.EN, "Unreal Engine development");

        taxonomies.updateSkill(
                ContentPersistenceFixtures.SKILL_ID,
                renamed,
                0L,
                mutationTime);

        assertThat(taxonomies.findSkills()).singleElement().satisfies(skill -> {
            assertThat(skill.version()).isEqualTo(1L);
            assertThat(skill.names()).isEqualTo(renamed);
        });
        assertThat(projects.require(linked.id())).satisfies(project -> {
            assertThat(project.version()).isEqualTo(1L);
            assertThat(project.publicationDirty()).isTrue();
            assertThat(project.skills().get(0).names()).isEqualTo(renamed);
        });
        List<Instant> storedMutationTimes = owner.sql("""
                        select s.updated_at,p.updated_at
                        from portfolio.skill s cross join portfolio.project p
                        where s.id=:skillId and p.id=:projectId
                        """)
                .param("skillId", ContentPersistenceFixtures.SKILL_ID)
                .param("projectId", linked.id())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getObject(1, OffsetDateTime.class).toInstant(),
                        resultSet.getObject(2, OffsetDateTime.class).toInstant()))
                .single();
        assertThat(storedMutationTimes).containsExactly(mutationTime, mutationTime);

        UUID missing = UUID.fromString("82000000-0000-4000-8000-000000000097");
        assertThatThrownBy(() -> taxonomies.updateTag(
                        missing,
                        Map.of(LocaleCode.EN, "Missing"),
                        0L))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("TAXONOMY_NOT_FOUND");
                    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test
    void databaseTaxonomyValuesStayAuthoritativeAndImportOnlyCreatesMissingTags() {
        seedTaxonomy();
        ProjectWorkspaceDto source = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "taxonomy-authority", 0, 0L);
        ProjectWorkspaceDto withUntrustedTaxonomyCopy = copyProject(
                source,
                source.externalKey(),
                source.slug(),
                source.sortOrder(),
                source.publicationDirty(),
                source.version(),
                List.of(new ProjectWorkspaceDto.TaxonomyRef(
                        ContentPersistenceFixtures.TAG_ID,
                        "untrusted-key",
                        0,
                        Map.of(LocaleCode.EN, "Untrusted name"))),
                source.skills());
        projects.insert(withUntrustedTaxonomyCopy);

        ProjectWorkspaceDto.TaxonomyRef stored = projects.require(source.id()).tags().get(0);
        assertThat(stored.normalizedKey()).isEqualTo("gameplay");
        assertThat(stored.names().get(LocaleCode.EN)).isEqualTo("Gameplay");

        UUID importedId = UUID.fromString("83000000-0000-4000-8000-000000000099");
        Map<LocaleCode, String> importedNames = Map.of(
                LocaleCode.ZH_CN, "Animation",
                LocaleCode.EN, "Animation");
        taxonomies.replaceImportTags(List.of(
                new ProjectWorkspaceDto.TaxonomyRef(
                        ContentPersistenceFixtures.TAG_ID,
                        "gameplay",
                        0,
                        Map.of(LocaleCode.EN, "Still untrusted")),
                new ProjectWorkspaceDto.TaxonomyRef(
                        importedId,
                        "animation",
                        1,
                        importedNames)));

        assertThat(taxonomies.findTags())
                .filteredOn(tag -> tag.id().equals(ContentPersistenceFixtures.TAG_ID))
                .singleElement()
                .satisfies(tag -> assertThat(tag.names().get(LocaleCode.EN)).isEqualTo("Gameplay"));
        assertThat(taxonomies.findTags())
                .filteredOn(tag -> tag.id().equals(importedId))
                .singleElement()
                .satisfies(tag -> {
                    assertThat(tag.normalizedKey()).isEqualTo("animation");
                    assertThat(tag.version()).isZero();
                    assertThat(tag.names()).isEqualTo(importedNames);
                });
    }

    @Test
    void publicationMethodsUseCasDeduplicateIdsAndIncrementExactlyOnce() {
        ProjectWorkspaceDto input = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "publication-state", 0, 0L);
        projects.insert(input);

        projects.markPublished(input.id(), 0L);
        assertThat(projects.require(input.id())).satisfies(project -> {
            assertThat(project.version()).isEqualTo(1L);
            assertThat(project.publicationDirty()).isFalse();
        });
        assertThatThrownBy(() -> projects.markPublished(input.id(), 0L))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONTENT_VERSION_CONFLICT"));
        assertThat(projects.require(input.id()).version()).isEqualTo(1L);

        projects.markPublicationDirty(List.of(input.id(), input.id()));
        assertThat(projects.require(input.id())).satisfies(project -> {
            assertThat(project.version()).isEqualTo(2L);
            assertThat(project.publicationDirty()).isTrue();
        });
    }

    @Test
    void replaceUsesExpectedVersionForcesDirtyAndRejectsExternalKeyMutation() {
        ProjectWorkspaceDto input = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "immutable-key", 0, 0L);
        projects.insert(input);
        projects.markPublished(input.id(), 0L);
        ProjectWorkspaceDto clean = projects.require(input.id());
        ProjectWorkspaceDto replacement = copyProject(
                ContentPersistenceFixtures.withEnglishProjectTitle(clean, "Expected-version update"),
                clean.externalKey(),
                clean.slug(),
                clean.sortOrder(),
                false,
                999L,
                clean.tags(),
                clean.skills());

        projects.replace(replacement, 1L);

        ProjectWorkspaceDto committed = projects.require(input.id());
        assertThat(committed.version()).isEqualTo(2L);
        assertThat(committed.publicationDirty()).isTrue();
        assertThat(committed.translations().get(LocaleCode.EN).title())
                .isEqualTo("Expected-version update");

        ProjectWorkspaceDto invalidIdentity = copyProject(
                committed,
                "changed-external-key",
                committed.slug(),
                committed.sortOrder(),
                committed.publicationDirty(),
                committed.version(),
                committed.tags(),
                committed.skills());
        assertThatThrownBy(() -> projects.replace(invalidIdentity, 2L))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONTENT_IDENTITY_MISMATCH"));
        assertThat(projects.require(input.id())).isEqualTo(committed);
    }

    @Test
    void namedProjectUniquenessConstraintsUseStableConflictCodes() {
        UUID firstId = UUID.fromString("85000000-0000-4000-8000-000000000011");
        UUID candidateId = UUID.fromString("85000000-0000-4000-8000-000000000012");
        ProjectWorkspaceDto first = ContentPersistenceFixtures.simpleProject(firstId, "unique-one", 0, 0L);
        projects.insert(first);

        ProjectWorkspaceDto candidate = ContentPersistenceFixtures.simpleProject(
                candidateId, "unique-two", 1, 0L);
        ProjectWorkspaceDto duplicateSlug = copyProject(
                candidate,
                candidate.externalKey(),
                first.slug(),
                candidate.sortOrder(),
                candidate.publicationDirty(),
                candidate.version(),
                candidate.tags(),
                candidate.skills());
        assertConflictCode(
                () -> projects.insert(duplicateSlug),
                "CONTENT_SLUG_CONFLICT");

        ProjectWorkspaceDto duplicateExternalKey = copyProject(
                candidate,
                first.externalKey(),
                candidate.slug(),
                candidate.sortOrder(),
                candidate.publicationDirty(),
                candidate.version(),
                candidate.tags(),
                candidate.skills());
        assertConflictCode(
                () -> projects.insert(duplicateExternalKey),
                "CONTENT_EXTERNAL_KEY_CONFLICT");

        ProjectWorkspaceDto duplicateCatalogOrder = copyProject(
                candidate,
                candidate.externalKey(),
                candidate.slug(),
                first.sortOrder(),
                candidate.publicationDirty(),
                candidate.version(),
                candidate.tags(),
                candidate.skills());
        assertConflictCode(
                () -> projects.insert(duplicateCatalogOrder),
                "CONTENT_CATALOG_ORDER_CONFLICT");
        assertThat(projects.findAll()).extracting(ProjectWorkspaceDto::id).containsExactly(firstId);
    }

    @Test
    void namedProjectSortCheckMapsToStableValidationAndRollsBackTheRoot() {
        ProjectWorkspaceDto invalid = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "negative-sort", -1, 0L);

        assertValidationError(
                () -> projects.insert(invalid),
                "sortOrder");

        assertThat(projects.find(invalid.id())).isEmpty();
    }

    @ParameterizedTest(name = "named project-media {0} check maps to validation")
    @EnumSource(InvalidProjectMedia.class)
    void namedProjectMediaChecksMapToStableValidationAndRollBack(
            InvalidProjectMedia invalidField) {
        UUID assetId = UUID.fromString("83000000-0000-4000-8000-000000000095");
        insertReadyAsset(assetId, "invalid-project-media.png");
        ProjectWorkspaceDto source = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID, "invalid-project-media", 0, 0L);
        ProjectWorkspaceDto.ProjectMedia media = new ProjectWorkspaceDto.ProjectMedia(
                assetId,
                invalidField == InvalidProjectMedia.USAGE ? "BAD" : "COVER",
                0,
                invalidField == InvalidProjectMedia.LAYOUT ? "bad" : "wide",
                "50% 50%",
                "Credit",
                URI.create(invalidField == InvalidProjectMedia.SOURCE_URL
                        ? "http://example.test/not-https"
                        : "https://example.test/media"));
        ProjectWorkspaceDto invalid = new ProjectWorkspaceDto(
                source.id(), source.externalKey(), source.slug(), source.number(), source.sortOrder(),
                source.featured(), source.visible(), source.publicationDirty(), source.version(),
                source.translations(), source.tags(), source.skills(), List.of(media), source.blocks());

        assertValidationError(
                () -> projects.insert(invalid),
                invalidField.field);

        assertThat(projects.find(invalid.id())).isEmpty();
    }

    @ParameterizedTest(name = "SITE named nested uniqueness {0} maps to conflict")
    @EnumSource(InvalidSiteUniqueness.class)
    void namedSiteNestedUniquenessMapsToStableConflictAndRollsBack(
            InvalidSiteUniqueness invalidField) {
        SiteWorkspaceDto source = ContentPersistenceFixtures.siteWithoutMedia(0L);
        sites.replace(source, 0L);
        SiteWorkspaceDto committed = sites.require();
        List<SiteWorkspaceDto.NavigationItem> navigation = committed.navigation();
        List<SiteWorkspaceDto.ProfileFact> facts = committed.facts();
        if (invalidField == InvalidSiteUniqueness.NAVIGATION_TARGET) {
            SiteWorkspaceDto.NavigationItem first = navigation.get(0);
            SiteWorkspaceDto.NavigationItem second = navigation.get(1);
            navigation = List.of(
                    first,
                    new SiteWorkspaceDto.NavigationItem(
                            second.id(), first.target(), second.sortOrder(),
                            second.visible(), second.labels()));
        } else if (invalidField == InvalidSiteUniqueness.NAVIGATION_SORT_ORDER) {
            SiteWorkspaceDto.NavigationItem first = navigation.get(0);
            SiteWorkspaceDto.NavigationItem second = navigation.get(1);
            navigation = List.of(
                    first,
                    new SiteWorkspaceDto.NavigationItem(
                            second.id(), second.target(), first.sortOrder(),
                            second.visible(), second.labels()));
        } else {
            SiteWorkspaceDto.ProfileFact first = facts.get(0);
            facts = List.of(
                    first,
                    new SiteWorkspaceDto.ProfileFact(
                            UUID.fromString("87000000-0000-4000-8000-000000000001"),
                            first.externalKey(),
                            first.sortOrder() + 1,
                            first.copy()));
        }
        SiteWorkspaceDto invalid = copySiteCollections(committed, navigation, facts);

        assertConflictField(
                () -> sites.replace(invalid, committed.version()),
                "CONTENT_UNIQUE_CONFLICT",
                invalidField.field);

        assertThat(sites.require()).isEqualTo(committed);
    }

    @Test
    void importTagNormalizedKeyConflictMapsToStable409AndRollsBack() {
        seedTaxonomy();
        UUID newId = UUID.fromString("82000000-0000-4000-8000-000000000096");

        assertConflictField(
                () -> taxonomies.replaceImportTags(List.of(
                        new ProjectWorkspaceDto.TaxonomyRef(
                                newId,
                                "gameplay",
                                0,
                                Map.of(
                                        LocaleCode.ZH_CN, "Duplicate",
                                        LocaleCode.EN, "Duplicate")))),
                "CONTENT_UNIQUE_CONFLICT",
                "tags.normalizedKey");

        assertThat(taxonomies.findTags()).singleElement().satisfies(tag -> {
            assertThat(tag.id()).isEqualTo(ContentPersistenceFixtures.TAG_ID);
            assertThat(tag.normalizedKey()).isEqualTo("gameplay");
            assertThat(tag.version()).isZero();
        });
    }

    @ParameterizedTest(name = "project child uniqueness {0} maps to a stable conflict")
    @EnumSource(InvalidProjectChildUniqueness.class)
    void allDtoReachableProjectChildUniqueConstraintsMapToStable409AndRollBack(
            InvalidProjectChildUniqueness invalidField) {
        ProjectWorkspaceDto source = ContentPersistenceFixtures.simpleProject(
                ContentPersistenceFixtures.PROJECT_ID,
                "child-uniqueness",
                0,
                0L);
        projects.insert(source);
        ProjectWorkspaceDto invalid = projectWithChildConflict(source, invalidField);

        assertConflictField(
                () -> projects.replace(invalid, source.version()),
                "CONTENT_UNIQUE_CONFLICT",
                invalidField.field);

        assertThat(projects.require(source.id())).isEqualTo(source);
    }

    @ParameterizedTest(name = "SITE duplicate collection identity {0} is rejected")
    @EnumSource(DuplicateSiteCollectionIdentity.class)
    void duplicateSiteCollectionIdentitiesAreRejectedBeforeMutationAndKeepCommittedState(
            DuplicateSiteCollectionIdentity invalidField) {
        SiteWorkspaceDto source = ContentPersistenceFixtures.siteWithoutMedia(0L);
        sites.replace(source, 0L);
        SiteWorkspaceDto committed = sites.require();
        SiteWorkspaceDto invalid = siteWithDuplicateIdentity(committed, invalidField);

        assertValidationError(
                () -> sites.replace(invalid, committed.version()),
                invalidField.field);

        assertThat(sites.require()).isEqualTo(committed);
    }

    @ParameterizedTest(name = "unmapped persistence exception {0} is preserved")
    @EnumSource(UnmappedPersistenceFailure.class)
    void unknownOrConstraintFreeDatabaseFailuresRemainOriginal(
            UnmappedPersistenceFailure failure) {
        RuntimeException original = new IllegalStateException(failure.message);

        assertThat(ContentPersistenceErrors.translateConstraint(original))
                .isSameAs(original);
    }

    private ProjectWorkspaceDto projectWithChildConflict(
            ProjectWorkspaceDto source,
            InvalidProjectChildUniqueness conflict) {
        UUID secondTagId = UUID.fromString("88000000-0000-4000-8000-000000000001");
        UUID secondSkillId = UUID.fromString("88000000-0000-4000-8000-000000000002");
        UUID firstAssetId = UUID.fromString("83000000-0000-4000-8000-000000000093");
        UUID secondAssetId = UUID.fromString("83000000-0000-4000-8000-000000000094");
        return switch (conflict) {
            case TAG_SORT_ORDER -> {
                seedExpandedTaxonomy(secondTagId, secondSkillId);
                yield copyProjectCollections(
                        source,
                        List.of(
                                taxonomyRef(
                                        ContentPersistenceFixtures.TAG_ID,
                                        "gameplay",
                                        0),
                                taxonomyRef(secondTagId, "systems", 0)),
                        source.skills(),
                        source.media(),
                        source.blocks());
            }
            case SKILL_SORT_ORDER -> {
                seedExpandedTaxonomy(secondTagId, secondSkillId);
                yield copyProjectCollections(
                        source,
                        source.tags(),
                        List.of(
                                taxonomyRef(
                                        ContentPersistenceFixtures.SKILL_ID,
                                        "unreal-engine",
                                        0),
                                taxonomyRef(secondSkillId, "cpp", 0)),
                        source.media(),
                        source.blocks());
            }
            case MEDIA_ASSET_USAGE -> {
                insertReadyAsset(firstAssetId, "duplicate-media-identity.png");
                yield copyProjectCollections(
                        source,
                        source.tags(),
                        source.skills(),
                        List.of(
                                projectMedia(firstAssetId, "COVER", 0),
                                projectMedia(firstAssetId, "COVER", 1)),
                        source.blocks());
            }
            case MEDIA_USAGE_SORT_ORDER -> {
                insertReadyAsset(firstAssetId, "duplicate-media-sort-a.png");
                insertReadyAsset(secondAssetId, "duplicate-media-sort-b.png");
                yield copyProjectCollections(
                        source,
                        source.tags(),
                        source.skills(),
                        List.of(
                                projectMedia(firstAssetId, "COVER", 0),
                                projectMedia(secondAssetId, "COVER", 0)),
                        source.blocks());
            }
            case BLOCK_SORT_ORDER -> copyProjectCollections(
                    source,
                    source.tags(),
                    source.skills(),
                    source.media(),
                    List.of(
                            markdownBlock(
                                    UUID.fromString("89000000-0000-4000-8000-000000000001"),
                                    0),
                            markdownBlock(
                                    UUID.fromString("89000000-0000-4000-8000-000000000002"),
                                    0)));
            case METRIC_SORT_ORDER -> copyProjectCollections(
                    source,
                    source.tags(),
                    source.skills(),
                    source.media(),
                    List.of(metricsBlockWithDuplicateSortOrder()));
        };
    }

    private SiteWorkspaceDto siteWithDuplicateIdentity(
            SiteWorkspaceDto source,
            DuplicateSiteCollectionIdentity conflict) {
        List<SiteWorkspaceDto.NavigationItem> navigation = source.navigation();
        List<SiteWorkspaceDto.ProfileFact> facts = source.facts();
        List<SiteWorkspaceDto.ProfileSkill> profileSkills = source.profileSkills();
        SiteWorkspaceDto.Roadmap roadmap = source.roadmap();
        List<SiteWorkspaceDto.SocialLink> socialLinks = source.socialLinks();
        List<SiteWorkspaceDto.ResumeDocument> resumes = source.resumes();
        switch (conflict) {
            case NAVIGATION_ID -> {
                SiteWorkspaceDto.NavigationItem first = navigation.get(0);
                SiteWorkspaceDto.NavigationItem second = navigation.get(1);
                navigation = List.of(
                        first,
                        new SiteWorkspaceDto.NavigationItem(
                                first.id(),
                                second.target(),
                                second.sortOrder(),
                                second.visible(),
                                second.labels()));
            }
            case SOCIAL_LINK_ID -> {
                SiteWorkspaceDto.SocialLink first = socialLinks.get(0);
                socialLinks = List.of(
                        first,
                        new SiteWorkspaceDto.SocialLink(
                                first.id(),
                                "LinkedIn",
                                URI.create("https://example.test/linkedin"),
                                first.sortOrder() + 1,
                                true));
            }
            case PROFILE_FACT_ID -> {
                SiteWorkspaceDto.ProfileFact first = facts.get(0);
                facts = List.of(
                        first,
                        new SiteWorkspaceDto.ProfileFact(
                                first.id(),
                                "location",
                                first.sortOrder() + 1,
                                first.copy()));
            }
            case PROFILE_SKILL_ID -> {
                SiteWorkspaceDto.ProfileSkill first = profileSkills.get(0);
                profileSkills = List.of(
                        first,
                        new SiteWorkspaceDto.ProfileSkill(
                                first.id(),
                                "cpp",
                                first.sortOrder() + 1,
                                first.copy()));
            }
            case ROADMAP_STAGE_ID -> {
                SiteWorkspaceDto.RoadmapStage first = roadmap.stages().get(0);
                SiteWorkspaceDto.RoadmapStage duplicate = new SiteWorkspaceDto.RoadmapStage(
                        first.id(),
                        "next-stage",
                        "02",
                        first.sortOrder() + 1,
                        true,
                        first.copy(),
                        List.of());
                roadmap = new SiteWorkspaceDto.Roadmap(
                        roadmap.header(),
                        List.of(first, duplicate));
            }
            case ROADMAP_OUTCOME_ID -> {
                SiteWorkspaceDto.RoadmapStage stage = roadmap.stages().get(0);
                SiteWorkspaceDto.RoadmapOutcome first = stage.outcomes().get(0);
                SiteWorkspaceDto.RoadmapOutcome duplicate = new SiteWorkspaceDto.RoadmapOutcome(
                        first.id(),
                        first.sortOrder() + 1,
                        first.text());
                SiteWorkspaceDto.RoadmapStage changed = new SiteWorkspaceDto.RoadmapStage(
                        stage.id(),
                        stage.externalKey(),
                        stage.number(),
                        stage.sortOrder(),
                        stage.visible(),
                        stage.copy(),
                        List.of(first, duplicate));
                roadmap = new SiteWorkspaceDto.Roadmap(
                        roadmap.header(),
                        List.of(changed));
            }
            case ROADMAP_OUTCOME_CROSS_STAGE_ID -> {
                SiteWorkspaceDto.RoadmapStage firstStage = roadmap.stages().get(0);
                SiteWorkspaceDto.RoadmapOutcome firstOutcome = firstStage.outcomes().get(0);
                SiteWorkspaceDto.RoadmapStage secondStage = new SiteWorkspaceDto.RoadmapStage(
                        UUID.fromString("88000000-0000-4000-8000-000000000010"),
                        "next-stage",
                        "02",
                        firstStage.sortOrder() + 1,
                        true,
                        firstStage.copy(),
                        List.of(new SiteWorkspaceDto.RoadmapOutcome(
                                firstOutcome.id(),
                                0,
                                firstOutcome.text())));
                roadmap = new SiteWorkspaceDto.Roadmap(
                        roadmap.header(),
                        List.of(firstStage, secondStage));
            }
            case RESUME_ID -> {
                UUID assetId = UUID.fromString("83000000-0000-4000-8000-000000000092");
                UUID resumeId = UUID.fromString("88000000-0000-4000-8000-000000000020");
                insertReadyAsset(assetId, "duplicate-resume.pdf");
                resumes = List.of(
                        new SiteWorkspaceDto.ResumeDocument(
                                resumeId,
                                LocaleCode.ZH_CN,
                                assetId,
                                "2026.1 zh",
                                false,
                                LocalDate.of(2026, 7, 17)),
                        new SiteWorkspaceDto.ResumeDocument(
                                resumeId,
                                LocaleCode.EN,
                                assetId,
                                "2026.1 en",
                                false,
                                LocalDate.of(2026, 7, 17)));
            }
        }
        return copySiteCollections(
                source,
                navigation,
                facts,
                profileSkills,
                roadmap,
                socialLinks,
                resumes);
    }

    private ProjectWorkspaceDto.TaxonomyRef taxonomyRef(
            UUID id,
            String normalizedKey,
            int sortOrder) {
        return new ProjectWorkspaceDto.TaxonomyRef(
                id,
                normalizedKey,
                sortOrder,
                Map.of(
                        LocaleCode.ZH_CN, normalizedKey,
                        LocaleCode.EN, normalizedKey));
    }

    private ProjectWorkspaceDto.ProjectMedia projectMedia(
            UUID assetId,
            String usage,
            int sortOrder) {
        return new ProjectWorkspaceDto.ProjectMedia(
                assetId,
                usage,
                sortOrder,
                "wide",
                "50% 50%",
                "Credit",
                URI.create("https://example.test/media/" + assetId));
    }

    private ContentBlockDto markdownBlock(UUID id, int sortOrder) {
        return new ContentBlockDto(
                id,
                sortOrder,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                new ContentBlockDto.MarkdownPayload(Map.of(
                        LocaleCode.ZH_CN, "中文",
                        LocaleCode.EN, "English")));
    }

    private ContentBlockDto metricsBlockWithDuplicateSortOrder() {
        ContentBlockDto.Metric first = metric(
                UUID.fromString("89000000-0000-4000-8000-000000000011"),
                0);
        ContentBlockDto.Metric second = metric(
                UUID.fromString("89000000-0000-4000-8000-000000000012"),
                0);
        return new ContentBlockDto(
                UUID.fromString("89000000-0000-4000-8000-000000000010"),
                0,
                true,
                ContentBlockDto.Width.STANDARD,
                ContentBlockDto.Alignment.LEFT,
                ContentBlockDto.Emphasis.NONE,
                1,
                new ContentBlockDto.MetricsPayload(List.of(first, second)));
    }

    private ContentBlockDto.Metric metric(UUID id, int sortOrder) {
        return new ContentBlockDto.Metric(
                id,
                sortOrder,
                null,
                Map.of(
                        LocaleCode.ZH_CN,
                        new ContentBlockDto.MetricCopy("指标", "一", ""),
                        LocaleCode.EN,
                        new ContentBlockDto.MetricCopy("Metric", "One", "")));
    }

    private void assertConflictCode(Runnable mutation, String code) {
        assertThatThrownBy(mutation::run)
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    private void assertConflictField(Runnable mutation, String code, String field) {
        assertThatThrownBy(mutation::run)
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.fieldErrors()).containsKey(field);
                });
    }

    private void assertValidationError(Runnable mutation, String field) {
        assertThatThrownBy(mutation::run)
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo("CONTENT_WORKSPACE_INVALID");
                    assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.fieldErrors()).containsKey(field);
                });
    }

    private void assertPersistenceCorrupt(Runnable read) {
        assertThatThrownBy(read::run)
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo("CONTENT_PERSISTENCE_CORRUPT"));
    }

    private ProjectWorkspaceDto copyProject(
            ProjectWorkspaceDto source,
            String externalKey,
            String slug,
            int sortOrder,
            boolean publicationDirty,
            long version,
            List<ProjectWorkspaceDto.TaxonomyRef> tags,
            List<ProjectWorkspaceDto.TaxonomyRef> skills) {
        return new ProjectWorkspaceDto(
                source.id(), externalKey, slug, source.number(), sortOrder,
                source.featured(), source.visible(), publicationDirty, version,
                source.translations(), tags, skills, source.media(), source.blocks());
    }

    private ProjectWorkspaceDto withBlocks(
            ProjectWorkspaceDto source,
            List<ContentBlockDto> blocks) {
        return new ProjectWorkspaceDto(
                source.id(), source.externalKey(), source.slug(), source.number(), source.sortOrder(),
                source.featured(), source.visible(), source.publicationDirty(), source.version(),
                source.translations(), source.tags(), source.skills(), source.media(), blocks);
    }

    private ProjectWorkspaceDto copyProjectCollections(
            ProjectWorkspaceDto source,
            List<ProjectWorkspaceDto.TaxonomyRef> tags,
            List<ProjectWorkspaceDto.TaxonomyRef> skills,
            List<ProjectWorkspaceDto.ProjectMedia> media,
            List<ContentBlockDto> blocks) {
        return new ProjectWorkspaceDto(
                source.id(), source.externalKey(), source.slug(), source.number(), source.sortOrder(),
                source.featured(), source.visible(), source.publicationDirty(), source.version(),
                source.translations(), tags, skills, media, blocks);
    }

    private SiteWorkspaceDto copySiteCollections(
            SiteWorkspaceDto source,
            List<SiteWorkspaceDto.NavigationItem> navigation,
            List<SiteWorkspaceDto.ProfileFact> facts) {
        return copySiteCollections(
                source,
                navigation,
                facts,
                source.profileSkills(),
                source.roadmap(),
                source.socialLinks(),
                source.resumes());
    }

    private SiteWorkspaceDto copySiteCollections(
            SiteWorkspaceDto source,
            List<SiteWorkspaceDto.NavigationItem> navigation,
            List<SiteWorkspaceDto.ProfileFact> facts,
            List<SiteWorkspaceDto.ProfileSkill> profileSkills,
            SiteWorkspaceDto.Roadmap roadmap,
            List<SiteWorkspaceDto.SocialLink> socialLinks,
            List<SiteWorkspaceDto.ResumeDocument> resumes) {
        return new SiteWorkspaceDto(
                source.siteId(), source.version(), source.monogram(), source.email(),
                source.identity(), source.seo(), source.accessibility(), navigation,
                source.hero(), source.about(), facts, profileSkills, source.work(),
                roadmap, source.contact(), source.privacy(), socialLinks, resumes);
    }

    private void insertEnglishBlockCopy(UUID blockId) {
        owner.sql("""
                        insert into portfolio.project_content_block_translation(
                            block_id,locale,title,description)
                        values (:blockId,'en','English only','Missing zh-CN')
                        """)
                .param("blockId", blockId)
                .update();
    }

    private void seedTaxonomy() {
        owner.sql("insert into portfolio.tag(id,normalized_key) values (:id,'gameplay')")
                .param("id", ContentPersistenceFixtures.TAG_ID).update();
        owner.sql("""
                        insert into portfolio.tag_translation(tag_id,locale,name)
                        values (:id,'zh-CN','玩法'),(:id,'en','Gameplay')
                        """).param("id", ContentPersistenceFixtures.TAG_ID).update();
        owner.sql("insert into portfolio.skill(id,normalized_key) values (:id,'unreal-engine')")
                .param("id", ContentPersistenceFixtures.SKILL_ID).update();
        owner.sql("""
                        insert into portfolio.skill_translation(skill_id,locale,name)
                        values (:id,'zh-CN','虚幻引擎'),(:id,'en','Unreal Engine')
                        """).param("id", ContentPersistenceFixtures.SKILL_ID).update();
    }

    private void seedExpandedTaxonomy(UUID secondTagId, UUID secondSkillId) {
        seedTaxonomy();
        owner.sql("insert into portfolio.tag(id,normalized_key) values (:id,'systems')")
                .param("id", secondTagId)
                .update();
        owner.sql("insert into portfolio.skill(id,normalized_key) values (:id,'cpp')")
                .param("id", secondSkillId)
                .update();
    }

    private void insertReadyAsset(java.util.UUID id, String filename) {
        owner.sql("""
                        insert into portfolio.media_asset(
                            id, provider, object_key, original_filename, mime_type,
                            byte_size, sha256, status
                        ) values (:id, 'LOCAL', :key, :filename, :mime, 1, :sha, 'READY')
                        """)
                .param("id", id)
                .param("key", "content-persistence/" + id)
                .param("filename", filename)
                .param("mime", filename.endsWith(".pdf") ? "application/pdf" : "image/png")
                .param("sha", id.toString().replace("-", "") + id.toString().replace("-", ""))
                .update();
    }

    private enum InvalidProjectMedia {
        USAGE("media.usage"),
        LAYOUT("media.layout"),
        SOURCE_URL("media.sourceUrl");

        private final String field;

        InvalidProjectMedia(String field) {
            this.field = field;
        }
    }

    private enum InvalidSiteUniqueness {
        NAVIGATION_TARGET("navigation.target"),
        NAVIGATION_SORT_ORDER("navigation.sortOrder"),
        PROFILE_FACT_EXTERNAL_KEY("facts.externalKey");

        private final String field;

        InvalidSiteUniqueness(String field) {
            this.field = field;
        }
    }

    private enum InvalidProjectChildUniqueness {
        TAG_SORT_ORDER("tags.sortOrder"),
        SKILL_SORT_ORDER("skills.sortOrder"),
        MEDIA_ASSET_USAGE("media.assetUsage"),
        MEDIA_USAGE_SORT_ORDER("media.sortOrder"),
        BLOCK_SORT_ORDER("blocks.sortOrder"),
        METRIC_SORT_ORDER("blocks.metrics.sortOrder");

        private final String field;

        InvalidProjectChildUniqueness(String field) {
            this.field = field;
        }
    }

    private enum DuplicateSiteCollectionIdentity {
        NAVIGATION_ID("navigation.id"),
        SOCIAL_LINK_ID("socialLinks.id"),
        PROFILE_FACT_ID("facts.id"),
        PROFILE_SKILL_ID("profileSkills.id"),
        ROADMAP_STAGE_ID("roadmap.stages.id"),
        ROADMAP_OUTCOME_ID("roadmap.stages.outcomes.id"),
        ROADMAP_OUTCOME_CROSS_STAGE_ID("roadmap.stages.outcomes.id"),
        RESUME_ID("resumes.id");

        private final String field;

        DuplicateSiteCollectionIdentity(String field) {
            this.field = field;
        }
    }

    private enum UnmappedPersistenceFailure {
        NO_CONSTRAINT("database operation failed"),
        UNKNOWN_CONSTRAINT(
                "duplicate key value violates unique constraint \"future_content_uk\"");

        private final String message;

        UnmappedPersistenceFailure(String message) {
            this.message = message;
        }
    }

    private enum CorruptPayloadShape {
        MARKDOWN_MISSING_LOCALE("MARKDOWN"),
        QUOTE_MISSING_LOCALE("QUOTE"),
        GALLERY_TOO_SMALL("GALLERY"),
        METRICS_EMPTY("METRICS"),
        METRIC_COPY_MISSING_LOCALE("METRICS"),
        VIDEO_COPY_MISSING_LOCALE("VIDEO"),
        CODE_COPY_MISSING_LOCALE("CODE"),
        DOWNLOAD_COPY_MISSING_LOCALE("DOWNLOAD"),
        LINK_COPY_MISSING_LOCALE("LINK");

        private final String blockType;

        CorruptPayloadShape(String blockType) {
            this.blockType = blockType;
        }
    }
}
