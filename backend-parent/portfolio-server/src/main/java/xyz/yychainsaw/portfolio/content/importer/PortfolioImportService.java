package xyz.yychainsaw.portfolio.content.importer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.yychainsaw.portfolio.common.error.DomainException;
import xyz.yychainsaw.portfolio.content.api.LocaleCode;
import xyz.yychainsaw.portfolio.content.api.ProjectWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.SiteWorkspaceDto;
import xyz.yychainsaw.portfolio.content.api.TaxonomyWorkspaceDto;
import xyz.yychainsaw.portfolio.content.persistence.ProjectWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.SiteWorkspaceRepository;
import xyz.yychainsaw.portfolio.content.persistence.TaxonomyRepository;
import xyz.yychainsaw.portfolio.media.application.ImportMediaCommand;
import xyz.yychainsaw.portfolio.media.application.ImportedMedia;
import xyz.yychainsaw.portfolio.media.application.MediaAssetDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaCopyDescriptor;
import xyz.yychainsaw.portfolio.media.application.MediaFinalizationService;
import xyz.yychainsaw.portfolio.media.application.MediaImportService;
import xyz.yychainsaw.portfolio.media.application.MediaQueryService;
import xyz.yychainsaw.portfolio.media.application.MediaVariantDescriptor;

@Service
@Conditional(PortfolioImportRuntimeCondition.class)
public final class PortfolioImportService {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Comparator<ImportIssue> ISSUE_ORDER =
            Comparator.comparing(ImportIssue::severity)
                    .thenComparing(ImportIssue::path)
                    .thenComparing(ImportIssue::code)
                    .thenComparing(ImportIssue::message);
    private static final Set<String> MEDIA_LOCALES = Set.of("zh-CN", "en");

    private final PortfolioImportReader reader;
    private final PortfolioImportValidator validator;
    private final PortfolioImportMapper mapper;
    private final MediaImportService mediaImports;
    private final MediaFinalizationService mediaFinalization;
    private final MediaQueryService mediaQueries;
    private final TransactionTemplate transactions;
    private final SiteWorkspaceRepository sites;
    private final TaxonomyRepository taxonomy;
    private final ProjectWorkspaceRepository projects;

    public PortfolioImportService(
            PortfolioImportReader reader,
            PortfolioImportValidator validator,
            PortfolioImportMapper mapper,
            MediaImportService mediaImports,
            MediaFinalizationService mediaFinalization,
            MediaQueryService mediaQueries,
            TransactionTemplate transactions,
            SiteWorkspaceRepository sites,
            TaxonomyRepository taxonomy,
            ProjectWorkspaceRepository projects) {
        this.reader = Objects.requireNonNull(reader, "portfolio import reader is required");
        this.validator = Objects.requireNonNull(
                validator, "portfolio import validator is required");
        this.mapper = Objects.requireNonNull(mapper, "portfolio import mapper is required");
        this.mediaImports = Objects.requireNonNull(
                mediaImports, "media import service is required");
        this.mediaFinalization = Objects.requireNonNull(
                mediaFinalization, "media finalization service is required");
        this.mediaQueries = Objects.requireNonNull(
                mediaQueries, "media query service is required");
        this.transactions = Objects.requireNonNull(
                transactions, "transaction template is required");
        this.sites = Objects.requireNonNull(sites, "site repository is required");
        this.taxonomy = Objects.requireNonNull(taxonomy, "taxonomy repository is required");
        this.projects = Objects.requireNonNull(projects, "project repository is required");
    }

    public ImportReport dryRun(
            Path input, Path assetRoot, String expectedSha256) {
        Validation validation = validate(input, assetRoot, expectedSha256);
        return validation.report(false);
    }

