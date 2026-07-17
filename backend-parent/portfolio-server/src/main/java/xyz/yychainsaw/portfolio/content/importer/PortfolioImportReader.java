package xyz.yychainsaw.portfolio.content.importer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import xyz.yychainsaw.portfolio.api.admin.media.StrictHttpsSourceUrl;
import xyz.yychainsaw.portfolio.common.error.DomainException;

@Component
@Conditional(PortfolioImportRuntimeCondition.class)
public final class PortfolioImportReader {
    private static final String JSON_INVALID = "IMPORT_JSON_INVALID";
    private static final String REQUIRED_MISSING = "IMPORT_REQUIRED_FIELD_MISSING";
    private static final String LOCALE_INVALID = "IMPORT_LOCALE_SET_INVALID";
    private static final Set<String> LOCALES = Set.of("zh-CN", "en");
    private static final Pattern EXTERNAL_KEY =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");
    private static final Comparator<ImportIssue> ISSUE_ORDER =
            Comparator.comparing(ImportIssue::severity)
                    .thenComparing(ImportIssue::path)
                    .thenComparing(ImportIssue::code)
                    .thenComparing(ImportIssue::message);

    private final ObjectMapper mapper;

    public PortfolioImportReader(ObjectMapper mapper) {
        this.mapper = mapper.copy();
        this.mapper.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    ReadResult read(Path input) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(input);
        } catch (IOException | RuntimeException failure) {
            throw new DomainException(
                    "IMPORT_INPUT_UNREADABLE", HttpStatus.UNPROCESSABLE_ENTITY, Map.of());
        }
        String sha256 = sha256(bytes);

        JsonNode tree;
        try {
            tree = mapper.readTree(bytes);
            if (tree == null) {
                return invalidDocument(sha256);
            }
        } catch (JsonProcessingException | RuntimeException malformed) {
            return invalidDocument(sha256);
        } catch (IOException impossibleForByteArray) {
            return invalidDocument(sha256);
        }

        List<ImportIssue> issues = new ArrayList<>();
        validateDocument(tree, issues);
        List<ImportIssue> sorted = sortedIssues(issues);
        if (!sorted.isEmpty()) {
            return new ReadResult(Optional.empty(), sha256, sorted);
        }

