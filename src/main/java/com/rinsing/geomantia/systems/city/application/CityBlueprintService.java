package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprint;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintContractException;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityBlueprintReasonCode;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseTerrainFieldCodec;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

/** Prepares a read-only D4 decision context and accepts exactly one AI Blueprint submission per context. */
public final class CityBlueprintService {
    public static final String CONTEXT_SCHEMA = "city_blueprint_context.v0.10";
    public static final String SNAPSHOT_SCHEMA = "city_blueprint_catalog_snapshot.v0.10";
    public static final String REPORT_SCHEMA = "city_blueprint_validation_report.v0.4";
    public static final String TRACE_SCHEMA = "city_blueprint_submission_trace.v0.4";

    private final CityBlueprintCodec codec = new CityBlueprintCodec();
    private final CityBlueprintValidator validator = new CityBlueprintValidator();

    public JsonObject prepare(Path debugRoot, String runId, String cityId,
                               JsonObject terraSenseProfileSource, JsonObject templateCatalogSource,
                               JsonObject blueprintReferenceCatalog) throws IOException {
        Path runDir = requireRunDirectory(debugRoot, runId);
        JsonObject seed = loadCitySeed(runDir, cityId);
        Path d3Path = d3Path(runDir, cityId);
        String d3Raw = requireFile(d3Path, "CITY_BLUEPRINT_D3_NOT_FOUND");
        JsonObject d3 = parseObject(d3Raw, "CITY_BLUEPRINT_D3_INVALID");
        if (!cityId.equals(string(d3, "cityId"))) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_D3_CITY_MISMATCH");
        }
        String d3Schema = string(d3, "schemaVersion");
        if (!Set.of("city_landform_review.v0.1", "city_landform_review.v0.2").contains(d3Schema)) {
            throw new CityBlueprintContractException(CityBlueprintReasonCode.CITY_BLUEPRINT_D3_SCHEMA_UNSUPPORTED,
                    "$.d3ReviewPackage.schemaVersion", "Unsupported D3 review schema: " + d3Schema);
        }
        if ("partial".equalsIgnoreCase(string(d3, "status"))) {
            throw new CityBlueprintContractException(CityBlueprintReasonCode.CITY_BLUEPRINT_D3_PARTIAL,
                    "$.d3ReviewPackage.status", "A partial D3 artifact cannot prepare a formal Blueprint context.");
        }
        requireD3SiteReview(runDir, cityId, seed, d3Raw);

        CityStructureProfileCatalog.ImportedCatalog structureCatalog =
                CityStructureProfileCatalog.importCatalog(runDir, terraSenseProfileSource);
        JsonObject templateCatalogJson = loadJsonSource(debugRoot, runDir, templateCatalogSource,
                "catalog", "catalogPath", "templateCatalogPath", "path");
        CityTemplateCatalog templateCatalog = new CityTemplateCatalogLoader().load(templateCatalogJson);
        CityBlueprintReferenceCatalog references = CityBlueprintReferenceCatalog.parse(
                blueprintReferenceCatalog, templateCatalog);
        requireStructureRefsInCatalog(references, structureCatalog);

        Path terrainFieldPath = CityTestRunLayout.open(runDir, cityId)
                .stepDirectory(CityTestRunLayout.LAND_USE)
                .resolve("land_use_terrain_field.json");
        String terrainFieldRaw = requireFile(terrainFieldPath, "CITY_BLUEPRINT_TERRAIN_FIELD_NOT_FOUND");
        LandUseTerrainField terrainField = new LandUseTerrainFieldCodec().fromJson(
                parseObject(terrainFieldRaw, "CITY_BLUEPRINT_TERRAIN_FIELD_INVALID"));
        validateTerrainField(cityId, d3, terrainField);