    public ImportReport commit(
            Path input, Path assetRoot, String expectedSha256) {
        rejectAmbientTransaction();
        Validation validation = validate(input, assetRoot, expectedSha256);
        if (validation.hasStructureErrors()) {
            return validation.report(false);
        }
        PortfolioImportV1 payload = validation.payload();
        requireEmptyWorkspace();

        TreeMap<String, ImportMediaCommand> commands = mediaCommands(payload, assetRoot);
        LinkedHashMap<String, ImportedMedia> importedByPath = new LinkedHashMap<>();
        for (Map.Entry<String, ImportMediaCommand> entry : commands.entrySet()) {
            ImportedMedia imported = Objects.requireNonNull(
                    transactions.execute(status -> mediaImports.importLocal(entry.getValue())),
                    "media import returned no result");
            importedByPath.put(entry.getKey(), imported);
        }

        for (Map.Entry<String, ImportedMedia> entry : importedByPath.entrySet()) {
            if (!entry.getValue().readyVariants().isEmpty()) {
                continue;
            }
            try {
                mediaFinalization.finalizeAsset(entry.getValue().assetId());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw mediaFailure();
            }
        }

        PortfolioImportMapper.MappedImport mapped = Objects.requireNonNull(
                transactions.execute(status -> attachContent(
                        payload, commands, importedByPath)),
                "portfolio content transaction returned no result");
        return new ImportReport(
                validation.sha256(),
                true,
                mapped.projects().size(),
                mapped.mediaCount(),
                mapped.tagCount(),
                validation.issues());
    }

    private Validation validate(
            Path input, Path assetRoot, String expectedSha256) {
        PortfolioImportReader.ReadResult read = reader.read(input);
        List<ImportIssue> issues = new ArrayList<>(read.issues());
        if (expectedSha256 == null
                || !SHA256.matcher(expectedSha256).matches()
                || !expectedSha256.equals(read.sha256())) {
            issues.add(new ImportIssue(
                    ImportIssue.Severity.STRUCTURE_ERROR,
                    "$",
                    "IMPORT_CHECKSUM_MISMATCH",
                    "Input checksum does not match the expected SHA-256."));
        }
        if (read.payload().isPresent()) {
            issues.addAll(validator.validate(read.payload().orElseThrow(), assetRoot));
        }
        List<ImportIssue> sorted = issues.stream().distinct().sorted(ISSUE_ORDER).toList();
        PortfolioImportSemantics.Counts counts = read.payload()
                .map(PortfolioImportSemantics::counts)
                .orElse(new PortfolioImportSemantics.Counts(0, 0, 0));
        return new Validation(
                read.payload().orElse(null), read.sha256(), sorted, counts);
    }

    private PortfolioImportMapper.MappedImport attachContent(
            PortfolioImportV1 payload,
            Map<String, ImportMediaCommand> commands,
            Map<String, ImportedMedia> importedByPath) {
        Map<UUID, PortfolioImportMapper.ReadyMedia> readyById = lockReadyMedia(
                importedByPath.values());
        LinkedHashMap<String, PortfolioImportMapper.ReadyMedia> readyByPath =
                new LinkedHashMap<>();
        for (Map.Entry<String, ImportMediaCommand> entry : commands.entrySet()) {
            ImportedMedia imported = importedByPath.get(entry.getKey());
            PortfolioImportMapper.ReadyMedia ready = readyById.get(imported.assetId());
            if (ready == null
                    || !imported.originalSha256().equals(ready.asset().sha256())
                    || !exactMediaCopy(ready.asset(), entry.getValue())) {
                throw mediaFailure();
            }
            readyByPath.put(entry.getKey(), ready);
        }

        PortfolioImportMapper.MappedImport mapped = mapper.map(payload, readyByPath);
        requireEmptyWorkspace();
        replaceSeedSite(mapped.site());
        taxonomy.replaceImportTags(mapped.tags());
        for (ProjectWorkspaceDto project : mapped.projects()) {
            projects.insert(project);
        }
        assertEquivalent(mapped);
        return mapped;
    }

