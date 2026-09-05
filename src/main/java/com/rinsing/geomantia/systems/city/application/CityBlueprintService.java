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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Prepares a read-only D4 decision context and validates replaceable Blueprint revisions. */
public final class CityBlueprintService {
    public static final String CONTEXT_SCHEMA = "city_blueprint_context";
    public static final String SNAPSHOT_SCHEMA = "city_blueprint_catalog_snapshot";
    public static final String REPORT_SCHEMA = "city_blueprint_validation_report";
    public static final String TRACE_SCHEMA = "city_blueprint_submission_trace";
    private static final Map<Path, Object> SUBMISSION_ARTIFACT_LOCKS = new ConcurrentHashMap<>();

    private final CityBlueprintCodec codec = new CityBlueprintCodec();
    private final CityBlueprintValidator validator = new CityBlueprintValidator();
    private final CityBlueprintFailureBudget failureBudget = new CityBlueprintFailureBudget();

    public JsonObject prepare(Path debugRoot, String runId, String cityId,
                               JsonObject terraSenseProfileSource, JsonObject templateCatalogSource,
                               JsonObject blueprintReferenceCatalog) throws IOException {
        return prepare(debugRoot, runId, cityId, terraSenseProfileSource, templateCatalogSource,
                blueprintReferenceCatalog, null);
    }

    public JsonObject prepare(Path debugRoot, String runId, String cityId,
                               JsonObject terraSenseProfileSource, JsonObject templateCatalogSource,
                               JsonObject blueprintReferenceCatalog, JsonObject patchReviewEvidence) throws IOException {
        Path runDir = requireRunDirectory(debugRoot, runId);
        JsonObject seed = loadCitySeed(runDir, cityId);
        Path d3Path = d3Path(runDir, cityId);
        String d3Raw = requireFile(d3Path, "CITY_BLUEPRINT_D3_NOT_FOUND");
        JsonObject d3 = parseObject(d3Raw, "CITY_BLUEPRINT_D3_INVALID");
        if (!cityId.equals(string(d3, "cityId"))) {
            throw new IllegalArgumentException("CITY_BLUEPRINT_D3_CITY_MISMATCH");
        }
        String d3Schema = string(d3, "schema");
        if (!"city_landform_review".equals(d3Schema)) {
            throw new CityBlueprintContractException(CityBlueprintReasonCode.CITY_BLUEPRINT_D3_SCHEMA_UNSUPPORTED,
                    "$.d3ReviewPackage.schema", "Unsupported D3 review schema: " + d3Schema);
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
                terrainField.schema(), terrainFieldRaw);
        Path snapshotPath = outputDir.resolve("city_blueprint_catalog_snapshot.json");
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schema", SNAPSHOT_SCHEMA);
        snapshot.add("structureCatalog", structureCatalog.asJson());
        snapshot.add("templateCatalog", templateCatalogJson.deepCopy());
        snapshot.add("referenceCatalog", references.json().deepCopy());
        snapshot.add("terrainFieldRef", artifactRefJson(terrainFieldRef));
        writeAtomic(snapshotPath, snapshot);

        CityBlueprint.ArtifactRef d3Ref = artifactRef(debugRoot, d3Path, string(d3, "schema"), d3Raw);
        String snapshotRaw = Files.readString(snapshotPath);
        CityBlueprint.ArtifactRef snapshotRef = artifactRef(debugRoot, snapshotPath, SNAPSHOT_SCHEMA, snapshotRaw);

        JsonObject contextCore = new JsonObject();
        contextCore.addProperty("schema", CONTEXT_SCHEMA);
        contextCore.addProperty("runId", runId);
        contextCore.addProperty("cityId", cityId);
        contextCore.add("sourceD3Ref", artifactRefJson(d3Ref));
        contextCore.add("catalogSnapshotRef", artifactRefJson(snapshotRef));
        if (patchReviewEvidence != null) {
            contextCore.add("patchReviewEvidence", patchReviewEvidence.deepCopy());
        }
        contextCore.addProperty("generationSeedSuggestion", stableSeed(cityId, d3Ref.contentHash(),
                snapshotRef.contentHash()));
        JsonObject boundary = new JsonObject();
        boundary.addProperty("contextPreparationCountsAsAiCityDesignCall", false);
        boundary.addProperty("maximumBlueprintCompileFailures",
                CityBlueprintFailureBudget.MAX_FAILURE_COUNT);
        boundary.addProperty("submissionValidationFailuresCountTowardBudget", false);
        boundary.addProperty("blueprintRevisionAllowedAfterCompileFailure", true);
        JsonObject recoveryBoundary = new JsonObject();
        recoveryBoundary.addProperty("evidenceSource", "tool_responses_and_returned_artifacts_only");
        recoveryBoundary.addProperty("sourceCodeInspectionAllowed", false);
        recoveryBoundary.addProperty("projectDocumentationInspectionAllowed", false);
        recoveryBoundary.addProperty("rawRunArtifactInspectionAllowed", false);
        boundary.add("agentRecoveryBoundary", recoveryBoundary);
        contextCore.add("decisionBoundary", boundary);
        contextCore.add("designGuide", CityDesignGuide.from(references));
        contextCore.add("citySeed", seed.deepCopy());
        contextCore.add("d3ReviewPackage", d3.deepCopy());
        contextCore.add("catalogSnapshot", snapshot.deepCopy());
        String contextId = sha256(CityJson.GSON.toJson(contextCore));

        JsonObject context = contextCore.deepCopy();
        context.addProperty("contextId", contextId);
        context.addProperty("preparedAt", Instant.now().toString());
        Path contextPath = outputDir.resolve("city_blueprint_context.json");
        writeAtomic(contextPath, context);
        JsonObject budget = failureBudget.initialize(debugRoot, runId, cityId, contextId);

        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("contextId", contextId);
        response.addProperty("aiCityDesignCallCount", 0);
        response.add("cityBlueprintContext", context);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("cityBlueprintContext", ref(debugRoot, contextPath));
        artifacts.addProperty("cityBlueprintCatalogSnapshot", ref(debugRoot, snapshotPath));
        response.add("artifacts", artifacts);
        CityBlueprintFailureBudget.attach(response, budget, debugRoot, runId, cityId);
        return response;
    }