        Path outputDir = outputDirectory(runDir, cityId);
        Files.createDirectories(outputDir);
        CityBlueprint.ArtifactRef terrainFieldRef = artifactRef(debugRoot, terrainFieldPath,
                terrainField.schemaVersion(), terrainFieldRaw);
        Path snapshotPath = outputDir.resolve("city_blueprint_catalog_snapshot.json");
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schemaVersion", SNAPSHOT_SCHEMA);
        snapshot.add("structureCatalog", structureCatalog.asJson());
        snapshot.add("templateCatalog", templateCatalogJson.deepCopy());
        snapshot.add("referenceCatalog", references.json().deepCopy());
        snapshot.add("terrainFieldRef", artifactRefJson(terrainFieldRef));
        writeAtomic(snapshotPath, snapshot);

        CityBlueprint.ArtifactRef d3Ref = artifactRef(debugRoot, d3Path, string(d3, "schemaVersion"), d3Raw);
        String snapshotRaw = Files.readString(snapshotPath);
        CityBlueprint.ArtifactRef snapshotRef = artifactRef(debugRoot, snapshotPath, SNAPSHOT_SCHEMA, snapshotRaw);

        JsonObject contextCore = new JsonObject();
        contextCore.addProperty("schemaVersion", CONTEXT_SCHEMA);
        contextCore.addProperty("runId", runId);
        contextCore.addProperty("cityId", cityId);
        contextCore.add("sourceD3Ref", artifactRefJson(d3Ref));
        contextCore.add("catalogSnapshotRef", artifactRefJson(snapshotRef));
        contextCore.addProperty("generationSeedSuggestion", stableSeed(cityId, d3Ref.contentHash(),
                snapshotRef.contentHash()));
        JsonObject boundary = new JsonObject();
        boundary.addProperty("contextPreparationCountsAsAiCityDesignCall", false);
        boundary.addProperty("maximumAiCityDesignSubmissions", 1);
        boundary.addProperty("postSubmissionAiCandidateRequestsAllowed", false);
        contextCore.add("decisionBoundary", boundary);
        contextCore.add("citySeed", seed.deepCopy());
        contextCore.add("d3ReviewPackage", d3.deepCopy());
        contextCore.add("catalogSnapshot", snapshot.deepCopy());
        String contextId = sha256(CityJson.GSON.toJson(contextCore));