    private Map<UUID, PortfolioImportMapper.ReadyMedia> lockReadyMedia(
            java.util.Collection<ImportedMedia> imported) {
        List<UUID> ids = imported.stream()
                .map(ImportedMedia::assetId)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        LinkedHashMap<UUID, PortfolioImportMapper.ReadyMedia> ready =
                new LinkedHashMap<>();
        for (UUID id : ids) {
            MediaAssetDescriptor asset = mediaQueries.requireReadyAsset(id);
            if (!id.equals(asset.assetId())
                    || !"READY".equals(asset.status())
                    || asset.variants().isEmpty()) {
                throw mediaFailure();
            }
            List<MediaVariantDescriptor> summaries = asset.variants().stream()
                    .sorted(Comparator.comparing(MediaVariantDescriptor::variantName))
                    .toList();
            HashSet<String> names = new HashSet<>();
            List<MediaVariantDescriptor> selected = new ArrayList<>();
            for (MediaVariantDescriptor summary : summaries) {
                if (!names.add(summary.variantName())
                        || !id.equals(summary.assetId())
                        || !"READY".equals(summary.status())) {
                    throw mediaFailure();
                }
                MediaVariantDescriptor variant =
                        mediaQueries.requireReadyVariant(id, summary.variantName());
                if (!summary.equals(variant)
                        || !id.equals(variant.assetId())
                        || !"READY".equals(variant.status())) {
                    throw mediaFailure();
                }
                selected.add(variant);
            }
            ready.put(id, new PortfolioImportMapper.ReadyMedia(asset, selected));
        }
        return ready;
    }

    private static boolean exactMediaCopy(
            MediaAssetDescriptor asset, ImportMediaCommand command) {
        if (!asset.copyByLocale().keySet().equals(MEDIA_LOCALES)) {
            return false;
        }
        String sourceUrl = command.sourceUrl() == null
                ? null
                : command.sourceUrl().toASCIIString();
        for (String locale : MEDIA_LOCALES) {
            MediaCopyDescriptor actual = asset.copyByLocale().get(locale);
            if (actual == null
                    || !Objects.equals(actual.alt(), command.altByLocale().get(locale))
                    || actual.caption() != null
                    || !Objects.equals(actual.credit(), command.credit())
                    || !Objects.equals(actual.sourceUrl(), sourceUrl)) {
                return false;
            }
        }
        return true;
    }

    private void replaceSeedSite(SiteWorkspaceDto mapped) {
        try {
            sites.replace(mapped, 0);
        } catch (DomainException failure) {
            if ("CONTENT_VERSION_CONFLICT".equals(failure.code())
                    && !isCompleteEmptySeed(sites.require())) {
                throw alreadyCompleted();
            }
            throw failure;
        }
    }

    private void assertEquivalent(PortfolioImportMapper.MappedImport expected) {
        if (!expected.site().equals(sites.require())
                || !expected.projects().equals(projects.findAll())
                || !taxonomy.findSkills().isEmpty()
                || !equivalentTags(expected.tags(), taxonomy.findTags())) {
            throw equivalenceFailure();
        }
    }

    private static boolean equivalentTags(
            List<ProjectWorkspaceDto.TaxonomyRef> expected,
            List<TaxonomyWorkspaceDto> actual) {
        Map<UUID, TagSemantic> expectedById = new LinkedHashMap<>();
        for (ProjectWorkspaceDto.TaxonomyRef tag : expected) {
            if (expectedById.put(
                            tag.id(),
                            new TagSemantic(
                                    tag.id(), tag.normalizedKey(), tag.names(), 0))
                    != null) {
                return false;
            }
        }
        Map<UUID, TagSemantic> actualById = new LinkedHashMap<>();
        for (TaxonomyWorkspaceDto tag : actual) {
            if (actualById.put(
                            tag.id(),
                            new TagSemantic(
                                    tag.id(), tag.normalizedKey(), tag.names(), tag.version()))
                    != null) {
                return false;
            }
        }
        return expectedById.equals(actualById);
    }

