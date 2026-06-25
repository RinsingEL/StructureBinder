package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.BuildableAreaMap;
import com.rinsing.geomantia.systems.city.domain.model.CityFunctionType;
import com.rinsing.geomantia.systems.city.domain.model.CityQualityReport;
import com.rinsing.geomantia.systems.city.domain.model.FunctionZoneMap;
import com.rinsing.geomantia.systems.city.domain.model.FunctionZonePatch;
import com.rinsing.geomantia.systems.city.domain.model.PlanningGrid;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class CityStructureD6Planner {
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int MIN_FIXED_LANDING_CANDIDATES = 64;
    private static final int FIXED_LANDING_CANDIDATES_PER_REQUESTED_COUNT = 16;
    private static final Set<String> FIXED_FORBIDDEN_FIELDS = Set.of(
            "targetVisibleAreaRatio", "minVisibleAreaRatio", "maxVisibleAreaRatio",
            "anchorBlock", "validatedAnchorBlock", "finalBlock", "candidateBlock",
            "x", "z", "blockX", "blockZ", "jigsawDepth", "maxDepth", "radius", "pieceBudget");
    private static final Set<String> SELECTION_FORBIDDEN_FIELDS = Set.of(
            "anchorBlock", "validatedAnchorBlock", "finalBlock", "candidateBlock",
            "x", "z", "blockX", "blockZ", "footprint");

    public Result plan(Path baseDirectory,
                       FunctionZoneMap zoneMap,
                       BuildableAreaMap buildableAreaMap,
                       JsonObject profileSource,
                       JsonObject structureChoicePlan,
                       JsonObject fixedPlacementSelectionPlan) throws IOException {
        if (baseDirectory == null) {
            baseDirectory = Path.of(".");
        }
        if (zoneMap == null) {
            throw new IllegalArgumentException("FunctionZoneMap is required for D6.");
        }
        if (buildableAreaMap == null) {
            throw new IllegalArgumentException("BuildableAreaMap is required for D6.");
        }
        if (!zoneMap.cityId().equals(buildableAreaMap.cityId())) {
            throw new IllegalArgumentException("FunctionZoneMap and BuildableAreaMap cityId mismatch.");
        }
        if (profileSource == null) {
            throw new IllegalArgumentException("TerraSenseStructureProfileSource is required for D6.");
        }

        ImportedCatalog catalog = importCatalog(baseDirectory, profileSource);
        ZoneContext zones = new ZoneContext(zoneMap, buildableAreaMap);
        JsonObject filteredCatalog = buildFilteredCatalog(zoneMap.cityId(), zones, catalog);
        JsonObject choicePlan = structureChoicePlan == null
                ? emptyChoicePlan(zoneMap.cityId())
                : structureChoicePlan.deepCopy();
        validateChoicePlan(zoneMap.cityId(), zones, filteredCatalog, choicePlan, catalog);

        JsonObject candidateSet = buildFixedPlacementCandidateSet(zoneMap.cityId(), zones, catalog, choicePlan);
        JsonObject selectionPlan = fixedPlacementSelectionPlan == null
                ? emptySelectionPlan(zoneMap.cityId())
                : fixedPlacementSelectionPlan.deepCopy();
        validateSelectionPlan(zoneMap.cityId(), candidateSet, selectionPlan);

        JsonObject plannedFixedMap = buildPlannedFixedPlacementMap(zoneMap.cityId(), zones, choicePlan,
                candidateSet, selectionPlan, catalog);
        JsonObject structurePoolMap = buildStructurePoolMap(zoneMap.cityId(), zones, choicePlan, plannedFixedMap, catalog);
        JsonObject quality = quality(catalog, filteredCatalog, candidateSet, plannedFixedMap, structurePoolMap);

        return new Result(
                profileSource.deepCopy(),
                catalog.asJson(),
                filteredCatalog,
                choicePlan,
                candidateSet,
                selectionPlan,
                plannedFixedMap,
                structurePoolMap,
                quality);
    }

    private ImportedCatalog importCatalog(Path baseDirectory, JsonObject source) throws IOException {
        String sourceType = stringValue(source, "sourceType", "debug_catalog");
        String catalogMode = stringValue(source, "catalogMode", sourceType.equals("debug_catalog") ? "debug" : "official");
        Path inputPath = switch (sourceType) {
            case "structure_profile_jsonl" -> resolve(baseDirectory, requiredString(source, "profilePath"));
            case "c3_5_compat_catalog" -> resolve(baseDirectory, requiredString(source, "compatCatalogPath"));
            case "debug_catalog" -> resolve(baseDirectory, requiredString(source, "debugCatalogPath"));
            default -> throw new IllegalArgumentException("Unsupported TerraSense sourceType: " + sourceType);
        };
        if (!Files.exists(inputPath)) {
            throw new IllegalArgumentException("TerraSense profile source not found: " + inputPath);
        }
        List<String> warnings = new ArrayList<>();
        List<String> needsReview = new ArrayList<>();
        List<StructureProfile> profiles = switch (sourceType) {
            case "structure_profile_jsonl" -> readJsonlProfiles(inputPath, source, catalogMode, warnings, needsReview);
            case "c3_5_compat_catalog" -> readCompatCatalog(inputPath, source, catalogMode, warnings, needsReview);
            case "debug_catalog" -> readDebugCatalog(inputPath, source, catalogMode, warnings, needsReview);
            default -> List.of();
        };
        Map<String, StructureProfile> byId = new LinkedHashMap<>();
        for (StructureProfile profile : profiles) {
            if (byId.putIfAbsent(profile.structureId(), profile) != null) {
                warnings.add("Duplicate structure profile ignored after first occurrence: " + profile.structureId());
            }
        }
        if ("debug".equals(catalogMode) && needsReview.stream()
                .anyMatch(reason -> reason.contains("fixed_footprint without reliable fixedFootprint"))) {
            throw new IllegalArgumentException("debug_catalog contains fixed_footprint entries without reliable fixedFootprint.");
        }
        JsonObject normalizedSource = source.deepCopy();
        normalizedSource.addProperty("resolvedPath", inputPath.toAbsolutePath().toString());
        return new ImportedCatalog(catalogMode, normalizedSource, new ArrayList<>(byId.values()), warnings, needsReview);
    }

    private List<StructureProfile> readJsonlProfiles(Path path, JsonObject source, String catalogMode,
                                                     List<String> warnings, List<String> needsReview) throws IOException {
        List<StructureProfile> profiles = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                profileFromJson(obj, source, catalogMode, "StructureProfile line " + lineNo, warnings, needsReview)
                        .ifPresent(profiles::add);
            }
        }
        return profiles;
    }

    private List<StructureProfile> readCompatCatalog(Path path, JsonObject source, String catalogMode,
                                                     List<String> warnings, List<String> needsReview) throws IOException {
        JsonElement root = JsonParser.parseString(Files.readString(path));
        JsonArray structures;
        if (root.isJsonArray()) {
            structures = root.getAsJsonArray();
        } else {
            JsonObject obj = root.getAsJsonObject();
            structures = arrayValue(obj, "structures", arrayValue(obj, "entries", new JsonArray()));
        }
        List<StructureProfile> profiles = new ArrayList<>();
        int index = 0;
        for (JsonElement elem : structures) {
            index++;
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject compat = elem.getAsJsonObject();
            if (!compat.has("structureId") && compat.has("structure_id")) {
                compat.addProperty("structureId", compat.get("structure_id").getAsString());
            }
            if (!compat.has("functionTags") && compat.has("function_candidates")) {
                compat.add("functionTags", compat.get("function_candidates"));
            }
            if (!compat.has("sourceProfileRef")) {
                compat.addProperty("sourceProfileRef", "compat catalog entry " + index);
            }
            profileFromJson(compat, source, catalogMode, "compat catalog entry " + index, warnings, needsReview)
                    .ifPresent(profiles::add);
        }
        return profiles;
    }

    private List<StructureProfile> readDebugCatalog(Path path, JsonObject source, String catalogMode,
                                                    List<String> warnings, List<String> needsReview) throws IOException {
        JsonObject obj = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        String declaredMode = stringValue(obj, "catalogMode", catalogMode);
        if (!"debug".equals(declaredMode)) {
            throw new IllegalArgumentException("debug_catalog source must reference catalogMode=debug.");
        }
        List<StructureProfile> profiles = new ArrayList<>();
        int index = 0;
        for (JsonElement elem : requiredArray(obj, "structures")) {
            index++;
            if (!elem.isJsonObject()) {
                continue;
            }
            profileFromJson(elem.getAsJsonObject(), source, "debug",
                    "debug catalog entry " + index, warnings, needsReview).ifPresent(profiles::add);
        }
        return profiles;
    }

    private Optional<StructureProfile> profileFromJson(JsonObject obj, JsonObject source, String catalogMode,
                                                       String sourceRef, List<String> warnings,
                                                       List<String> needsReview) {
        String structureId = firstString(obj, "structureId", "structure_id", "id");
        if (structureId.isBlank()) {
            needsReview.add(sourceRef + ": missing structureId");
            return Optional.empty();
        }
        if (!validResourceId(structureId)) {
            needsReview.add(sourceRef + ": invalid resource id " + structureId);
            return Optional.empty();
        }
        List<String> qualityTags = strings(firstArray(obj, "qualityTags", "quality_tags"));
        if (qualityTags.stream().anyMatch(tag -> tag.equalsIgnoreCase("reject"))) {
            needsReview.add(structureId + ": rejected by quality_tags");
            return Optional.empty();
        }
        if (!"debug".equals(catalogMode)) {
            String reviewState = firstString(obj, "reviewState", "review_state");
            if (!reviewState.isBlank() && !"approved".equalsIgnoreCase(reviewState)) {
                needsReview.add(structureId + ": review_state is not approved");
                return Optional.empty();
            }
        }

        JsonObject curation = objectValue(obj, "curation");
        String sourceProfileRef = firstString(obj, "sourceProfileRef", "source_profile_ref");
        if (sourceProfileRef.isBlank()) {
            sourceProfileRef = sourceRef;
        }
        String sampleType = firstString(obj, "sampleType", "sample_type");
        String placementKind = firstString(obj, "placementKind", "placement_kind");
        String placementCommand = firstString(obj, "placementCommand", "placement_command");
        if (placementKind.isBlank()) {
            placementKind = inferPlacementKind(sampleType, placementCommand);
        }
        String footprintMode = firstString(obj, "footprintMode", "footprint_mode");
        Footprint fixedFootprint = footprint(obj);
        int clearance = intValue(obj, "clearanceBlocks", intValue(obj, "clearance_blocks", 0));
        if (footprintMode.isBlank()) {
            footprintMode = fixedFootprint.valid() ? "fixed_footprint" : "variable_area";
        }
        int visibleAreaCost = intValue(obj, "visibleAreaCost", intValue(obj, "visible_area_cost", 0));
        if ("fixed_footprint".equals(footprintMode) && !fixedFootprint.valid()) {
            needsReview.add(structureId + ": fixed_footprint without reliable fixedFootprint");
            return Optional.empty();
        }
        if ("fixed_footprint".equals(footprintMode) && visibleAreaCost <= 0) {
            visibleAreaCost = expandedArea(fixedFootprint.boundsAt(0, 0, "NONE"), clearance);
        }
        AreaRange expectedAreaRange = areaRange(obj, fixedFootprint);
        OriginOffset footprintOriginOffset = originOffset(obj);
        List<String> functions = firstStrings(obj, curation, "functionTags", "function_tags", "function_affinity",
                "function_candidates");
        List<String> styles = firstStrings(obj, curation, "styleTags", "style_tags", "style_affinity");
        List<String> placementTags = firstStrings(obj, curation, "placementTags", "placement_tags", "placement_affinity");
        List<String> usageTags = firstStrings(obj, curation, "usageTags", "usage_tags", "usage_affinity");
        List<String> rotations = rotations(firstArray(obj, "allowedRotations", "allowed_rotations"));
        if (rotations.isEmpty()) {
            rotations = List.of("NONE");
        }
        String connectorsRef = firstString(obj, "connectorsRef", "connectors_ref");
        String profileType = firstString(obj, "profileType", "profile_type");
        if (profileType.isBlank()) {
            profileType = "single";
        }
        return Optional.of(new StructureProfile(structureId, sourceProfileRef, profileType, sampleType,
                placementKind, placementCommand, footprintMode, functions, styles, placementTags, usageTags,
                qualityTags, fixedFootprint, footprintOriginOffset, visibleAreaCost, rotations, clearance, expectedAreaRange,
                connectorsRef, stringValue(source, "catalogMode", catalogMode)));
    }

    private JsonObject buildFilteredCatalog(String cityId, ZoneContext zones, ImportedCatalog catalog) {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", "city_filtered_structure_catalog.v0.1");
        result.addProperty("cityId", cityId);
        result.addProperty("sourceProfileCatalogRef", "structure_profile_catalog.json");
        JsonArray zoneCatalogs = new JsonArray();
        for (ZoneInfo zone : zones.zones()) {
            JsonObject zoneObj = new JsonObject();
            zoneObj.addProperty("zonePatchId", zone.zonePatchId());
            zoneObj.addProperty("functionType", zone.functionType().contractName());
            zoneObj.addProperty("visibleBuildableArea", zone.buildableAreaBlocks());
            JsonArray fixed = new JsonArray();
            JsonArray variable = new JsonArray();
            JsonArray filteredOut = new JsonArray();
            JsonArray needsReview = new JsonArray();
            for (StructureProfile profile : catalog.profiles()) {
                FilterDecision decision = filter(profile, zone);
                switch (decision.status()) {
                    case "fixed" -> fixed.add(profileCandidate(profile));
                    case "variable" -> variable.add(profileCandidate(profile));
                    case "needs_review" -> needsReview.add(filterRecord(profile, decision.reasonCode(), decision.message()));
                    default -> filteredOut.add(filterRecord(profile, decision.reasonCode(), decision.message()));
                }
            }
            zoneObj.add("fixedCandidates", fixed);
            zoneObj.add("variableCandidates", variable);
            zoneObj.add("filteredOut", filteredOut);
            zoneObj.add("needsReview", needsReview);
            zoneCatalogs.add(zoneObj);
        }
        result.add("zoneCatalogs", zoneCatalogs);
        JsonObject metrics = new JsonObject();
        metrics.addProperty("structureCount", catalog.profiles().size());
        result.add("quality", new CityQualityReport(true, 100, List.of(), catalog.warnings(), catalog.needsReview(), metrics).asJson());
        return result;
    }

    private FilterDecision filter(StructureProfile profile, ZoneInfo zone) {
        if (!d7Eligible(profile)) {
            return new FilterDecision("filtered", "NOT_CONFIGURED_STRUCTURE_ENTRY",
                    "D7 candidates must be structure_assembly + minecraft_place_structure.");
        }
        if (!functionMatches(profile, zone.functionType())) {
            return new FilterDecision("filtered", "FUNCTION_TAG_MISMATCH",
                    "Structure function tags do not match zone functionType.");
        }
        if ("fixed_footprint".equals(profile.footprintMode())) {
            if (!profile.fixedFootprint().valid()) {
                return new FilterDecision("needs_review", "MISSING_FIXED_FOOTPRINT",
                        "Fixed footprint candidate has no reliable dimensions.");
            }
            if (profile.visibleAreaCost() > zone.buildableAreaBlocks()) {
                return new FilterDecision("filtered", "FIXED_FOOTPRINT_AREA_TOO_LARGE",
                        "Fixed footprint + clearance exceeds visible buildable area.");
            }
            if (firstFit(zone, profile).isEmpty()) {
                return new FilterDecision("filtered", "FIXED_FOOTPRINT_NO_BUILDABLE_LANDING",
                        "No allowed rotation can fit inside the buildable cell shape.");
            }
            return new FilterDecision("fixed", "", "");
        }
        if ("variable_area".equals(profile.footprintMode())) {
            return new FilterDecision("variable", "", "");
        }
        return new FilterDecision("needs_review", "UNKNOWN_FOOTPRINT_MODE", profile.footprintMode());
    }

    private void validateChoicePlan(String cityId, ZoneContext zones, JsonObject filteredCatalog,
                                    JsonObject choicePlan, ImportedCatalog catalog) {
        String planCity = requiredString(choicePlan, "cityId");
        if (!cityId.equals(planCity)) {
            throw new IllegalArgumentException("StructureChoicePlan cityId mismatch.");
        }
        Map<String, FilteredZone> filtered = filteredZones(filteredCatalog);
        Set<String> selectionIds = new HashSet<>();
        for (JsonElement elem : requiredArray(choicePlan, "zoneChoices")) {
            JsonObject zoneChoice = elem.getAsJsonObject();
            String zoneId = requiredString(zoneChoice, "zonePatchId");
            ZoneInfo zone = zones.byId(zoneId);
            if (zone == null) {
                throw new IllegalArgumentException("Unknown zonePatchId in StructureChoicePlan: " + zoneId);
            }
            String functionType = requiredString(zoneChoice, "functionType");
            if (!zone.functionType().contractName().equals(functionType)) {
                throw new IllegalArgumentException("StructureChoicePlan functionType mismatch for zone: " + zoneId);
            }
            FilteredZone filteredZone = filtered.get(zoneId);
            for (JsonElement fixedElem : arrayValue(zoneChoice, "fixedSelections", new JsonArray())) {
                JsonObject fixed = fixedElem.getAsJsonObject();
                rejectFields(fixed, FIXED_FORBIDDEN_FIELDS, "fixedSelections[]");
                String selectionId = requiredString(fixed, "selectionId");
                if (!selectionIds.add(selectionId)) {
                    throw new IllegalArgumentException("Duplicate structure selectionId: " + selectionId);
                }
                String structureId = requiredString(fixed, "structureId");
                if (!validResourceId(structureId)) {
                    throw new IllegalArgumentException("Invalid fixed structureId: " + structureId);
                }
                if (filteredZone == null || !filteredZone.fixedIds().contains(structureId)) {
                    throw new IllegalArgumentException("fixedSelections structureId is not a filtered fixed_footprint candidate: " + structureId);
                }
                int count = intValue(fixed, "count", 1);
                if (count < 1) {
                    throw new IllegalArgumentException("fixedSelections count must be positive: " + selectionId);
                }
                String failurePolicy = stringValue(fixed, "failurePolicy", "block_city");
                if (!Set.of("block_city", "degrade", "skip_with_warning").contains(failurePolicy)) {
                    throw new IllegalArgumentException("Unknown fixed failurePolicy: " + failurePolicy);
                }
                requireD7Eligible(catalog.byId().get(structureId), "fixedSelections");
            }
            for (JsonElement varElem : arrayValue(zoneChoice, "variableSelections", new JsonArray())) {
                JsonObject variable = varElem.getAsJsonObject();
                String selectionId = requiredString(variable, "selectionId");
                if (!selectionIds.add(selectionId)) {
                    throw new IllegalArgumentException("Duplicate structure selectionId: " + selectionId);
                }
                String structureId = requiredString(variable, "structureId");
                if (!validResourceId(structureId)) {
                    throw new IllegalArgumentException("Invalid variable structureId: " + structureId);
                }
                if (filteredZone == null || !filteredZone.variableIds().contains(structureId)) {
                    throw new IllegalArgumentException("variableSelections structureId is not a filtered variable_area candidate: " + structureId);
                }
                if (!variable.has("targetVisibleAreaRatio")) {
                    throw new IllegalArgumentException("variableSelections targetVisibleAreaRatio is required: " + selectionId);
                }
                double ratio = doubleValue(variable, "targetVisibleAreaRatio", 0);
                if (ratio <= 0 || ratio > 1.0) {
                    throw new IllegalArgumentException("variableSelections targetVisibleAreaRatio must be in (0, 1].");
                }
                int weight = intValue(variable, "weight", 1);
                if (weight < 1) {
                    throw new IllegalArgumentException("variableSelections weight must be positive: " + selectionId);
                }
                String materializationMode = stringValue(variable, "materializationMode", "minecraft_place_structure");
                if (!Set.of("minecraft_place_structure", "bounded_jigsaw").contains(materializationMode)) {
                    throw new IllegalArgumentException("Unknown variable materializationMode: " + materializationMode);
                }
                requireD7Eligible(catalog.byId().get(structureId), "variableSelections");
            }
        }
    }

    private JsonObject buildFixedPlacementCandidateSet(String cityId, ZoneContext zones, ImportedCatalog catalog,
                                                       JsonObject choicePlan) {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", "city_fixed_placement_candidate_set.v0.1");
        result.addProperty("cityId", cityId);
        result.addProperty("sourceChoicePlanRef", "structure_choice_plan.json");
        result.addProperty("previewRef", "fixed_placement_preview.png");
        JsonArray candidates = new JsonArray();
        List<String> hardBlocks = new ArrayList<>();
        for (JsonElement zoneElem : requiredArray(choicePlan, "zoneChoices")) {
            JsonObject zoneChoice = zoneElem.getAsJsonObject();
            ZoneInfo zone = zones.byId(requiredString(zoneChoice, "zonePatchId"));
            for (JsonElement fixedElem : arrayValue(zoneChoice, "fixedSelections", new JsonArray())) {
                JsonObject fixed = fixedElem.getAsJsonObject();
                String selectionId = requiredString(fixed, "selectionId");
                StructureProfile profile = catalog.byId().get(requiredString(fixed, "structureId"));
                int count = intValue(fixed, "count", 1);
                List<Landing> landings = generateLandings(zone, profile, selectionId, count);
                if (landings.isEmpty()) {
                    hardBlocks.add("NO_FIXED_LANDING_CANDIDATE:" + selectionId);
                }
                for (Landing landing : landings) {
                    candidates.add(landing.asJson());
                }
            }
        }
        result.add("candidates", candidates);
        result.add("quality", new CityQualityReport(hardBlocks.isEmpty(), hardBlocks.isEmpty() ? 100 : 0,
                hardBlocks, List.of(), List.of(), metric("candidateCount", candidates.size())).asJson());
        return result;
    }

    private List<Landing> generateLandings(ZoneInfo zone, StructureProfile profile, String selectionId, int count) {
        List<Landing> landings = new ArrayList<>();
        int wanted = Math.max(MIN_FIXED_LANDING_CANDIDATES,
                count * FIXED_LANDING_CANDIDATES_PER_REQUESTED_COUNT);
        for (String rotation : profile.allowedRotations()) {
            for (CellAnchor anchor : zone.buildableAnchors()) {
                BlockBounds footprint = profile.fixedFootprint().boundsAt(
                        anchor.blockMinX() + profile.footprintOriginOffset().x(),
                        anchor.blockMinZ() + profile.footprintOriginOffset().z(), rotation);
                BlockBounds clearance = expand(footprint, profile.clearanceBlocks());
                if (!zone.covers(clearance)) {
                    continue;
                }
                double score = landingScore(zone, footprint);
                JsonObject scoreBreakdown = new JsonObject();
                scoreBreakdown.addProperty("interiorScore", score);
                scoreBreakdown.addProperty("buildableFit", 1.0);
                landings.add(new Landing("", selectionId, zone.zonePatchId(), profile.structureId(),
                        footprint.center(), rotation, footprint, clearance, expandedArea(footprint, profile.clearanceBlocks()),
                        scoreBreakdown, List.of()));
            }
        }
        landings.sort(Comparator.comparingDouble(Landing::score).reversed()
                .thenComparing(Landing::landingCandidateId));
        List<Landing> ranked = landings.size() > wanted ? landings.subList(0, wanted) : landings;
        List<Landing> numbered = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Landing landing = ranked.get(i);
            String id = selectionId + "_cand_" + String.format(Locale.ROOT, "%02d", i + 1);
            numbered.add(new Landing(id, landing.selectionId(), landing.zonePatchId(), landing.structureId(),
                    landing.anchorBlock(), landing.rotation(), landing.footprint(), landing.clearanceFootprint(),
                    landing.visibleAreaCost(), landing.scoreBreakdown(), landing.riskFlags()));
        }
        return numbered;
    }

    private Optional<Landing> firstFit(ZoneInfo zone, StructureProfile profile) {
        for (String rotation : profile.allowedRotations()) {
            for (CellAnchor anchor : zone.buildableAnchors()) {
                BlockBounds footprint = profile.fixedFootprint().boundsAt(
                        anchor.blockMinX() + profile.footprintOriginOffset().x(),
                        anchor.blockMinZ() + profile.footprintOriginOffset().z(), rotation);
                if (zone.covers(expand(footprint, profile.clearanceBlocks()))) {
                    return Optional.of(new Landing("", "", zone.zonePatchId(), profile.structureId(), footprint.center(),
                            rotation, footprint, expand(footprint, profile.clearanceBlocks()),
                            expandedArea(footprint, profile.clearanceBlocks()), new JsonObject(), List.of()));
                }
            }
        }
        return Optional.empty();
    }

    private void validateSelectionPlan(String cityId, JsonObject candidateSet, JsonObject selectionPlan) {
        if (!cityId.equals(requiredString(selectionPlan, "cityId"))) {
            throw new IllegalArgumentException("FixedPlacementSelectionPlan cityId mismatch.");
        }
        Map<String, JsonObject> candidatesById = new HashMap<>();
        Map<String, String> selectionByCandidate = new HashMap<>();
        for (JsonElement elem : requiredArray(candidateSet, "candidates")) {
            JsonObject candidate = elem.getAsJsonObject();
            candidatesById.put(requiredString(candidate, "landingCandidateId"), candidate);
            selectionByCandidate.put(requiredString(candidate, "landingCandidateId"), requiredString(candidate, "selectionId"));
        }
        Set<String> seenSelections = new HashSet<>();
        for (JsonElement elem : requiredArray(selectionPlan, "selections")) {
            JsonObject selection = elem.getAsJsonObject();
            rejectFields(selection, SELECTION_FORBIDDEN_FIELDS, "FixedPlacementSelectionPlan.selections[]");
            String selectionId = requiredString(selection, "selectionId");
            String candidateId = requiredString(selection, "landingCandidateId");
            JsonObject candidate = candidatesById.get(candidateId);
            if (candidate == null) {
                throw new IllegalArgumentException("Unknown landingCandidateId: " + candidateId);
            }
            if (!selectionId.equals(selectionByCandidate.get(candidateId))) {
                throw new IllegalArgumentException("landingCandidateId does not belong to selectionId: " + candidateId);
            }
            if (!seenSelections.add(selectionId)) {
                throw new IllegalArgumentException("Duplicate fixed landing selection for selectionId: " + selectionId);
            }
        }
    }

    private JsonObject buildPlannedFixedPlacementMap(String cityId, ZoneContext zones, JsonObject choicePlan,
                                                     JsonObject candidateSet, JsonObject selectionPlan,
                                                     ImportedCatalog catalog) {
        Map<String, JsonObject> candidatesById = new HashMap<>();
        for (JsonElement elem : requiredArray(candidateSet, "candidates")) {
            JsonObject candidate = elem.getAsJsonObject();
            candidatesById.put(requiredString(candidate, "landingCandidateId"), candidate);
        }
        Map<String, JsonObject> fixedBySelection = fixedSelectionsById(choicePlan);
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", "city_planned_fixed_placement_map.v0.1");
        result.addProperty("cityId", cityId);
        result.addProperty("sourceCandidateSetRef", "fixed_placement_candidate_set.json");
        result.addProperty("sourceSelectionPlanRef", "fixed_placement_selection_plan.json");
        JsonArray placements = new JsonArray();
        List<String> hardBlocks = new ArrayList<>();
        List<BlockBounds> occupied = new ArrayList<>();
        int index = 1;
        for (JsonElement elem : requiredArray(selectionPlan, "selections")) {
            JsonObject selection = elem.getAsJsonObject();
            String selectionId = requiredString(selection, "selectionId");
            JsonObject fixed = fixedBySelection.get(selectionId);
            JsonObject candidate = candidatesById.get(requiredString(selection, "landingCandidateId"));
            if (fixed == null || candidate == null) {
                hardBlocks.add("UNKNOWN_FIXED_SELECTION:" + selectionId);
                continue;
            }
            BlockBounds clearanceFootprint = bounds(requiredObject(candidate, "clearanceFootprint"));
            boolean conflict = occupied.stream().anyMatch(clearanceFootprint::overlaps);
            ZoneInfo zone = zones.byId(requiredString(candidate, "zonePatchId"));
            if (conflict) {
                hardBlocks.add("FIXED_FOOTPRINT_CONFLICT:" + selectionId);
                continue;
            }
            if (zone == null || !zone.covers(clearanceFootprint)) {
                hardBlocks.add("FIXED_FOOTPRINT_OUT_OF_BUILDABLE_AREA:" + selectionId);
                continue;
            }
            occupied.add(clearanceFootprint);
            StructureProfile profile = catalog.byId().get(requiredString(candidate, "structureId"));
            JsonObject placement = new JsonObject();
            placement.addProperty("placementId", "fixed_" + String.format(Locale.ROOT, "%02d", index++));
            placement.addProperty("selectionId", selectionId);
            placement.addProperty("landingCandidateId", requiredString(candidate, "landingCandidateId"));
            placement.addProperty("zonePatchId", requiredString(candidate, "zonePatchId"));
            placement.addProperty("structureId", requiredString(candidate, "structureId"));
            placement.add("validatedAnchorBlock", requiredObject(candidate, "anchorBlock"));
            placement.addProperty("rotation", requiredString(candidate, "rotation"));
            placement.add("footprint", requiredObject(candidate, "footprint"));
            placement.add("clearanceFootprint", requiredObject(candidate, "clearanceFootprint"));
            placement.addProperty("visibleAreaCost", intValue(candidate, "visibleAreaCost", 0));
            placement.addProperty("priority", intValue(fixed, "priority", 100));
            placement.addProperty("failurePolicy", stringValue(fixed, "failurePolicy", "block_city"));
            placement.addProperty("placementKind", profile == null ? "" : profile.placementKind());
            placement.addProperty("sampleType", profile == null ? "" : profile.sampleType());
            placement.addProperty("placementCommand", profile == null ? "" : profile.placementCommand());
            placement.add("footprintOriginOffset", profile == null
                    ? OriginOffset.ZERO.asJson()
                    : profile.footprintOriginOffset().asJson());
            placements.add(placement);
        }
        result.add("placements", placements);
        result.add("remainingVisibleAreaByZone", remainingAreas(zones, placements));
        result.add("quality", new CityQualityReport(hardBlocks.isEmpty(), hardBlocks.isEmpty() ? 100 : 0,
                hardBlocks, List.of(), List.of(), metric("plannedFixedCount", placements.size())).asJson());
        if (!hardBlocks.isEmpty()) {
            throw new IllegalArgumentException("D6 fixed placement hard blocks: " + hardBlocks);
        }
        return result;
    }

    private JsonObject buildStructurePoolMap(String cityId, ZoneContext zones, JsonObject choicePlan,
                                             JsonObject plannedFixedMap, ImportedCatalog catalog) {
        JsonObject remaining = requiredObject(plannedFixedMap, "remainingVisibleAreaByZone");
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", "city_structure_pool_map.v0.1");
        result.addProperty("cityId", cityId);
        result.addProperty("sourceChoicePlanRef", "structure_choice_plan.json");
        result.addProperty("sourcePlannedFixedPlacementMapRef", "planned_fixed_placement_map.json");
        JsonArray pools = new JsonArray();
        List<String> warnings = new ArrayList<>();
        for (JsonElement elem : requiredArray(choicePlan, "zoneChoices")) {
            JsonObject zoneChoice = elem.getAsJsonObject();
            String zoneId = requiredString(zoneChoice, "zonePatchId");
            ZoneInfo zone = zones.byId(zoneId);
            JsonObject pool = new JsonObject();
            pool.addProperty("zonePatchId", zoneId);
            pool.addProperty("functionType", zone.functionType().contractName());
            int remainingArea = remaining.has(zoneId) ? remaining.get(zoneId).getAsInt() : zone.buildableAreaBlocks();
            pool.addProperty("remainingVisibleArea", remainingArea);
            JsonArray variables = new JsonArray();
            double ratioSum = 0;
            for (JsonElement varElem : arrayValue(zoneChoice, "variableSelections", new JsonArray())) {
                JsonObject variable = varElem.getAsJsonObject().deepCopy();
                StructureProfile profile = catalog.byId().get(requiredString(variable, "structureId"));
                double ratio = doubleValue(variable, "targetVisibleAreaRatio", 0);
                ratioSum += ratio;
                variable.addProperty("targetVisibleAreaBlocks", Math.max(1, (int) Math.round(remainingArea * ratio)));
                variable.addProperty("placementKind", profile.placementKind());
                variable.addProperty("sampleType", profile.sampleType());
                variable.addProperty("placementCommand", profile.placementCommand());
                variable.addProperty("materializationMode", stringValue(variable,
                        "materializationMode", "minecraft_place_structure"));
                variable.add("startFootprint", profile.expectedAreaRange().startFootprint().asJson());
                variable.add("expectedAreaRange", profile.expectedAreaRange().asJson());
                variables.add(variable);
            }
            if (ratioSum > 1.0) {
                warnings.add("Variable targetVisibleAreaRatio sum exceeds 1.0 for zone " + zoneId);
            }
            pool.add("variableSelections", variables);
            pool.add("fallbackTags", new JsonArray());
            pool.addProperty("zoneReason", stringValue(zoneChoice, "zoneReason", ""));
            pools.add(pool);
        }
        result.add("zonePools", pools);
        result.add("quality", new CityQualityReport(true, Math.max(0, 100 - warnings.size() * 5),
                List.of(), warnings, List.of(), metric("zonePoolCount", pools.size())).asJson());
        return result;
    }

    private JsonObject quality(ImportedCatalog catalog, JsonObject filteredCatalog, JsonObject candidateSet,
                               JsonObject plannedFixedMap, JsonObject structurePoolMap) {
        List<String> warnings = new ArrayList<>(catalog.warnings());
        List<String> needsReview = new ArrayList<>(catalog.needsReview());
        List<String> hardBlocks = new ArrayList<>();
        hardBlocks.addAll(strings(arrayValue(requiredObject(candidateSet, "quality"), "hardBlocks", new JsonArray())));
        hardBlocks.addAll(strings(arrayValue(requiredObject(plannedFixedMap, "quality"), "hardBlocks", new JsonArray())));
        JsonObject metrics = new JsonObject();
        metrics.addProperty("catalogStructureCount", catalog.profiles().size());
        metrics.addProperty("zoneCatalogCount", requiredArray(filteredCatalog, "zoneCatalogs").size());
        metrics.addProperty("fixedCandidateCount", requiredArray(candidateSet, "candidates").size());
        metrics.addProperty("plannedFixedCount", requiredArray(plannedFixedMap, "placements").size());
        metrics.addProperty("zonePoolCount", requiredArray(structurePoolMap, "zonePools").size());
        return new CityQualityReport(hardBlocks.isEmpty(), hardBlocks.isEmpty() ? 100 : 0,
                hardBlocks, warnings, needsReview, metrics).asJson();
    }

    private JsonObject remainingAreas(ZoneContext zones, JsonArray placements) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        for (ZoneInfo zone : zones.zones()) {
            remaining.put(zone.zonePatchId(), zone.buildableAreaBlocks());
        }
        for (JsonElement elem : placements) {
            JsonObject placement = elem.getAsJsonObject();
            String zoneId = requiredString(placement, "zonePatchId");
            remaining.computeIfPresent(zoneId, (id, value) -> Math.max(0, value - intValue(placement, "visibleAreaCost", 0)));
        }
        JsonObject obj = new JsonObject();
        remaining.forEach(obj::addProperty);
        return obj;
    }

    private JsonObject emptyChoicePlan(String cityId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_structure_choice_plan.v0.1");
        obj.addProperty("cityId", cityId);
        obj.add("zoneChoices", new JsonArray());
        return obj;
    }

    private JsonObject emptySelectionPlan(String cityId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_fixed_placement_selection_plan.v0.1");
        obj.addProperty("cityId", cityId);
        obj.add("selections", new JsonArray());
        return obj;
    }

    private Map<String, FilteredZone> filteredZones(JsonObject filteredCatalog) {
        Map<String, FilteredZone> result = new HashMap<>();
        for (JsonElement elem : requiredArray(filteredCatalog, "zoneCatalogs")) {
            JsonObject zone = elem.getAsJsonObject();
            result.put(requiredString(zone, "zonePatchId"), new FilteredZone(
                    ids(arrayValue(zone, "fixedCandidates", new JsonArray())),
                    ids(arrayValue(zone, "variableCandidates", new JsonArray()))));
        }
        return result;
    }

    private Map<String, JsonObject> fixedSelectionsById(JsonObject choicePlan) {
        Map<String, JsonObject> result = new HashMap<>();
        for (JsonElement zoneElem : requiredArray(choicePlan, "zoneChoices")) {
            JsonObject zoneChoice = zoneElem.getAsJsonObject();
            for (JsonElement fixedElem : arrayValue(zoneChoice, "fixedSelections", new JsonArray())) {
                JsonObject fixed = fixedElem.getAsJsonObject();
                result.put(requiredString(fixed, "selectionId"), fixed);
            }
        }
        return result;
    }

    private Set<String> ids(JsonArray candidates) {
        Set<String> ids = new HashSet<>();
        for (JsonElement elem : candidates) {
            ids.add(requiredString(elem.getAsJsonObject(), "structureId"));
        }
        return ids;
    }

    private void requireD7Eligible(StructureProfile profile, String context) {
        if (!d7Eligible(profile)) {
            throw new IllegalArgumentException(context + " references a non configured structure entry: "
                    + (profile == null ? "<missing>" : profile.structureId()));
        }
    }

    private boolean d7Eligible(StructureProfile profile) {
        return profile != null
                && "structure_assembly".equals(profile.sampleType())
                && "minecraft_place_structure".equals(profile.placementKind())
                && validResourceId(profile.structureId());
    }

    private boolean functionMatches(StructureProfile profile, CityFunctionType type) {
        if (profile.functionTags().isEmpty() || profile.functionTags().contains("any")) {
            return true;
        }
        return profile.functionTags().stream()
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .anyMatch(tag -> tag.equals(type.contractName()));
    }

    private JsonObject profileCandidate(StructureProfile profile) {
        JsonObject obj = new JsonObject();
        obj.addProperty("structureId", profile.structureId());
        obj.addProperty("footprintMode", profile.footprintMode());
        obj.addProperty("placementKind", profile.placementKind());
        obj.addProperty("sampleType", profile.sampleType());
        obj.add("functionTags", stringArray(profile.functionTags()));
        obj.add("styleTags", stringArray(profile.styleTags()));
        obj.add("placementTags", stringArray(profile.placementTags()));
        obj.add("usageTags", stringArray(profile.usageTags()));
        obj.add("qualityTags", stringArray(profile.qualityTags()));
        obj.addProperty("visibleAreaCost", profile.visibleAreaCost());
        obj.add("fixedFootprint", profile.fixedFootprint().asJson());
        obj.add("expectedAreaRange", profile.expectedAreaRange().asJson());
        obj.add("allowedRotations", stringArray(profile.allowedRotations()));
        obj.addProperty("clearanceBlocks", profile.clearanceBlocks());
        return obj;
    }

    private JsonObject filterRecord(StructureProfile profile, String reasonCode, String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("structureId", profile.structureId());
        obj.addProperty("reasonCode", reasonCode);
        obj.addProperty("message", message);
        obj.addProperty("sampleType", profile.sampleType());
        obj.addProperty("placementKind", profile.placementKind());
        return obj;
    }

    private double landingScore(ZoneInfo zone, BlockBounds footprint) {
        BlockPoint center = footprint.center();
        BlockPoint zoneCenter = zone.bounds().center();
        double distance = Math.hypot(center.x() - zoneCenter.x(), center.z() - zoneCenter.z());
        return Math.max(1.0, 1000.0 - distance);
    }

    private static int expandedArea(BlockBounds footprint, int clearance) {
        return expand(footprint, clearance).widthBlocks() * expand(footprint, clearance).heightBlocks();
    }

    private static BlockBounds expand(BlockBounds bounds, int clearance) {
        int c = Math.max(0, clearance);
        return new BlockBounds(bounds.minX() - c, bounds.minZ() - c, bounds.maxX() + c, bounds.maxZ() + c);
    }

    private Footprint footprint(JsonObject obj) {
        JsonObject source = objectValue(obj, "fixedFootprint");
        if (source.entrySet().isEmpty()) {
            JsonObject hardFacts = objectValue(obj, "hard_facts");
            source = objectValue(hardFacts, "footprint");
            if (source.entrySet().isEmpty()) {
                source = objectValue(hardFacts, "size");
            }
        }
        int width = firstInt(source, 0, "widthBlocks", "width", "x");
        int depth = firstInt(source, 0, "depthBlocks", "depth", "z");
        int height = firstInt(source, 0, "heightBlocks", "height", "y");
        return new Footprint(width, depth, height);
    }

    private OriginOffset originOffset(JsonObject obj) {
        JsonObject source = objectValue(obj, "footprintOriginOffset");
        if (source.entrySet().isEmpty()) {
            source = objectValue(obj, "footprint_origin_offset");
        }
        if (source.entrySet().isEmpty()) {
            source = objectValue(obj, "originOffset");
        }
        if (source.entrySet().isEmpty()) {
            source = objectValue(obj, "origin_offset");
        }
        if (source.entrySet().isEmpty()) {
            JsonObject footprint = objectValue(obj, "footprint");
            source = objectValue(footprint, "origin_offset");
            if (source.entrySet().isEmpty()) {
                source = objectValue(footprint, "originOffset");
            }
        }
        if (source.entrySet().isEmpty()) {
            return OriginOffset.ZERO;
        }
        return new OriginOffset(firstInt(source, 0, "x", "blockX"), firstInt(source, 0, "z", "blockZ"));
    }

    private AreaRange areaRange(JsonObject obj, Footprint fixedFootprint) {
        JsonObject source = objectValue(obj, "expectedAreaRange");
        int min = firstInt(source, 0, "minAreaBlocks", "minArea", "min");
        int max = firstInt(source, 0, "maxAreaBlocks", "maxArea", "max");
        Footprint start = footprint(objectValue(obj, "startFootprint"));
        if (!start.valid()) {
            start = fixedFootprint.valid() ? fixedFootprint : new Footprint(16, 16, 12);
        }
        if (min <= 0) {
            min = Math.max(64, start.widthBlocks() * start.depthBlocks());
        }
        if (max < min) {
            max = min * 4;
        }
        return new AreaRange(min, max, start);
    }

    private String inferPlacementKind(String sampleType, String command) {
        if ("structure_assembly".equals(sampleType)) {
            return "minecraft_place_structure";
        }
        if ("single_template".equals(sampleType)) {
            return "minecraft_template";
        }
        if ("jigsaw_assembly".equals(sampleType)) {
            return "minecraft_jigsaw_pool";
        }
        if (command != null && command.trim().startsWith("place structure")) {
            return "minecraft_place_structure";
        }
        return "";
    }

    private Path resolve(Path baseDirectory, String raw) {
        Path path = Path.of(raw);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDirectory.resolve(path).normalize();
    }

    private void rejectFields(JsonObject obj, Set<String> fields, String context) {
        for (String field : fields) {
            if (obj.has(field)) {
                throw new IllegalArgumentException(context + " must not contain " + field + ".");
            }
        }
    }

    private static boolean validResourceId(String id) {
        return id != null && RESOURCE_ID.matcher(id).matches();
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static JsonObject metric(String key, int value) {
        JsonObject metrics = new JsonObject();
        metrics.addProperty(key, value);
        return metrics;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static String firstString(JsonObject obj, String... keys) {
        for (String key : keys) {
            String value = stringValue(obj, key, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static int firstInt(JsonObject obj, int defaultValue, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsInt();
            }
        }
        return defaultValue;
    }

    private static JsonArray firstArray(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonArray()) {
                return obj.getAsJsonArray(key);
            }
        }
        return new JsonArray();
    }

    private static List<String> firstStrings(JsonObject obj, JsonObject nested, String... keys) {
        for (String key : keys) {
            JsonArray array = firstArray(obj, key);
            if (!array.isEmpty()) {
                return strings(array);
            }
            array = firstArray(nested, key);
            if (!array.isEmpty()) {
                return strings(array);
            }
        }
        return List.of();
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                values.add(elem.getAsString().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(values);
    }

    private static List<String> rotations(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                values.add(elem.getAsString().toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(values);
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsInt();
    }

    private static double doubleValue(JsonObject obj, String key, double defaultValue) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsDouble();
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required.");
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonArray arrayValue(JsonObject obj, String key, JsonArray defaultValue) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : defaultValue;
    }

    private static JsonObject requiredObject(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required.");
        }
        return obj.getAsJsonObject(key);
    }

    private static JsonObject objectValue(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : new JsonObject();
    }

    private record ImportedCatalog(String catalogMode, JsonObject source, List<StructureProfile> profiles,
                                   List<String> warnings, List<String> needsReview) {
        ImportedCatalog {
            profiles = List.copyOf(profiles);
            warnings = List.copyOf(warnings);
            needsReview = List.copyOf(needsReview);
        }

        Map<String, StructureProfile> byId() {
            Map<String, StructureProfile> result = new HashMap<>();
            for (StructureProfile profile : profiles) {
                result.put(profile.structureId(), profile);
            }
            return result;
        }

        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("schemaVersion", "city_structure_profile_catalog.v0.1");
            obj.add("source", source);
            obj.addProperty("catalogMode", catalogMode);
            JsonArray array = new JsonArray();
            profiles.forEach(profile -> array.add(profile.asJson()));
            obj.add("structures", array);
            JsonObject metrics = new JsonObject();
            metrics.addProperty("structureCount", profiles.size());
            obj.add("quality", new CityQualityReport(true, 100, List.of(), warnings, needsReview, metrics).asJson());
            return obj;
        }
    }

    private record StructureProfile(String structureId, String sourceProfileRef, String profileType,
                                    String sampleType, String placementKind, String placementCommand,
                                    String footprintMode, List<String> functionTags, List<String> styleTags,
                                    List<String> placementTags, List<String> usageTags, List<String> qualityTags,
                                    Footprint fixedFootprint, OriginOffset footprintOriginOffset,
                                    int visibleAreaCost, List<String> allowedRotations,
                                    int clearanceBlocks, AreaRange expectedAreaRange, String connectorsRef,
                                    String catalogMode) {
        StructureProfile {
            functionTags = List.copyOf(functionTags);
            styleTags = List.copyOf(styleTags);
            placementTags = List.copyOf(placementTags);
            usageTags = List.copyOf(usageTags);
            qualityTags = List.copyOf(qualityTags);
            footprintOriginOffset = footprintOriginOffset == null ? OriginOffset.ZERO : footprintOriginOffset;
            allowedRotations = List.copyOf(allowedRotations);
        }

        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("structureId", structureId);
            obj.addProperty("sourceProfileRef", sourceProfileRef);
            obj.addProperty("profileType", profileType);
            obj.addProperty("sampleType", sampleType);
            obj.addProperty("placementKind", placementKind);
            obj.addProperty("placementCommand", placementCommand);
            obj.addProperty("footprintMode", footprintMode);
            obj.add("functionTags", stringArray(functionTags));
            obj.add("styleTags", stringArray(styleTags));
            obj.add("placementTags", stringArray(placementTags));
            obj.add("usageTags", stringArray(usageTags));
            obj.add("qualityTags", stringArray(qualityTags));
            obj.add("fixedFootprint", fixedFootprint.asJson());
            obj.add("footprintOriginOffset", footprintOriginOffset.asJson());
            obj.addProperty("visibleAreaCost", visibleAreaCost);
            obj.add("allowedRotations", stringArray(allowedRotations));
            obj.addProperty("clearanceBlocks", clearanceBlocks);
            obj.add("expectedAreaRange", expectedAreaRange.asJson());
            obj.addProperty("connectorsRef", connectorsRef);
            obj.addProperty("catalogMode", catalogMode);
            return obj;
        }
    }

    private record Footprint(int widthBlocks, int depthBlocks, int heightBlocks) {
        boolean valid() {
            return widthBlocks > 0 && depthBlocks > 0;
        }

        BlockBounds boundsAt(int minX, int minZ, String rotation) {
            int width = widthBlocks;
            int depth = depthBlocks;
            if ("CLOCKWISE_90".equals(rotation) || "COUNTERCLOCKWISE_90".equals(rotation)) {
                width = depthBlocks;
                depth = widthBlocks;
            }
            return new BlockBounds(minX, minZ, minX + width - 1, minZ + depth - 1);
        }

        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("widthBlocks", widthBlocks);
            obj.addProperty("depthBlocks", depthBlocks);
            obj.addProperty("heightBlocks", heightBlocks);
            return obj;
        }
    }

    private record OriginOffset(int x, int z) {
        static final OriginOffset ZERO = new OriginOffset(0, 0);

        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("x", x);
            obj.addProperty("z", z);
            return obj;
        }
    }

    private record AreaRange(int minAreaBlocks, int maxAreaBlocks, Footprint startFootprint) {
        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("minAreaBlocks", minAreaBlocks);
            obj.addProperty("maxAreaBlocks", maxAreaBlocks);
            obj.add("startFootprint", startFootprint.asJson());
            return obj;
        }
    }

    private record FilterDecision(String status, String reasonCode, String message) {
    }

    private record FilteredZone(Set<String> fixedIds, Set<String> variableIds) {
    }

    private record Landing(String landingCandidateId, String selectionId, String zonePatchId, String structureId,
                           BlockPoint anchorBlock, String rotation, BlockBounds footprint,
                           BlockBounds clearanceFootprint, int visibleAreaCost, JsonObject scoreBreakdown,
                           List<String> riskFlags) {
        double score() {
            return scoreBreakdown.has("interiorScore") ? scoreBreakdown.get("interiorScore").getAsDouble() : 0;
        }

        JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("landingCandidateId", landingCandidateId);
            obj.addProperty("selectionId", selectionId);
            obj.addProperty("zonePatchId", zonePatchId);
            obj.addProperty("structureId", structureId);
            obj.add("anchorBlock", anchorBlock.asJson());
            obj.addProperty("rotation", rotation);
            obj.add("footprint", boundsJson(footprint));
            obj.add("clearanceFootprint", boundsJson(clearanceFootprint));
            obj.addProperty("visibleAreaCost", visibleAreaCost);
            obj.add("scoreBreakdown", scoreBreakdown);
            obj.add("riskFlags", stringArray(riskFlags));
            return obj;
        }
    }

    private static final class ZoneContext {
        private final PlanningGrid grid;
        private final List<ZoneInfo> zones;
        private final Map<String, ZoneInfo> byId = new HashMap<>();

        ZoneContext(FunctionZoneMap zoneMap, BuildableAreaMap buildableAreaMap) {
            this.grid = zoneMap.grid();
            Map<String, FunctionZonePatch> sourceZones = new HashMap<>();
            for (FunctionZonePatch zone : zoneMap.zones()) {
                sourceZones.put(zone.zonePatchId(), zone);
            }
            List<ZoneInfo> infos = new ArrayList<>();
            for (BuildableAreaMap.ZoneBuildability buildable : buildableAreaMap.zones()) {
                FunctionZonePatch zone = sourceZones.get(buildable.zonePatchId());
                if (zone == null) {
                    continue;
                }
                ZoneInfo info = new ZoneInfo(grid, zone, buildable);
                infos.add(info);
                byId.put(info.zonePatchId(), info);
            }
            this.zones = List.copyOf(infos);
        }

        List<ZoneInfo> zones() {
            return zones;
        }

        ZoneInfo byId(String zoneId) {
            return byId.get(zoneId);
        }
    }

    private static final class ZoneInfo {
        private final PlanningGrid grid;
        private final FunctionZonePatch zone;
        private final BuildableAreaMap.ZoneBuildability buildable;
        private final Set<Long> buildableCells = new LinkedHashSet<>();
        private final List<CellAnchor> anchors = new ArrayList<>();

        ZoneInfo(PlanningGrid grid, FunctionZonePatch zone, BuildableAreaMap.ZoneBuildability buildable) {
            this.grid = grid;
            this.zone = zone;
            this.buildable = buildable;
            for (BuildableAreaMap.BuildableCell cell : buildable.buildableCells()) {
                int x = grid.blockToCellX(cell.blockMinX());
                int z = grid.blockToCellZ(cell.blockMinZ());
                buildableCells.add(key(x, z));
                anchors.add(new CellAnchor(cell.blockMinX(), cell.blockMinZ()));
            }
            anchors.sort(Comparator.comparingInt(CellAnchor::blockMinX).thenComparingInt(CellAnchor::blockMinZ));
        }

        String zonePatchId() {
            return zone.zonePatchId();
        }

        CityFunctionType functionType() {
            return zone.functionType();
        }

        BlockBounds bounds() {
            return zone.cellShape();
        }

        int buildableAreaBlocks() {
            return buildable.buildableAreaBlocks();
        }

        List<CellAnchor> buildableAnchors() {
            return anchors;
        }

        boolean covers(BlockBounds bounds) {
            int minCellX = clampCellX(grid.blockToCellX(bounds.minX()));
            int maxCellX = clampCellX(grid.blockToCellX(bounds.maxX()));
            int minCellZ = clampCellZ(grid.blockToCellZ(bounds.minZ()));
            int maxCellZ = clampCellZ(grid.blockToCellZ(bounds.maxZ()));
            for (int x = minCellX; x <= maxCellX; x++) {
                for (int z = minCellZ; z <= maxCellZ; z++) {
                    if (!buildableCells.contains(key(x, z))) {
                        return false;
                    }
                }
            }
            return true;
        }

        private int clampCellX(int x) {
            return Math.max(0, Math.min(grid.cellsX() - 1, x));
        }

        private int clampCellZ(int z) {
            return Math.max(0, Math.min(grid.cellsZ() - 1, z));
        }

        private long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }
    }

    private record CellAnchor(int blockMinX, int blockMinZ) {
    }

    public record Result(JsonObject terraSenseProfileSource,
                         JsonObject structureProfileCatalog,
                         JsonObject filteredStructureCatalog,
                         JsonObject structureChoicePlan,
                         JsonObject fixedPlacementCandidateSet,
                         JsonObject fixedPlacementSelectionPlan,
                         JsonObject plannedFixedPlacementMap,
                         JsonObject structurePoolMap,
                         JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", boolValue(qualityReport, "passed", false));
            obj.add("structureProfileCatalog", structureProfileCatalog);
            obj.add("filteredStructureCatalog", filteredStructureCatalog);
            obj.add("structureChoicePlan", structureChoicePlan);
            obj.add("fixedPlacementCandidateSet", fixedPlacementCandidateSet);
            obj.add("fixedPlacementSelectionPlan", fixedPlacementSelectionPlan);
            obj.add("plannedFixedPlacementMap", plannedFixedPlacementMap);
            obj.add("structurePoolMap", structurePoolMap);
            obj.add("qualityReport", qualityReport);
            return obj;
        }

        private static boolean boolValue(JsonObject obj, String key, boolean defaultValue) {
            return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                    ? obj.get(key).getAsBoolean()
                    : defaultValue;
        }

        public String pretty() {
            return CityJson.GSON.toJson(asJson());
        }
    }
}