    public JsonObject submit(Path debugRoot, String runId, String cityId, String contextId,
                             JsonObject blueprintJson) throws IOException {
        synchronized (submissionArtifactLock(outputDirectory(requireRunDirectory(debugRoot, runId), cityId))) {
            return submitLocked(debugRoot, runId, cityId, contextId, blueprintJson, false);
        }
    }

    public JsonObject submitDesign(Path debugRoot, String runId, String cityId, String contextId,
                                   JsonObject request) throws IOException {
        Path outputDir = outputDirectory(requireRunDirectory(debugRoot, runId), cityId);
        synchronized (submissionArtifactLock(outputDir)) {
            if (request.has("cityBlueprint") == request.has("blueprintPatch"))
                throw new IllegalArgumentException("CITY_BLUEPRINT_INPUT_EXACTLY_ONE_REQUIRED");
            String mode = request.has("proportionMode") ? request.get("proportionMode").getAsString() : "EXACT_SHARES";
            if (!java.util.Set.of("EXACT_SHARES", "RELATIVE_WEIGHTS").contains(mode))
                throw new IllegalArgumentException("CITY_BLUEPRINT_PROPORTION_MODE_INVALID");
            JsonObject input;
            if (request.has("blueprintPatch")) {
                if (!"EXACT_SHARES".equals(mode)) throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_REQUIRES_EXACT_SHARES");
                Path blueprintPath = outputDir.resolve("city_blueprint.json");
                if (!Files.isRegularFile(blueprintPath) || !request.has("baseBlueprintHash")
                        || !sha256(Files.readString(blueprintPath)).equals(request.get("baseBlueprintHash").getAsString()))
                    throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_BASE_STALE");
                JsonObject accepted = readObject(outputDir.resolve("city_blueprint_submission_trace.json"),
                        CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE);
                if (!contextId.equals(string(accepted, "contextId"))
                        || !request.get("baseBlueprintHash").getAsString().equals(string(accepted, "cityBlueprintHash")))
                    throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_BASE_STALE");
                input = CityBlueprintDesignInput.revise(readObject(blueprintPath,
                        CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE), request.getAsJsonArray("blueprintPatch"));
            } else {
                if (request.has("baseBlueprintHash")) throw new IllegalArgumentException("CITY_BLUEPRINT_PATCH_REQUIRED_WITH_BASE_HASH");
                input = request.getAsJsonObject("cityBlueprint");
            }
            return submitLocked(debugRoot, runId, cityId, contextId, input, "RELATIVE_WEIGHTS".equals(mode));
        }
    }