        JsonObject context = contextCore.deepCopy();
        context.addProperty("contextId", contextId);
        context.addProperty("preparedAt", Instant.now().toString());
        Path contextPath = outputDir.resolve("city_blueprint_context.json");
        writeAtomic(contextPath, context);

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("contextId", contextId);
        response.addProperty("aiCityDesignCallCount", 0);
        response.add("cityBlueprintContext", context);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("cityBlueprintContext", ref(debugRoot, contextPath));
        artifacts.addProperty("cityBlueprintCatalogSnapshot", ref(debugRoot, snapshotPath));
        response.add("artifacts", artifacts);
        return response;
    }

    public JsonObject submit(Path debugRoot, String runId, String cityId, String contextId,
                             JsonObject blueprintJson) throws IOException {
        Path runDir = requireRunDirectory(debugRoot, runId);
        Path outputDir = outputDirectory(runDir, cityId);
        Path contextPath = outputDir.resolve("city_blueprint_context.json");
        Path snapshotPath = outputDir.resolve("city_blueprint_catalog_snapshot.json");
        Path reportPath = outputDir.resolve("city_blueprint_validation_report.json");
        Path tracePath = outputDir.resolve("city_blueprint_submission_trace.json");
        Path blueprintPath = outputDir.resolve("city_blueprint.json");
        JsonObject context = readObject(contextPath, CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_NOT_FOUND);
        if (!CONTEXT_SCHEMA.equals(string(context, "schemaVersion"))
                || !contextId.equals(string(context, "contextId"))
                || !contextId.equals(contextIdentity(context))
                || !cityId.equals(string(context, "cityId"))
                || !context.has("catalogSnapshotRef")
                || !context.get("catalogSnapshotRef").isJsonObject()
                || !SNAPSHOT_SCHEMA.equals(string(context.getAsJsonObject("catalogSnapshotRef"),
                "schemaVersion"))) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE, "$context",
                    "The submitted contextId is not the current prepared context.", false);
        }
        if (Files.isRegularFile(tracePath)) {
            JsonObject priorTrace = parseObject(Files.readString(tracePath), "CITY_BLUEPRINT_TRACE_INVALID");
            if (contextId.equals(string(priorTrace, "contextId"))
                    && integer(priorTrace, "aiCityDesignSubmissionCount", 0) >= 1) {
                return alreadyConsumed(debugRoot, cityId, contextId, context);
            }
        }

        Path claimPath = outputDir.resolve(".city_blueprint_submission_"
                + contextId.replace("sha256:", "") + ".claim");
        JsonObject claim = trace(cityId, contextId, "received", 1, context, new JsonArray());
        claim.addProperty("attemptConsumed", true);
        try {
            writeNew(claimPath, claim);
        } catch (FileAlreadyExistsException exception) {
            return alreadyConsumed(debugRoot, cityId, contextId, context);
        }

        CityBlueprint blueprint;
        try {
            blueprint = codec.read(blueprintJson);
        } catch (CityBlueprintContractException exception) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath, exception.reasonCode(),
                    exception.fieldPath(), exception.getMessage(), true);
        } catch (RuntimeException exception) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_JSON_INVALID, "$", exception.getMessage(), true);
        }

        CityBlueprint.ArtifactRef expectedD3 = artifactRefFromJson(context.getAsJsonObject("sourceD3Ref"));
        CityBlueprint.ArtifactRef expectedSnapshot = artifactRefFromJson(
                context.getAsJsonObject("catalogSnapshotRef"));
        if (!hashStillCurrent(debugRoot, expectedD3) || !hashStillCurrent(debugRoot, expectedSnapshot)) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE, "$context",
                    "A frozen D3 or catalog snapshot artifact changed after context preparation.", true);
        }
        JsonObject snapshot = readObject(snapshotPath, CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE);
        if (!SNAPSHOT_SCHEMA.equals(string(snapshot, "schemaVersion"))) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE, "$.catalogSnapshot.schemaVersion",
                    "Unsupported frozen catalog snapshot schema.", true);
        }
        if (!snapshotArtifactsCurrent(debugRoot, snapshot)) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE, "$.catalogSnapshot",
                    "The frozen D3 terrain field artifact changed after context preparation.", true);
        }
        JsonObject structureCatalog = snapshot.getAsJsonObject("structureCatalog");
        if (structureCatalog == null || !CityStructureProfileCatalog.SCHEMA_VERSION.equals(
                string(structureCatalog, "schemaVersion"))) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE, "$.catalogSnapshot.structureCatalog",
                    "Unsupported frozen structure semantic catalog schema.", true);
        }
        CityTemplateCatalog templates = new CityTemplateCatalogLoader().load(snapshot.getAsJsonObject("templateCatalog"));
        CityBlueprintReferenceCatalog references = CityBlueprintReferenceCatalog.parse(
                snapshot.getAsJsonObject("referenceCatalog"), templates);
        Set<String> patchRefs = patchRefs(context.getAsJsonObject("d3ReviewPackage"));
        CityBlueprintValidator.ValidationResult result = validator.validate(blueprint,
                new CityBlueprintValidator.ExpectedContext(cityId, expectedD3, expectedSnapshot, patchRefs),
                references);
        if (!result.valid()) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath, result.issues(), true);
        }

        JsonObject canonical = codec.write(blueprint);
        writeAtomic(blueprintPath, canonical);
        JsonObject report = report(cityId, contextId, true, new JsonArray());
        writeAtomic(reportPath, report);
        JsonObject trace = trace(cityId, contextId, "accepted", 1, context, new JsonArray());
        trace.addProperty("cityBlueprintHash", sha256(Files.readString(blueprintPath)));
        writeAtomic(tracePath, trace);
        return response(debugRoot, true, report, trace, blueprintPath, reportPath, tracePath);
    }

    private JsonObject failure(Path debugRoot, String cityId, String contextId, Path reportPath, Path tracePath,
                               CityBlueprintReasonCode reason, String path, String message,
                               boolean consumeSubmission) throws IOException {
        return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                java.util.List.of(new CityBlueprintValidator.Issue(reason, path, message)), consumeSubmission);
    }

    private JsonObject failure(Path debugRoot, String cityId, String contextId, Path reportPath, Path tracePath,
                               java.util.List<CityBlueprintValidator.Issue> issues,
                               boolean consumeSubmission) throws IOException {
        JsonArray issueArray = new JsonArray();
        issues.forEach(issue -> issueArray.add(issue.asJson()));
        JsonObject report = report(cityId, contextId, false, issueArray);
        if (consumeSubmission) writeAtomic(reportPath, report);
        // Trace intentionally contains only frozen identities, never the rejected Blueprint payload.
        int priorCount = 0;
        if (Files.isRegularFile(tracePath)) {
            try {
                priorCount = integer(parseObject(Files.readString(tracePath), "CITY_BLUEPRINT_TRACE_INVALID"),
                        "aiCityDesignSubmissionCount", 0);
            } catch (RuntimeException ignored) {
                priorCount = 0;
            }
        }
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", TRACE_SCHEMA);
        trace.addProperty("cityId", cityId);
        trace.addProperty("contextId", contextId);
        trace.addProperty("status", "rejected");
        trace.addProperty("aiCityDesignSubmissionCount", consumeSubmission ? Math.max(1, priorCount) : priorCount);
        trace.addProperty("contextPreparationCountsAsAiCityDesignCall", false);
        trace.addProperty("attemptConsumed", consumeSubmission);
        trace.addProperty("recordedAt", Instant.now().toString());
        Path contextPath = reportPath.getParent().resolve("city_blueprint_context.json");
        if (Files.isRegularFile(contextPath)) {
            try {
                JsonObject frozenContext = parseObject(Files.readString(contextPath), "CITY_BLUEPRINT_CONTEXT_INVALID");
                if (frozenContext.has("sourceD3Ref")) {
                    trace.add("sourceD3Ref", frozenContext.getAsJsonObject("sourceD3Ref").deepCopy());
                }
                if (frozenContext.has("catalogSnapshotRef")) {
                    trace.add("catalogSnapshotRef",
                            frozenContext.getAsJsonObject("catalogSnapshotRef").deepCopy());
                }
            } catch (RuntimeException ignored) {
                // The explicit context failure already appears in failureReasons.
            }
        }
        trace.add("failureReasons", issueArray.deepCopy());
        if (consumeSubmission) writeAtomic(tracePath, trace);
        return response(debugRoot, false, report, trace, null, consumeSubmission ? reportPath : null,
                consumeSubmission ? tracePath : null);
    }

    private static JsonObject alreadyConsumed(Path debugRoot, String cityId, String contextId,
                                               JsonObject context) {
        JsonObject issue = new JsonObject();
        issue.addProperty("reasonCode",
                CityBlueprintReasonCode.CITY_BLUEPRINT_AI_SUBMISSION_ALREADY_CONSUMED.name());
        issue.addProperty("fieldPath", "$context");
        issue.addProperty("message", "This context has already consumed its single AI city-design submission.");
        JsonArray issues = new JsonArray();
        issues.add(issue);
        JsonObject report = report(cityId, contextId, false, issues);
        JsonObject trace = trace(cityId, contextId, "rejected", 1, context, issues.deepCopy());
        trace.addProperty("attemptConsumed", false);
        return response(debugRoot, false, report, trace, null, null, null);
    }

    private static JsonObject report(String cityId, String contextId, boolean valid, JsonArray issues) {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", REPORT_SCHEMA);
        report.addProperty("cityId", cityId);
        report.addProperty("contextId", contextId);
        report.addProperty("valid", valid);
        report.addProperty("validatedAt", Instant.now().toString());
        report.add("issues", issues);
        return report;
    }

    private static JsonObject trace(String cityId, String contextId, String status, int count,
                                    JsonObject context, JsonArray failures) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schemaVersion", TRACE_SCHEMA);
        trace.addProperty("cityId", cityId);
        trace.addProperty("contextId", contextId);
        trace.addProperty("status", status);
        trace.addProperty("aiCityDesignSubmissionCount", count);
        trace.addProperty("contextPreparationCountsAsAiCityDesignCall", false);
        trace.addProperty("recordedAt", Instant.now().toString());
        trace.add("sourceD3Ref", context.getAsJsonObject("sourceD3Ref").deepCopy());
        trace.add("catalogSnapshotRef", context.getAsJsonObject("catalogSnapshotRef").deepCopy());
        trace.add("failureReasons", failures);
        return trace;
    }

    private static JsonObject response(Path debugRoot, boolean ok, JsonObject report, JsonObject trace,
                                       Path blueprintPath, Path reportPath, Path tracePath) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", ok);
        response.addProperty("aiCityDesignSubmissionCount",
                integer(trace, "aiCityDesignSubmissionCount", 0));
        response.add("validationReport", report);
        response.add("submissionTrace", trace);
        JsonObject artifacts = new JsonObject();
        if (blueprintPath != null) artifacts.addProperty("cityBlueprint", ref(debugRoot, blueprintPath));
        if (reportPath != null) {
            artifacts.addProperty("cityBlueprintValidationReport", ref(debugRoot, reportPath));
        }
        if (tracePath != null) artifacts.addProperty("cityBlueprintSubmissionTrace", ref(debugRoot, tracePath));
        response.add("artifacts", artifacts);
        return response;
    }

    private static void requireStructureRefsInCatalog(CityBlueprintReferenceCatalog refs,
                                                       CityStructureProfileCatalog.ImportedCatalog catalog) {
        Set<String> profiles = new LinkedHashSet<>();
        catalog.profiles().forEach(profile -> profiles.add(profile.semanticProfileId()));
        for (String ref : refs.structureRefs()) {
            if (!profiles.contains(ref)) {
                throw new CityBlueprintContractException(CityBlueprintReasonCode.CITY_BLUEPRINT_STRUCTURE_REF_UNKNOWN,
                        "$.structureRefs", "structureRef is not present in the frozen TerraSense catalog: " + ref);
            }
        }
    }

    private static void validateTerrainField(String cityId, JsonObject d3, LandUseTerrainField field) {
        if (!cityId.equals(field.cityId())) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_TERRAIN_FIELD_CITY_MISMATCH: expected "
                    + cityId + " but found " + field.cityId());
        }
        JsonObject grid = d3.has("grid") && d3.get("grid").isJsonObject()
                ? d3.getAsJsonObject("grid") : new JsonObject();
        int step = integer(grid, "cellStepBlocks", 0);
        int minX = integer(grid, "originBlockX", 0);
        int minZ = integer(grid, "originBlockZ", 0);
        int cellsX = integer(grid, "cellsX", 0);
        int cellsZ = integer(grid, "cellsZ", 0);
        BlockBounds expected = new BlockBounds(minX, minZ,
                minX + cellsX * step - 1, minZ + cellsZ * step - 1);
        if (step <= 0 || cellsX <= 0 || cellsZ <= 0 || field.cellStepBlocks() != step
                || !expected.equals(field.planningBounds())) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_TERRAIN_FIELD_GRID_MISMATCH");
        }
        if (field.cells().isEmpty()) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_TERRAIN_FIELD_EMPTY");
        }
    }

    private static boolean snapshotArtifactsCurrent(Path debugRoot, JsonObject snapshot) throws IOException {
        if (snapshot == null || !snapshot.has("terrainFieldRef")
                || !snapshot.get("terrainFieldRef").isJsonObject()) {
            return false;
        }
        CityBlueprint.ArtifactRef field = artifactRefFromJson(snapshot.getAsJsonObject("terrainFieldRef"));
        return LandUseTerrainField.CURRENT_SCHEMA_VERSION.equals(field.schemaVersion())
                && hashStillCurrent(debugRoot, field);
    }

    private static Set<String> patchRefs(JsonObject d3) {
        Set<String> refs = new LinkedHashSet<>();
        JsonArray patches = d3.has("landformPatches") && d3.get("landformPatches").isJsonArray()
                ? d3.getAsJsonArray("landformPatches") : new JsonArray();
        for (JsonElement element : patches) {
            if (element.isJsonObject()) {
                String ref = string(element.getAsJsonObject(), "landformPatchId");
                if (!ref.isBlank()) refs.add(ref);
            }
        }
        return Set.copyOf(refs);
    }

    private static JsonObject loadJsonSource(Path debugRoot, Path runDir, JsonObject source,
                                             String inlineKey, String... pathKeys) throws IOException {
        if (source == null) throw new IllegalArgumentException("CITY_BLUEPRINT_CATALOG_SOURCE_REQUIRED");
        if (source.has(inlineKey) && source.get(inlineKey).isJsonObject()) {
            return source.getAsJsonObject(inlineKey).deepCopy();
        }
        String raw = "";
        for (String key : pathKeys) {
            raw = string(source, key);
            if (!raw.isBlank()) break;
        }
        if (raw.isBlank()) throw new IllegalArgumentException("CITY_BLUEPRINT_CATALOG_SOURCE_REQUIRED");
        Path requested = Path.of(raw);
        Path path = requested.isAbsolute() ? requested.normalize() : runDir.resolve(requested).normalize();
        if (!Files.isRegularFile(path) && !requested.isAbsolute()) path = debugRoot.resolve(requested).normalize();
        return parseObject(requireFile(path, "CITY_BLUEPRINT_CATALOG_NOT_FOUND"),
                "CITY_BLUEPRINT_CATALOG_INVALID");
    }

    private static JsonObject loadCitySeed(Path runDir, String cityId) throws IOException {
        JsonObject registry = readObject(runDir.resolve("city_seed_registry.json"),
                CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_NOT_FOUND);
        JsonArray seeds = registry.has("citySeeds") && registry.get("citySeeds").isJsonArray()
                ? registry.getAsJsonArray("citySeeds") : new JsonArray();
        for (JsonElement element : seeds) {
            if (element.isJsonObject() && cityId.equals(string(element.getAsJsonObject(), "citySeedId"))) {
                return element.getAsJsonObject();
            }
        }
        throw new IllegalArgumentException("CITY_BLUEPRINT_CITY_SEED_NOT_FOUND: " + cityId);
    }

    private static void requireD3SiteReview(Path runDir, String cityId, JsonObject seed, String d3Raw)
            throws IOException {
        JsonObject source = seed.has("source") && seed.get("source").isJsonObject()
                ? seed.getAsJsonObject("source") : null;
        boolean required = "capital".equals(string(seed, "role")) && source != null
                && "ai_candidate_selection".equals(string(source, "siteSelectionMode"));
        if (!required) return;
        Path decisionPath = CityTestRunLayout.open(runDir, cityId).stepDirectory(CityTestRunLayout.D3)
                .resolve("city_site_review_decision.json");
        if (!Files.isRegularFile(decisionPath)) {
            throw new CityBlueprintContractException(CityBlueprintReasonCode.CITY_BLUEPRINT_D3_SITE_REVIEW_REQUIRED,
                    "$context", "The selected capital site must be accepted before preparing D4.");
        }
        JsonObject decision = parseObject(Files.readString(decisionPath), "CITY_BLUEPRINT_D3_SITE_REVIEW_INVALID");
        if (!"accept_selected_site".equals(string(decision, "decision"))) {
            throw new CityBlueprintContractException(CityBlueprintReasonCode.CITY_BLUEPRINT_D3_SITE_REVIEW_REQUIRED,
                    "$context", "The D3 site review did not accept the current site.");
        }
        if (!sha256(d3Raw).equals(string(decision, "d3PackageIdentity"))
                || !sha256(CityJson.GSON.toJson(seed)).equals(string(decision, "citySeedIdentity"))) {
            throw new CityBlueprintContractException(CityBlueprintReasonCode.CITY_BLUEPRINT_D3_SITE_REVIEW_STALE,
                    "$context", "The D3 site review does not match the current D3 package and city seed.");
        }
    }

    private static Path requireRunDirectory(Path debugRoot, String runId) {
        Path runDir = debugRoot.resolve(runId).normalize();
        if (!runDir.startsWith(debugRoot.normalize()) || !Files.isDirectory(runDir)) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_RUN_NOT_FOUND: " + runId);
        }
        return runDir;
    }

    private static Path d3Path(Path runDir, String cityId) {
        return CityTestRunLayout.open(runDir, cityId).stepDirectory(CityTestRunLayout.D3)
                .resolve("city_landform_review_package.json");
    }

    private static Path outputDirectory(Path runDir, String cityId) {
        return CityTestRunLayout.open(runDir, cityId).stepDirectory(CityTestRunLayout.BLUEPRINT);
    }

    private static JsonObject readObject(Path path, CityBlueprintReasonCode reason) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new CityBlueprintContractException(reason, "$", "Required artifact not found: " + path);
        }
        return parseObject(Files.readString(path), reason.name());
    }

    private static JsonObject parseObject(String raw, String reason) {
        try {
            JsonElement element = JsonParser.parseString(raw);
            if (!element.isJsonObject()) throw new IllegalStateException("root must be object");
            return element.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(reason + ": " + exception.getMessage(), exception);
        }
    }

    private static String requireFile(Path path, String reason) throws IOException {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException(reason + ": " + path);
        return Files.readString(path);
    }

    private static CityBlueprint.ArtifactRef artifactRef(Path debugRoot, Path path, String schema, String raw) {
        return new CityBlueprint.ArtifactRef(ref(debugRoot, path), schema, sha256(raw));
    }

    private static CityBlueprint.ArtifactRef artifactRefFromJson(JsonObject object) {
        return new CityBlueprint.ArtifactRef(string(object, "path"), string(object, "schemaVersion"),
                string(object, "contentHash"));
    }

    private static JsonObject artifactRefJson(CityBlueprint.ArtifactRef ref) {
        JsonObject object = new JsonObject();
        object.addProperty("path", ref.path());
        object.addProperty("schemaVersion", ref.schemaVersion());
        object.addProperty("contentHash", ref.contentHash());
        return object;
    }

    private static boolean hashStillCurrent(Path debugRoot, CityBlueprint.ArtifactRef artifact) throws IOException {
        Path path = debugRoot.resolve(artifact.path()).normalize();
        return path.startsWith(debugRoot.normalize()) && Files.isRegularFile(path)
                && artifact.contentHash().equals(sha256(Files.readString(path)));
    }

    private static void writeAtomic(Path path, JsonObject value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, CityJson.GSON.toJson(value));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeNew(Path path, JsonObject value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, CityJson.GSON.toJson(value), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static long stableSeed(String cityId, String d3Hash, String catalogHash) {
        String hex = sha256(cityId + "\n" + d3Hash + "\n" + catalogHash).substring("sha256:".length(), 20);
        return Long.parseUnsignedLong(hex, 16);
    }

    private static String contextIdentity(JsonObject context) {
        JsonObject core = context.deepCopy();
        core.remove("contextId");
        core.remove("preparedAt");
        return sha256(CityJson.GSON.toJson(core));
    }

    private static String sha256(String raw) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String ref(Path debugRoot, Path path) {
        return debugRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static String safe(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try { return object.get(key).getAsString(); } catch (RuntimeException ignored) { return ""; }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try { return object.get(key).getAsInt(); } catch (RuntimeException ignored) { return fallback; }
    }
}