    private void requireEmptyWorkspace() {
        if (!isCompleteEmptySeed(sites.require())
                || !taxonomy.findTags().isEmpty()
                || !taxonomy.findSkills().isEmpty()
                || !projects.findAll().isEmpty()) {
            throw alreadyCompleted();
        }
    }

    private static boolean isCompleteEmptySeed(SiteWorkspaceDto site) {
        return SiteWorkspaceDto.SITE_ID.equals(site.siteId())
                && site.version() == 0
                && "".equals(site.monogram())
                && "".equals(site.email())
                && site.identity().isEmpty()
                && site.seo().isEmpty()
                && site.accessibility().isEmpty()
                && site.navigation().isEmpty()
                && site.hero() == null
                && site.about().isEmpty()
                && site.facts().isEmpty()
                && site.profileSkills().isEmpty()
                && site.work().isEmpty()
                && site.roadmap() != null
                && site.roadmap().header().isEmpty()
                && site.roadmap().stages().isEmpty()
                && site.contact().isEmpty()
                && site.privacy().isEmpty()
                && site.socialLinks().isEmpty()
                && site.resumes().isEmpty();
    }

    private static TreeMap<String, ImportMediaCommand> mediaCommands(
            PortfolioImportV1 payload, Path assetRoot) {
        TreeMap<String, ImportMediaCommand> commands = new TreeMap<>();
        PortfolioImportV1.HeroAsset hero = payload.heroAsset();
        commands.putIfAbsent(
                hero.image(),
                new ImportMediaCommand(
                        assetRoot,
                        hero.image(),
                        "HERO",
                        hero.objectPosition(),
                        hero.credit(),
                        hero.sourceUrl(),
                        altByLocale(hero.alt())));
        for (PortfolioImportV1.ProjectAsset asset : payload.projectAssets()) {
            commands.putIfAbsent(
                    asset.image(),
                    new ImportMediaCommand(
                            assetRoot,
                            asset.image(),
                            "COVER",
                            asset.objectPosition(),
                            asset.credit(),
                            asset.sourceUrl(),
                            altByLocale(asset.alt())));
        }
        return commands;
    }

    private static Map<String, String> altByLocale(
            Map<LocaleCode, String> localized) {
        return Map.of(
                LocaleCode.ZH_CN.value(), localized.get(LocaleCode.ZH_CN),
                LocaleCode.EN.value(), localized.get(LocaleCode.EN));
    }

    private static void rejectAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PORTFOLIO_IMPORT_TRANSACTION_FORBIDDEN");
        }
    }

    private static DomainException alreadyCompleted() {
        return new DomainException(
                "IMPORT_ALREADY_COMPLETED", HttpStatus.CONFLICT, Map.of());
    }

    private static IllegalStateException mediaFailure() {
        return new IllegalStateException("PORTFOLIO_IMPORT_MEDIA_NOT_READY");
    }

    private static IllegalStateException equivalenceFailure() {
        return new IllegalStateException("PORTFOLIO_IMPORT_EQUIVALENCE_FAILED");
    }

    private record Validation(
            PortfolioImportV1 payload,
            String sha256,
            List<ImportIssue> issues,
            PortfolioImportSemantics.Counts counts) {
        private Validation {
            issues = List.copyOf(issues);
        }

        private boolean hasStructureErrors() {
            return issues.stream().anyMatch(issue ->
                    issue.severity() == ImportIssue.Severity.STRUCTURE_ERROR);
        }

        private ImportReport report(boolean committed) {
            return new ImportReport(
                    sha256,
                    committed,
                    counts.projectCount(),
                    counts.mediaCount(),
                    counts.tagCount(),
                    issues);
        }
    }

    private record TagSemantic(
            UUID id,
            String normalizedKey,
            Map<LocaleCode, String> names,
            long version) {}
}