    private JsonObject submitLocked(Path debugRoot, String runId, String cityId, String contextId,
                                    JsonObject blueprintJson, boolean relativeWeights) throws IOException {
        Path runDir = requireRunDirectory(debugRoot, runId);
        Path outputDir = outputDirectory(runDir, cityId);
        Path contextPath = outputDir.resolve("city_blueprint_context.json");
        Path snapshotPath = outputDir.resolve("city_blueprint_catalog_snapshot.json");
        Path reportPath = outputDir.resolve("city_blueprint_last_rejection_report.json");
        Path tracePath = outputDir.resolve("city_blueprint_last_rejection_trace.json");
        Path acceptedReportPath = outputDir.resolve("city_blueprint_validation_report.json");
        Path acceptedTracePath = outputDir.resolve("city_blueprint_submission_trace.json");
        Path blueprintPath = outputDir.resolve("city_blueprint.json");
        JsonObject context = readObject(contextPath, CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_NOT_FOUND);
        JsonObject budget = failureBudget.initialize(debugRoot, runId, cityId, string(context, "contextId"));
        if (!CONTEXT_SCHEMA.equals(string(context, "schema"))
                || !contextId.equals(string(context, "contextId"))
                || !contextId.equals(contextIdentity(context))
                || !cityId.equals(string(context, "cityId"))
                || !context.has("catalogSnapshotRef")
                || !context.get("catalogSnapshotRef").isJsonObject()
                || !SNAPSHOT_SCHEMA.equals(string(context.getAsJsonObject("catalogSnapshotRef"),
                "schema"))) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE, "$context",
                    "The submitted contextId is not the current prepared context.", budget, runId);
        }
        if (CityBlueprintFailureBudget.exhausted(budget)) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_FAILURE_BUDGET_EXHAUSTED, "$context",
                    "This context has reached its five failed D4 compilations.", budget, runId);
        }
        if (CityBlueprintFailureBudget.succeeded(budget)) {
            budget = failureBudget.reopenForRevision(debugRoot, runId, cityId);
        }
        CityBlueprint.ArtifactRef expectedD3 = artifactRefFromJson(context.getAsJsonObject("sourceD3Ref"));
        CityBlueprint.ArtifactRef expectedSnapshot = artifactRefFromJson(
                context.getAsJsonObject("catalogSnapshotRef"));
        if (!hashStillCurrent(debugRoot, expectedD3) || !hashStillCurrent(debugRoot, expectedSnapshot)) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE, "$context",
                    "A frozen D3 or catalog snapshot artifact changed after context preparation.", budget, runId);
        }
        JsonObject snapshot = readObject(snapshotPath, CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE);
        if (!SNAPSHOT_SCHEMA.equals(string(snapshot, "schema"))) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE, "$.catalogSnapshot.schema",
                    "Unsupported frozen catalog snapshot schema.", budget, runId);
        }
        if (!snapshotArtifactsCurrent(debugRoot, snapshot)) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE, "$.catalogSnapshot",
                    "The frozen D3 terrain field artifact changed after context preparation.", budget, runId);
        }
        JsonObject structureCatalog = snapshot.getAsJsonObject("structureCatalog");
        if (structureCatalog == null || !CityStructureProfileCatalog.SCHEMA.equals(
                string(structureCatalog, "schema"))) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE, "$.catalogSnapshot.structureCatalog",
                    "Unsupported frozen structure semantic catalog schema.", budget, runId);
        }
        CityTemplateCatalog templates = new CityTemplateCatalogLoader().load(snapshot.getAsJsonObject("templateCatalog"));
        CityBlueprintReferenceCatalog references = CityBlueprintReferenceCatalog.parse(
                snapshot.getAsJsonObject("referenceCatalog"), templates);
        CityBlueprint blueprint;
        try {
                blueprint = codec.read(CityBlueprintDesignInput.bind(blueprintJson, context, relativeWeights));
        } catch (CityBlueprintContractException exception) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath, exception.reasonCode(),
                    exception.fieldPath(), exception.getMessage(), budget, runId);
        } catch (RuntimeException exception) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                    CityBlueprintReasonCode.CITY_BLUEPRINT_JSON_INVALID, "$", exception.getMessage(), budget, runId);
        }
        Set<String> patchRefs = patchRefs(context.getAsJsonObject("d3ReviewPackage"));
        CityBlueprintValidator.ValidationResult result = validator.validate(blueprint,
                new CityBlueprintValidator.ExpectedContext(cityId, expectedD3, expectedSnapshot, patchRefs),
                references);
        if (!result.valid()) {
            return failure(debugRoot, cityId, contextId, reportPath, tracePath, result.issues(), budget, runId);
        }
        budget = failureBudget.reopenForRevision(debugRoot, runId, cityId);

        JsonObject canonical = codec.write(blueprint);
        JsonObject report = report(cityId, contextId, true, new JsonArray());
        JsonObject trace = trace(cityId, contextId, "accepted", context, new JsonArray());
        synchronized (submissionArtifactLock(outputDir)) {
            writeAtomic(blueprintPath, canonical);
            writeAtomic(acceptedReportPath, report);
            trace.addProperty("cityBlueprintHash", sha256(Files.readString(blueprintPath)));
            writeAtomic(acceptedTracePath, trace);
        }
        JsonObject response = response(debugRoot, true, report, trace, blueprintPath,
                acceptedReportPath, acceptedTracePath);
        response.addProperty("nextAction", "city_compile_d4_blueprint");
        CityBlueprintFailureBudget.attach(response, budget, debugRoot, runId, cityId);
        return response;
    }

    private JsonObject failure(Path debugRoot, String cityId, String contextId, Path reportPath, Path tracePath,
                               CityBlueprintReasonCode reason, String path, String message,
                               JsonObject budget, String runId) throws IOException {
        return failure(debugRoot, cityId, contextId, reportPath, tracePath,
                java.util.List.of(new CityBlueprintValidator.Issue(reason, path, message)), budget, runId);
    }

    private JsonObject failure(Path debugRoot, String cityId, String contextId, Path reportPath, Path tracePath,
                               java.util.List<CityBlueprintValidator.Issue> issues,
                               JsonObject budget, String runId) throws IOException {
        JsonArray issueArray = new JsonArray();
        issues.forEach(issue -> issueArray.add(issue.asJson()));
        JsonObject report = report(cityId, contextId, false, issueArray);
        // Trace intentionally contains only frozen identities, never the rejected Blueprint payload.
        JsonObject trace = new JsonObject();
        trace.addProperty("schema", TRACE_SCHEMA);
        trace.addProperty("cityId", cityId);
        trace.addProperty("contextId", contextId);
        trace.addProperty("status", "rejected");
        trace.addProperty("contextPreparationCountsAsAiCityDesignCall", false);
        trace.addProperty("compilationFailureConsumed", false);
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
        synchronized (submissionArtifactLock(reportPath.getParent())) {
            writeAtomic(reportPath, report);
            writeAtomic(tracePath, trace);
        }
        JsonObject response = response(debugRoot, false, report, trace, null, reportPath, tracePath);
        String firstReason = issueArray.isEmpty() ? ""
                : string(issueArray.get(0).getAsJsonObject(), "reasonCode");
        response.addProperty("nextAction", CityBlueprintReasonCode.CITY_BLUEPRINT_CONTEXT_STALE.name()
                .equals(firstReason) ? "city_prepare_d4_blueprint_context"
                : CityBlueprintReasonCode.CITY_BLUEPRINT_FAILURE_BUDGET_EXHAUSTED.name().equals(firstReason)
                ? "stop_for_human_review" : "city_submit_d4_blueprint");
        CityBlueprintFailureBudget.attach(response, budget, debugRoot, runId, cityId);
        return response;
    }

    private static JsonObject report(String cityId, String contextId, boolean valid, JsonArray issues) {
        JsonObject report = new JsonObject();
        report.addProperty("schema", REPORT_SCHEMA);
        report.addProperty("cityId", cityId);
        report.addProperty("contextId", contextId);
        report.addProperty("valid", valid);
        report.addProperty("validatedAt", Instant.now().toString());
        report.add("issues", issues);
        return report;
    }

    private static JsonObject trace(String cityId, String contextId, String status,
                                    JsonObject context, JsonArray failures) {
        JsonObject trace = new JsonObject();
        trace.addProperty("schema", TRACE_SCHEMA);
        trace.addProperty("cityId", cityId);
        trace.addProperty("contextId", contextId);
        trace.addProperty("status", status);
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
        return LandUseTerrainField.SCHEMA.equals(field.schema())
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
        return new CityBlueprint.ArtifactRef(string(object, "path"), string(object, "schema"),
                string(object, "contentHash"));
    }

    private static JsonObject artifactRefJson(CityBlueprint.ArtifactRef ref) {
        JsonObject object = new JsonObject();
        object.addProperty("path", ref.path());
        object.addProperty("schema", ref.schema());
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

    private static Object submissionArtifactLock(Path outputDirectory) {
        return SUBMISSION_ARTIFACT_LOCKS.computeIfAbsent(outputDirectory.toAbsolutePath().normalize(),
                ignored -> new Object());
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