        try {
            PortfolioImportV1 payload = mapper.treeToValue(tree, PortfolioImportV1.class);
            return new ReadResult(Optional.of(payload), sha256, List.of());
        } catch (JsonProcessingException bindingFailure) {
            String path = bindingPath(bindingFailure);
            return new ReadResult(
                    Optional.empty(),
                    sha256,
                    List.of(issue(path, JSON_INVALID, "JSON value is invalid")));
        } catch (RuntimeException bindingFailure) {
            return new ReadResult(
                    Optional.empty(),
                    sha256,
                    List.of(issue("$", JSON_INVALID, "JSON value is invalid")));
        }
    }

    private static ReadResult invalidDocument(String sha256) {
        return new ReadResult(
                Optional.empty(),
                sha256,
                List.of(issue("$", JSON_INVALID, "JSON document is invalid")));
    }

    private static void validateDocument(JsonNode node, List<ImportIssue> issues) {
        ObjectNode root = requireRootObject(node, issues);
        if (root == null) {
            return;
        }
        unknownFields(
                root,
                "",
                Set.of("schemaVersion", "identity", "heroAsset", "projectAssets", "portfolioContent"),
                issues);
        requiredInteger(root, "", "schemaVersion", issues);
        validateIdentity(requiredObject(root, "", "identity", issues), "identity", issues);
        validateHeroAsset(requiredObject(root, "", "heroAsset", issues), "heroAsset", issues);
        validateProjectAssets(requiredArray(root, "", "projectAssets", issues), issues);
        validatePortfolioContent(
                requiredObject(root, "", "portfolioContent", issues), issues);
    }

    private static ObjectNode requireRootObject(JsonNode node, List<ImportIssue> issues) {
        if (!node.isObject()) {
            issues.add(issue("$", JSON_INVALID, "JSON root must be an object"));
            return null;
        }
        return (ObjectNode) node;
    }

    private static void validateIdentity(
            ObjectNode value, String path, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        unknownFields(value, path, Set.of("monogram", "nameZh", "nameEn", "email"), issues);
        String monogram = requiredString(value, path, "monogram", issues);
        if (monogram != null && monogram.isEmpty()) {
            issues.add(issue(child(path, "monogram"), JSON_INVALID, "String value is invalid"));
        }
        requiredString(value, path, "nameZh", issues);
        requiredString(value, path, "nameEn", issues);
        validateEmail(requiredString(value, path, "email", issues), child(path, "email"), issues);
    }

    private static void validateHeroAsset(
            ObjectNode value, String path, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        unknownFields(
                value,
                path,
                Set.of("image", "objectPosition", "credit", "sourceUrl", "alt"),
                issues);
        requiredString(value, path, "image", issues);
        requireNonempty(value, path, "objectPosition", issues);
        requireNonempty(value, path, "credit", issues);
        validateSourceUrl(
                requiredString(value, path, "sourceUrl", issues), child(path, "sourceUrl"), issues);
        validateLocalizedStrings(requiredObject(value, path, "alt", issues), child(path, "alt"), issues);
    }

    private static void validateProjectAssets(JsonNode value, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        for (int index = 0; index < value.size(); index++) {
            String path = "projectAssets[" + index + "]";
            ObjectNode asset = arrayObject(value.get(index), path, issues);
            if (asset == null) {
                continue;
            }
            unknownFields(
                    asset,
                    path,
                    Set.of("id", "image", "layout", "objectPosition", "credit", "sourceUrl", "alt"),
                    issues);
            validateExternalKey(requiredString(asset, path, "id", issues), child(path, "id"), issues);
            requiredString(asset, path, "image", issues);
            String layout = requiredString(asset, path, "layout", issues);
            if (layout != null && !Set.of("wide", "standard").contains(layout)) {
                issues.add(issue(child(path, "layout"), JSON_INVALID, "Enum value is invalid"));
            }
            requireNonempty(asset, path, "objectPosition", issues);
            requireNonempty(asset, path, "credit", issues);
            validateSourceUrl(
                    requiredString(asset, path, "sourceUrl", issues),
                    child(path, "sourceUrl"),
                    issues);
            validateLocalizedStrings(
                    requiredObject(asset, path, "alt", issues), child(path, "alt"), issues);
        }
    }

    private static void validatePortfolioContent(ObjectNode value, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        validateLocaleSet(value, "portfolioContent", issues);
        for (String locale : List.of("zh-CN", "en")) {
            String path = "portfolioContent." + locale;
            validatePortfolioCopy(
                    requiredObject(value, "portfolioContent", locale, issues), path, issues);
        }
    }

    private static void validatePortfolioCopy(
            ObjectNode value, String path, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        unknownFields(
                value,
                path,
                Set.of("seo", "a11y", "nav", "hero", "about", "work", "projects", "roadmap", "contact"),
                issues);
        validateStringObject(
                requiredObject(value, path, "seo", issues),
                child(path, "seo"),
                List.of("title", "description"),
                issues);
        validateStringObject(
                requiredObject(value, path, "a11y", issues),
                child(path, "a11y"),
                List.of(
                        "skip",
                        "primaryNav",
                        "mobileNav",
                        "openMenu",
                        "closeMenu",
                        "language",
                        "backToTop",
                        "projectTags"),
                issues);
        validateStringObject(
                requiredObject(value, path, "nav", issues),
                child(path, "nav"),
                List.of("about", "work", "roadmap", "contact"),
                issues);
        validateStringObject(
                requiredObject(value, path, "hero", issues),
                child(path, "hero"),
                List.of(
                        "eyebrow",
                        "displayName",
                        "secondaryName",
                        "role",
                        "headline",
                        "introduction",
                        "availability",
                        "primaryCta",
                        "secondaryCta",
                        "visualLabel",
                        "stageLabel"),
                issues);
        validateAbout(requiredObject(value, path, "about", issues), child(path, "about"), issues);
        validateStringObject(
                requiredObject(value, path, "work", issues),
                child(path, "work"),
                List.of(
                        "label",
                        "title",
                        "introduction",
                        "imageNotice",
                        "openSlotLabel",
                        "openSlotTitle",
                        "openSlotText",
                        "openSlotMeta"),
                issues);
        validateProjects(requiredArray(value, path, "projects", issues), child(path, "projects"), issues);
        validateRoadmap(
                requiredObject(value, path, "roadmap", issues), child(path, "roadmap"), issues);
        validateContact(
                requiredObject(value, path, "contact", issues), child(path, "contact"), issues);
    }

    private static void validateAbout(ObjectNode value, String path, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        List<String> fields = List.of(
                "label", "title", "statement", "focusLabel", "focusTitle", "focusIntro", "facts", "skills");
        unknownFields(value, path, Set.copyOf(fields), issues);
        for (String field : fields.subList(0, 6)) {
            requiredString(value, path, field, issues);
        }
        validateStringObjectArray(
                requiredArray(value, path, "facts", issues),
                child(path, "facts"),
                List.of("label", "value"),
                issues);
        validateStringObjectArray(
                requiredArray(value, path, "skills", issues),
                child(path, "skills"),
                List.of("name", "status"),
                issues);
    }

    private static void validateProjects(
            JsonNode value, String path, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        for (int index = 0; index < value.size(); index++) {
            String itemPath = path + "[" + index + "]";
            ObjectNode project = arrayObject(value.get(index), itemPath, issues);
            if (project == null) {
                continue;
            }
            unknownFields(
                    project,
                    itemPath,
                    Set.of("id", "number", "status", "eyebrow", "title", "summary", "tags"),
                    issues);
            validateExternalKey(
                    requiredString(project, itemPath, "id", issues), child(itemPath, "id"), issues);
            for (String field : List.of("number", "status", "eyebrow", "title", "summary")) {
                requiredString(project, itemPath, field, issues);
            }
            JsonNode tags = requiredArray(project, itemPath, "tags", issues);
            if (tags != null) {
                for (int tag = 0; tag < tags.size(); tag++) {
                    requireArrayString(tags.get(tag), child(itemPath, "tags") + "[" + tag + "]", issues);
                }
            }
        }
    }

    private static void validateRoadmap(
            ObjectNode value, String path, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        unknownFields(value, path, Set.of("label", "title", "introduction", "stages"), issues);
        for (String field : List.of("label", "title", "introduction")) {
            requiredString(value, path, field, issues);
        }
        JsonNode stages = requiredArray(value, path, "stages", issues);
        if (stages == null) {
            return;
        }
        String stagesPath = child(path, "stages");
        for (int index = 0; index < stages.size(); index++) {
            String itemPath = stagesPath + "[" + index + "]";
            ObjectNode stage = arrayObject(stages.get(index), itemPath, issues);
            if (stage == null) {
                continue;
            }
            unknownFields(
                    stage,
                    itemPath,
                    Set.of("id", "number", "period", "title", "summary", "outcomes"),
                    issues);
            validateExternalKey(
                    requiredString(stage, itemPath, "id", issues), child(itemPath, "id"), issues);
            for (String field : List.of("number", "period", "title", "summary")) {
                requiredString(stage, itemPath, field, issues);
            }
            JsonNode outcomes = requiredArray(stage, itemPath, "outcomes", issues);
            if (outcomes != null) {
                String outcomesPath = child(itemPath, "outcomes");
                for (int outcome = 0; outcome < outcomes.size(); outcome++) {
                    requireArrayString(outcomes.get(outcome), outcomesPath + "[" + outcome + "]", issues);
                }
            }
        }
    }

    private static void validateContact(
            ObjectNode value, String path, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        List<String> fields = List.of(
                "label", "title", "introduction", "emailLabel", "email", "workCta", "roadmapCta", "footerNote");
        unknownFields(value, path, Set.copyOf(fields), issues);
        for (String field : fields) {
            String text = requiredString(value, path, field, issues);
            if ("email".equals(field)) {
                validateEmail(text, child(path, field), issues);
            }
        }
    }

    private static void validateStringObject(
            ObjectNode value, String path, List<String> fields, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        unknownFields(value, path, Set.copyOf(fields), issues);
        for (String field : fields) {
            requiredString(value, path, field, issues);
        }
    }

    private static void validateStringObjectArray(
            JsonNode value, String path, List<String> fields, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        for (int index = 0; index < value.size(); index++) {
            String itemPath = path + "[" + index + "]";
            ObjectNode item = arrayObject(value.get(index), itemPath, issues);
            validateStringObject(item, itemPath, fields, issues);
        }
    }

    private static void validateLocalizedStrings(
            ObjectNode value, String path, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        validateLocaleSet(value, path, issues);
        for (String locale : List.of("zh-CN", "en")) {
            requiredString(value, path, locale, issues);
        }
    }

    private static void validateLocaleSet(
            ObjectNode value, String path, List<ImportIssue> issues) {
        unknownFields(value, path, LOCALES, issues);
        Set<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(LOCALES)) {
            issues.add(issue(path, LOCALE_INVALID, "Locale set is invalid"));
        }
    }

    private static ObjectNode requiredObject(
            ObjectNode parent, String path, String field, List<ImportIssue> issues) {
        JsonNode value = required(parent, path, field, issues);
        if (value == null) {
            return null;
        }
        String valuePath = child(path, field);
        if (!value.isObject()) {
            issues.add(issue(valuePath, JSON_INVALID, "JSON value must be an object"));
            return null;
        }
        return (ObjectNode) value;
    }

    private static JsonNode requiredArray(
            ObjectNode parent, String path, String field, List<ImportIssue> issues) {
        JsonNode value = required(parent, path, field, issues);
        if (value == null) {
            return null;
        }
        String valuePath = child(path, field);
        if (!value.isArray()) {
            issues.add(issue(valuePath, JSON_INVALID, "JSON value must be an array"));
            return null;
        }
        return value;
    }

    private static String requiredString(
            ObjectNode parent, String path, String field, List<ImportIssue> issues) {
        JsonNode value = required(parent, path, field, issues);
        if (value == null) {
            return null;
        }
        String valuePath = child(path, field);
        if (!value.isTextual()) {
            issues.add(issue(valuePath, JSON_INVALID, "JSON value must be a string"));
            return null;
        }
        return value.textValue();
    }

    private static void requiredInteger(
            ObjectNode parent, String path, String field, List<ImportIssue> issues) {
        JsonNode value = required(parent, path, field, issues);
        if (value != null && (!value.isIntegralNumber() || !value.canConvertToInt())) {
            issues.add(issue(child(path, field), JSON_INVALID, "JSON value must be an integer"));
        }
    }

    private static JsonNode required(
            ObjectNode parent, String path, String field, List<ImportIssue> issues) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            issues.add(issue(child(path, field), REQUIRED_MISSING, "Required field is missing"));
            return null;
        }
        return value;
    }

    private static ObjectNode arrayObject(
            JsonNode value, String path, List<ImportIssue> issues) {
        if (!value.isObject()) {
            issues.add(issue(path, JSON_INVALID, "JSON value must be an object"));
            return null;
        }
        return (ObjectNode) value;
    }

    private static void requireArrayString(
            JsonNode value, String path, List<ImportIssue> issues) {
        if (value == null || value.isNull()) {
            issues.add(issue(path, REQUIRED_MISSING, "Required field is missing"));
        } else if (!value.isTextual()) {
            issues.add(issue(path, JSON_INVALID, "JSON value must be a string"));
        }
    }

    private static void requireNonempty(
            ObjectNode parent, String path, String field, List<ImportIssue> issues) {
        String value = requiredString(parent, path, field, issues);
        if (value != null && value.isEmpty()) {
            issues.add(issue(child(path, field), JSON_INVALID, "String value is invalid"));
        }
    }

    private static void validateExternalKey(
            String value, String path, List<ImportIssue> issues) {
        if (value != null && !EXTERNAL_KEY.matcher(value).matches()) {
            issues.add(issue(path, JSON_INVALID, "Identifier format is invalid"));
        }
    }

    private static void validateEmail(String value, String path, List<ImportIssue> issues) {
        if (value != null && !EMAIL.matcher(value).matches()) {
            issues.add(issue(path, JSON_INVALID, "Email format is invalid"));
        }
    }

    private static void validateSourceUrl(
            String value, String path, List<ImportIssue> issues) {
        if (value == null) {
            return;
        }
        if (!value.startsWith("https://")) {
            issues.add(issue(path, JSON_INVALID, "Source URL format is invalid"));
            return;
        }
        try {
            StrictHttpsSourceUrl.requireValidNullable(value);
        } catch (IllegalArgumentException malformed) {
            issues.add(issue(path, JSON_INVALID, "Source URL format is invalid"));
        }
    }

    private static void unknownFields(
            ObjectNode object, String path, Set<String> allowed, List<ImportIssue> issues) {
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                issues.add(issue(child(path, field), JSON_INVALID, "Unknown field is not allowed"));
            }
        });
    }

    private static List<ImportIssue> sortedIssues(List<ImportIssue> issues) {
        ArrayList<ImportIssue> result = new ArrayList<>(new LinkedHashSet<>(issues));
        result.sort(ISSUE_ORDER);
        return List.copyOf(result);
    }

    private static String bindingPath(JsonProcessingException exception) {
        if (!(exception instanceof JsonMappingException mapping) || mapping.getPath().isEmpty()) {
            return "$";
        }
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : mapping.getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "$" : path.toString();
    }

    private static String child(String parent, String field) {
        return parent == null || parent.isEmpty() ? field : parent + "." + field;
    }

    private static ImportIssue issue(String path, String code, String message) {
        return new ImportIssue(ImportIssue.Severity.STRUCTURE_ERROR, path, code, message);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    record ReadResult(
            Optional<PortfolioImportV1> payload, String sha256, List<ImportIssue> issues) {
        ReadResult {
            payload = Optional.ofNullable(payload).orElseGet(Optional::empty);
            issues = List.copyOf(issues);
        }
    }
}
