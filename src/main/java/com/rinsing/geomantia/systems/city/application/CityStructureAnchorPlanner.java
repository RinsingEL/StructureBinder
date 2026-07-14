package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import com.rinsing.geomantia.systems.city.domain.model.CityLandformReviewPackage;
import com.rinsing.geomantia.systems.city.domain.model.LandformPatchSummary;
import com.rinsing.geomantia.systems.city.domain.model.PatchMemberCell;
import com.rinsing.geomantia.systems.city.domain.model.PlanningGrid;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CityStructureAnchorPlanner {
    public static final String PLAN_SCHEMA = "city_structure_anchor_plan.v0.1";
    public static final String MAP_SCHEMA = "city_structure_anchor_map.v0.1";
    public static final int DEFAULT_CLEARANCE_BLOCKS = 8;
    public static final int DEFAULT_SMALL_CLEARANCE_BLOCKS = 4;
    public static final int DEFAULT_MASK_MARGIN_BLOCKS = 8;
    public static final int DEFAULT_ROAD_ACCESS_MARGIN_BLOCKS = 6;
    public static final int DEFAULT_VEGETATION_MARGIN_BLOCKS = 8;
    public static final int DEFAULT_JIGSAW_RADIUS_BLOCKS = 96;

    public Result plan(Path baseDirectory,
                       CityLandformReviewPackage reviewPackage,
                       JsonObject terraSenseProfileSource,
                       JsonObject structureAnchorPlan) throws IOException {
        return plan(baseDirectory, reviewPackage, terraSenseProfileSource, structureAnchorPlan,
                CityStructureEnvelopeFacts.empty());
    }

    public Result plan(Path baseDirectory,
                       CityLandformReviewPackage reviewPackage,
                       JsonObject terraSenseProfileSource,
                       JsonObject structureAnchorPlan,
                       CityStructureEnvelopeFacts envelopeFacts) throws IOException {
        long started = System.nanoTime();
        if (reviewPackage == null) {
            throw new IllegalArgumentException("CityLandformReviewPackage is required for D4.");
        }
        rejectLegacyPayload(structureAnchorPlan);
        if (structureAnchorPlan == null || !structureAnchorPlan.has("anchors")) {
            throw new IllegalArgumentException("structureAnchorPlan.anchors array is required.");
        }
        String cityId = stringValue(structureAnchorPlan, "cityId", reviewPackage.cityId());
        if (!reviewPackage.cityId().equals(cityId)) {
            throw new IllegalArgumentException("StructureAnchorPlan cityId mismatch.");
        }

        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(baseDirectory, terraSenseProfileSource);
        Map<String, CityStructureProfileCatalog.StructureProfile> profiles = catalog.byId();
        CityStructureEnvelopeFacts facts = envelopeFacts == null ? CityStructureEnvelopeFacts.empty() : envelopeFacts;
        Map<String, LandformPatchSummary> patches = patchesByRef(reviewPackage);
        List<String> hardBlocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>(catalog.warnings());
        List<String> needsReview = new ArrayList<>(catalog.needsReview());
        JsonArray anchors = new JsonArray();
        List<BlockBounds> reserved = new ArrayList<>();

        int index = 0;
        for (JsonElement elem : requiredArray(structureAnchorPlan, "anchors")) {
            index++;
            JsonObject anchor = elem.getAsJsonObject();
            String anchorId = requiredString(anchor, "anchorId");
            boolean templatePlacement = isTemplatePlacement(anchor);
            String structureId = stringValue(anchor, "structureId", "");
            CityStructureProfileCatalog.StructureProfile profile = null;
            if (!templatePlacement) {
                structureId = requiredString(anchor, "structureId");
                profile = profiles.get(structureId);
                if (profile == null) {
                    hardBlocks.add(anchorId + ": structureId is not in approved TerraSense catalog: " + structureId);
                    continue;
                }
            }
            JsonObject anchorBlockJson = requiredObject(anchor, "anchorBlock");
            BlockPoint anchorBlock = new BlockPoint(intValue(anchorBlockJson, "x", 0),
                    intValue(anchorBlockJson, "z", 0));
            if (!reviewPackage.grid().containsBlock(anchorBlock.x(), anchorBlock.z())) {
                hardBlocks.add(anchorId + ": anchorBlock is outside D3 city grid.");
                continue;
            }
            List<LandformPatchSummary> sourcePatches = sourcePatches(anchor, patches);
            if (sourcePatches.isEmpty()) {
                hardBlocks.add(anchorId + ": sourcePatchIds must reference D3 landform patches or map labels.");
                continue;
            }
            if (sourcePatches.stream().noneMatch(patch -> patchContains(patch, reviewPackage.grid(), anchorBlock))) {
                hardBlocks.add(anchorId + ": anchorBlock is outside sourcePatchIds.");
                continue;
            }
            if (templatePlacement) {
                TemplateAnchorDecision template = templateAnchorDecision(anchor, anchorBlock);
                if (!template.hardBlockReason().isBlank()) {
                    hardBlocks.add(anchorId + ": " + template.hardBlockReason());
                    continue;
                }
                if (reservedEnvelopeOverlaps(reserved, template.collisionEnvelope())) {
                    hardBlocks.add(anchorId + ": reservedEnvelope overlaps an earlier planned structure.");
                    continue;
                }
                reserved.add(template.collisionEnvelope());
                anchors.add(templateAnchorJson(anchor, sourcePatches, anchorBlock, template));
                continue;
            }
            String rotation = stringValue(anchor, "rotation", "NONE").toUpperCase(Locale.ROOT);
            int clearance = Math.max(DEFAULT_CLEARANCE_BLOCKS,
                    intValue(anchor, "clearanceBlocks", profile.clearanceBlocks()));
            int smallClearance = Math.max(0,
                    intValue(anchor, "smallClearanceBlocks", DEFAULT_SMALL_CLEARANCE_BLOCKS));
            int roadMargin = intValue(anchor, "roadAccessMarginBlocks", DEFAULT_ROAD_ACCESS_MARGIN_BLOCKS);
            int vegetationMargin = intValue(anchor, "vegetationMarginBlocks", DEFAULT_VEGETATION_MARGIN_BLOCKS);
            int maskMargin = intValue(anchor, "maskMarginBlocks",
                    intValue(anchor, "d5MaskMarginBlocks", DEFAULT_MASK_MARGIN_BLOCKS));
            String envelopeGroupKey = stringValue(anchor, "envelopeGroupKey", "");
            CityStructureProfileCatalog.Footprint footprint = profile.planningFootprint();
            if (!footprint.valid()) {
                hardBlocks.add(anchorId + ": structure profile has no usable footprint.");
                continue;
            }
            BlockBounds plannedFootprint = footprint.centeredAt(anchorBlock.x(), anchorBlock.z(), rotation);
            EnvelopeDecision envelope = envelopeDecision(anchorBlock, plannedFootprint, profile, facts,
                    clearance, smallClearance, roadMargin, vegetationMargin, maskMargin, envelopeGroupKey);
            if (envelope.requiredFactsMissing()) {
                hardBlocks.add(anchorId + ": structure envelope facts are required for Trek structure "
                        + structureId + " but are missing or hash-mismatched.");
                continue;
            }
            if (!envelope.hardBlockReason().isBlank()) {
                hardBlocks.add(anchorId + ": " + envelope.hardBlockReason());
                continue;
            }
            BlockBounds reservedEnvelope = envelope.collisionEnvelope();
            if (reservedEnvelopeOverlaps(reserved, reservedEnvelope)) {
                hardBlocks.add(anchorId + ": reservedEnvelope overlaps an earlier planned structure.");
                continue;
            }
            reserved.add(reservedEnvelope);
            anchors.add(anchorJson(anchor, profile, sourcePatches, anchorBlock, rotation, plannedFootprint,
                    envelope, clearance, smallClearance, roadMargin, vegetationMargin, maskMargin));
        }

        JsonObject anchorMap = new JsonObject();
        anchorMap.addProperty("schemaVersion", MAP_SCHEMA);
        anchorMap.addProperty("cityId", reviewPackage.cityId());
        anchorMap.add("grid", reviewPackage.grid().asJson());
        anchorMap.add("sourceTerraSenseProfileSource", terraSenseProfileSource.deepCopy());
        anchorMap.add("structureProfileCatalog", catalog.asJson());
        anchorMap.add("anchors", anchors);
        JsonObject quality = quality(hardBlocks, warnings, needsReview, anchors.size());
        anchorMap.add("quality", quality);
        anchorMap.add("timingMs", timing(started));
        return new Result(structureAnchorPlan.deepCopy(), anchorMap, quality);
    }

    private JsonObject anchorJson(JsonObject source,
                                  CityStructureProfileCatalog.StructureProfile profile,
                                  List<LandformPatchSummary> patches,
                                  BlockPoint anchorBlock,
                                  String rotation,
                                  BlockBounds plannedFootprint,
                                  EnvelopeDecision envelope,
                                  int clearance,
                                  int smallClearance,
                                  int roadMargin,
                                  int vegetationMargin,
                                  int maskMargin) {
        JsonObject obj = new JsonObject();
        obj.addProperty("anchorId", requiredString(source, "anchorId"));
        obj.addProperty("structureId", profile.structureId());
        obj.addProperty("footprintMode", profile.footprintMode());
        obj.addProperty("profileType", profile.profileType());
        obj.add("anchorBlock", anchorBlock.asJson());
        obj.add("commandAnchorBlock", anchorBlock.asJson());
        obj.addProperty("rotation", rotation);
        obj.addProperty("priority", intValue(source, "priority", 0));
        obj.addProperty("roadAccessIntent", stringValue(source, "roadAccessIntent", "connect_to_city_entry"));
        obj.add("intentTerms", stringArray(strings(optionalArray(source, "intentTerms"))));
        obj.add("semanticTerms", stringArray(profile.semanticTerms()));
        obj.add("functionTerms", stringArray(profile.functionTerms()));
        obj.add("styleTerms", stringArray(profile.styleTerms()));
        obj.add("placementTerms", stringArray(profile.placementTerms()));
        obj.add("usageTerms", stringArray(profile.usageTerms()));
        obj.add("qualityTerms", stringArray(profile.qualityTerms()));
        JsonArray patchRefs = new JsonArray();
        for (LandformPatchSummary patch : patches) {
            JsonObject ref = new JsonObject();
            ref.addProperty("landformPatchId", patch.landformPatchId());
            ref.addProperty("mapLabel", patch.mapLabel());
            ref.addProperty("landformType", patch.landformType().contractName());
            ref.add("blockBounds", boundsJson(patch.blockBounds()));
            patchRefs.add(ref);
        }
        obj.add("sourcePatches", patchRefs);
        obj.add("plannedFootprint", boundsJson(plannedFootprint));
        obj.add("reservedEnvelope", boundsJson(envelope.collisionEnvelope()));
        obj.add("collisionEnvelope", boundsJson(envelope.collisionEnvelope()));
        obj.add("maskEnvelope", boundsJson(envelope.maskEnvelope()));
        obj.addProperty("clearanceBlocks", envelope.usesSmallClearance() ? smallClearance : clearance);
        obj.addProperty("defaultClearanceBlocks", clearance);
        obj.addProperty("smallClearanceBlocks", smallClearance);
        obj.addProperty("roadAccessMarginBlocks", roadMargin);
        obj.addProperty("vegetationMarginBlocks", vegetationMargin);
        obj.addProperty("maskMarginBlocks", maskMargin);
        obj.addProperty("reservedEnvelopeRadiusBlocks", envelope.envelopeRadiusBlocks());
        obj.addProperty("reservedEnvelopePolicy", envelope.policy());
        obj.addProperty("envelopeMode", envelope.envelopeMode());
        obj.addProperty("selectedEnvelopeGroupKey", envelope.selectedEnvelopeGroupKey());
        if (envelope.selectedGroup() != null) {
            obj.add("selectedEnvelopeGroup", envelope.selectedGroup().asJson());
        }
        if (envelope.fact() != null) {
            obj.add("structureEnvelopeFact", envelope.fact().asSummaryJson());
            obj.add("availableEnvelopeGroupKeys", bboxGroupKeys(envelope.fact()));
        }
        return obj;
    }

    private static JsonObject templateAnchorJson(JsonObject source,
                                                  List<LandformPatchSummary> patches,
                                                  BlockPoint anchorBlock,
                                                  TemplateAnchorDecision template) {
        JsonObject obj = source.deepCopy();
        String templateRef = template.templateRef();
        obj.addProperty("anchorId", requiredString(source, "anchorId"));
        obj.addProperty("structureId", "template:" + templateRef);
        obj.addProperty("templateId", stringValue(source, "templateId", templateRef));
        obj.addProperty("templateRef", templateRef);
        obj.addProperty("templateHash", template.templateHash());
        obj.addProperty("variantId", template.variantId());
        obj.addProperty("rotation", template.rotation());
        obj.addProperty("mirror", template.mirror());
        obj.addProperty("materializationSource", CityStructureMaterializationPlanner.TEMPLATE_MATERIALIZATION_SOURCE);
        obj.add("anchorBlock", anchorBlock.asJson());
        obj.add("commandAnchorBlock", anchorBlock.asJson());
        obj.add("sourcePatches", patchRefs(patches));
        obj.add("plannedFootprint", boundsJson(template.actualFootprint()));
        obj.add("templateFootprint", boundsJson(template.actualFootprint()));
        obj.add("lockedActualFootprint", boundsJson(template.actualFootprint()));
        obj.add("reservedEnvelope", boundsJson(template.collisionEnvelope()));
        obj.add("collisionEnvelope", boundsJson(template.collisionEnvelope()));
        obj.add("maskEnvelope", boundsJson(template.maskEnvelope()));
        obj.addProperty("clearanceBlocks", template.clearanceBlocks());
        obj.addProperty("maskMarginBlocks", template.maskMarginBlocks());
        obj.addProperty("reservedEnvelopePolicy", "structureTemplateFootprint+clearance");
        obj.addProperty("envelopeMode", "structure_template_nbt");
        obj.addProperty("selectedEnvelopeGroupKey", "");
        return obj;
    }

    private static JsonArray patchRefs(List<LandformPatchSummary> patches) {
        JsonArray refs = new JsonArray();
        for (LandformPatchSummary patch : patches) {
            JsonObject ref = new JsonObject();
            ref.addProperty("landformPatchId", patch.landformPatchId());
            ref.addProperty("mapLabel", patch.mapLabel());
            ref.addProperty("landformType", patch.landformType().contractName());
            ref.add("blockBounds", boundsJson(patch.blockBounds()));
            refs.add(ref);
        }
        return refs;
    }

    private static TemplateAnchorDecision templateAnchorDecision(JsonObject source, BlockPoint anchorBlock) {
        JsonObject nested = source.has("structureTemplate") && source.get("structureTemplate").isJsonObject()
                ? source.getAsJsonObject("structureTemplate") : null;
        String templateRef = firstTemplateString(source, nested, "templateRef", "nbtFile");
        String templateHash = firstTemplateString(source, nested, "templateHash", "contentHash");
        String variantId = firstTemplateString(source, nested, "variantId", "variant");
        String rotation = firstTemplateString(source, nested, "rotation", "").toUpperCase(Locale.ROOT);
        String mirror = firstTemplateString(source, nested, "mirror", "").toUpperCase(Locale.ROOT);
        BlockBounds footprint = firstTemplateBounds(source, nested, "templateFootprint", "actualFootprint");
        int clearance = Math.max(0, intValue(source, "clearanceBlocks", 0));
        int maskMargin = Math.max(0, intValue(source, "maskMarginBlocks", DEFAULT_MASK_MARGIN_BLOCKS));
        List<String> errors = new ArrayList<>();
        if (templateRef.isBlank()) errors.add("TEMPLATE_REF_MISSING");
        if (templateHash.isBlank()) errors.add("TEMPLATE_HASH_MISSING");
        if (variantId.isBlank()) errors.add("TEMPLATE_VARIANT_MISSING");
        if (rotation.isBlank() || mirror.isBlank()) errors.add("TEMPLATE_TRANSFORM_MISSING");
        if (footprint == null) errors.add("TEMPLATE_FOOTPRINT_MISSING");
        if (footprint != null && (footprint.maxX() < footprint.minX() || footprint.maxZ() < footprint.minZ())) {
            errors.add("TEMPLATE_BBOX_INVALID");
        }
        BlockBounds collision = footprint == null ? null : expand(footprint, clearance);
        BlockBounds mask = collision == null ? null : expand(collision, maskMargin);
        String reason = errors.isEmpty() ? "" : String.join(",", errors);
        return new TemplateAnchorDecision(templateRef, templateHash, variantId, rotation, mirror, footprint,
                collision, mask, clearance, maskMargin, reason);
    }

    private static boolean isTemplatePlacement(JsonObject source) {
        return source != null && (source.has("templateRef") || source.has("templateHash")
                || source.has("templateFootprint") || source.has("structureTemplate")
                || CityStructureMaterializationPlanner.TEMPLATE_MATERIALIZATION_SOURCE.equals(
                stringValue(source, "materializationSource", "")));
    }

    private static String firstTemplateString(JsonObject source, JsonObject nested, String... keys) {
        for (String key : keys) {
            String value = stringValue(source, key, "");
            if (!value.isBlank()) return value;
            value = stringValue(nested, key, "");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static BlockBounds firstTemplateBounds(JsonObject source, JsonObject nested, String... keys) {
        for (String key : keys) {
            JsonObject value = source != null && source.has(key) && source.get(key).isJsonObject()
                    ? source.getAsJsonObject(key) : null;
            if (value == null && nested != null && nested.has(key) && nested.get(key).isJsonObject()) {
                value = nested.getAsJsonObject(key);
            }
            if (value != null) return new BlockBounds(intValue(value, "minX", 0), intValue(value, "minZ", 0),
                    intValue(value, "maxX", 0), intValue(value, "maxZ", 0));
        }
        return null;
    }

    private record TemplateAnchorDecision(String templateRef, String templateHash, String variantId,
                                          String rotation, String mirror, BlockBounds actualFootprint,
                                          BlockBounds collisionEnvelope, BlockBounds maskEnvelope,
                                          int clearanceBlocks, int maskMarginBlocks, String hardBlockReason) {
    }

    private static EnvelopeDecision envelopeDecision(BlockPoint anchorBlock,
                                                     BlockBounds plannedFootprint,
                                                     CityStructureProfileCatalog.StructureProfile profile,
                                                     CityStructureEnvelopeFacts facts,
                                                     int clearance,
                                                     int smallClearance,
                                                     int roadMargin,
                                                     int vegetationMargin,
                                                     int maskMargin,
                                                     String requestedEnvelopeGroupKey) {
        java.util.Optional<CityStructureEnvelopeFacts.Fact> fact = facts.validFactFor(profile);
        if (fact.isPresent()) {
            CityStructureEnvelopeFacts.Fact value = fact.get();
            if (value.usesDominantBBoxGroup()) {
                java.util.Optional<CityStructureEnvelopeFacts.BBoxGroup> selected = requestedEnvelopeGroupKey.isBlank()
                        ? value.dominantGroup()
                        : value.groupByKey(requestedEnvelopeGroupKey);
                if (selected.isEmpty()) {
                    String reason = requestedEnvelopeGroupKey.isBlank()
                            ? "structure envelope facts have no bboxGroups for fixed bbox mode."
                            : "requested envelopeGroupKey is not in structure envelope facts: "
                            + requestedEnvelopeGroupKey;
                    return new EnvelopeDecision(plannedFootprint, plannedFootprint, plannedFootprint, 0,
                            "structureEnvelopeFacts:fixedBBoxGroupMissing", "fixed_bbox_group",
                            requestedEnvelopeGroupKey, null, value, false, reason, true);
                }
                CityStructureEnvelopeFacts.BBoxGroup group = selected.get();
                BlockBounds collision = fromLocal(anchorBlock, expand(group.localEnvelope(), smallClearance));
                BlockBounds mask = expand(collision, maskMargin);
                BlockBounds diagnosticMaxObserved = fromLocal(anchorBlock, expand(value.maxObservedEnvelope(),
                        Math.max(smallClearance, roadMargin)));
                return new EnvelopeDecision(collision, mask, diagnosticMaxObserved, 0,
                        "structureEnvelopeFacts:fixedBBoxGroup+smallClearance", "fixed_bbox_group",
                        group.groupKey(), group, value, false, "", true);
            }
            if (!requestedEnvelopeGroupKey.isBlank()) {
                return new EnvelopeDecision(plannedFootprint, plannedFootprint, plannedFootprint, 0,
                        "structureEnvelopeFacts:requestedGroupNotRecommended", "d2_recommended_envelope",
                        requestedEnvelopeGroupKey, null, value, false,
                        "requested envelopeGroupKey is only valid when D2 recommends dominantBBoxGroup.", false);
            }
            BlockBounds recommended = value.recommendedEnvelope();
            if (value.usesStableMaxEnvelope()) {
                BlockBounds collision = fromLocal(anchorBlock, expand(recommended, clearance));
                BlockBounds mask = expand(collision, maskMargin);
                BlockBounds diagnosticMaxObserved = fromLocal(anchorBlock, expand(value.maxObservedEnvelope(),
                        Math.max(clearance, roadMargin)));
                return new EnvelopeDecision(collision, mask, diagnosticMaxObserved, 0,
                        "structureEnvelopeFacts:stableMaxEnvelope+clearance/collision+maskMargin",
                        "d2_stable_max_envelope", "", null, value, false, "", false);
            }
            BlockBounds collision = fromLocal(anchorBlock, expand(value.p95Envelope(), clearance));
            BlockBounds mask = expand(collision, maskMargin);
            BlockBounds diagnosticMaxObserved = fromLocal(anchorBlock, expand(value.maxObservedEnvelope(),
                    Math.max(clearance, roadMargin)));
            return new EnvelopeDecision(collision, mask, diagnosticMaxObserved, 0,
                    "structureEnvelopeFacts:fixedDepthP95+clearance/collision+maskMargin",
                    "fixed_depth_statistics", "", null, value, false, "", false);
        }
        if (profile.structureId().startsWith("trek:")) {
            return new EnvelopeDecision(plannedFootprint, plannedFootprint, plannedFootprint,
                    0, "structureEnvelopeFactsRequired", "missing_structure_envelope_facts",
                    "", null, null, true, "", false);
        }
        int envelopeRadius = profile.jigsawLike()
                ? profile.jigsawExpansionRadius(DEFAULT_JIGSAW_RADIUS_BLOCKS) + clearance
                : clearance;
        BlockBounds collision = expand(plannedFootprint, envelopeRadius);
        BlockBounds mask = expand(collision, maskMargin);
        BlockBounds diagnosticMaxObserved = profile.jigsawLike()
                ? expand(plannedFootprint, envelopeRadius + roadMargin)
                : collision;
        return new EnvelopeDecision(collision, mask, diagnosticMaxObserved, envelopeRadius, profile.jigsawLike()
                ? "startFootprint+jigsawMaxExpansionRadius+clearance"
                : "fixedFootprint+clearance", profile.jigsawLike()
                ? "fallback_jigsaw_radius" : "fallback_fixed_footprint",
                "", null, null, false, "", false);
    }

    private static BlockBounds fromLocal(BlockPoint anchorBlock, BlockBounds local) {
        int originX = Math.floorDiv(anchorBlock.x(), 16) * 16;
        int originZ = Math.floorDiv(anchorBlock.z(), 16) * 16;
        return new BlockBounds(
                originX + local.minX(),
                originZ + local.minZ(),
                originX + local.maxX(),
                originZ + local.maxZ());
    }

    private static void rejectLegacyPayload(JsonObject plan) {
        if (plan == null) {
            throw new IllegalArgumentException("structureAnchorPlan object is required.");
        }
        for (String field : List.of("patchGroupPlan", "groups", "zoneChoices", "functionType",
                "functionTags", "function_candidates", "targetVisibleAreaRatio")) {
            if (plan.has(field)) {
                throw CityStructureProfileCatalog.legacyFlow("city_plan_d4 now requires structureAnchorPlan, not "
                        + field + ".");
            }
        }
    }

    private static Map<String, LandformPatchSummary> patchesByRef(CityLandformReviewPackage reviewPackage) {
        Map<String, LandformPatchSummary> result = new HashMap<>();
        for (LandformPatchSummary patch : reviewPackage.landformPatches()) {
            result.put(patch.landformPatchId(), patch);
            result.put(patch.mapLabel(), patch);
        }
        return result;
    }

    private static List<LandformPatchSummary> sourcePatches(JsonObject anchor,
                                                            Map<String, LandformPatchSummary> patches) {
        List<LandformPatchSummary> result = new ArrayList<>();
        for (JsonElement elem : requiredArray(anchor, "sourcePatchIds")) {
            String ref = elem.getAsString();
            LandformPatchSummary patch = patches.get(ref);
            if (patch != null && result.stream().noneMatch(existing -> existing.landformPatchId().equals(patch.landformPatchId()))) {
                result.add(patch);
            }
        }
        result.sort(Comparator.comparing(LandformPatchSummary::landformPatchId));
        return result;
    }

    private static boolean patchContains(LandformPatchSummary patch, PlanningGrid grid, BlockPoint point) {
        if (!patch.memberCells().isEmpty()) {
            int cellX = grid.blockToCellX(point.x());
            int cellZ = grid.blockToCellZ(point.z());
            for (PatchMemberCell cell : patch.memberCells()) {
                if (cell.cellX() == cellX && cell.cellZ() == cellZ) {
                    return true;
                }
            }
        }
        return patch.blockBounds().contains(point.x(), point.z());
    }

    private static boolean reservedEnvelopeOverlaps(List<BlockBounds> existing, BlockBounds candidate) {
        for (BlockBounds bounds : existing) {
            if (bounds.overlaps(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static BlockBounds expand(BlockBounds bounds, int amount) {
        int a = Math.max(0, amount);
        return new BlockBounds(bounds.minX() - a, bounds.minZ() - a,
                bounds.maxX() + a, bounds.maxZ() + a);
    }

    private record EnvelopeDecision(BlockBounds collisionEnvelope, BlockBounds maskEnvelope,
                                    BlockBounds diagnosticMaxObservedEnvelope, int envelopeRadiusBlocks,
                                    String policy, String envelopeMode, String selectedEnvelopeGroupKey,
                                    CityStructureEnvelopeFacts.BBoxGroup selectedGroup,
                                    CityStructureEnvelopeFacts.Fact fact,
                                    boolean requiredFactsMissing, String hardBlockReason,
                                    boolean usesSmallClearance) {
    }

    private static JsonObject quality(List<String> hardBlocks, List<String> warnings,
                                      List<String> needsReview, int anchorCount) {
        JsonObject quality = new JsonObject();
        quality.addProperty("passed", hardBlocks.isEmpty());
        quality.addProperty("score", hardBlocks.isEmpty() ? 100 : 0);
        quality.add("hardBlocks", stringArray(hardBlocks));
        quality.add("warnings", stringArray(warnings));
        quality.add("needsReview", stringArray(needsReview));
        JsonObject metrics = new JsonObject();
        metrics.addProperty("acceptedAnchorCount", anchorCount);
        quality.add("metrics", metrics);
        return quality;
    }

    private static JsonObject timing(long started) {
        JsonObject timing = new JsonObject();
        timing.addProperty("total", (System.nanoTime() - started) / 1_000_000L);
        return timing;
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static JsonArray bboxGroupKeys(CityStructureEnvelopeFacts.Fact fact) {
        JsonArray array = new JsonArray();
        fact.bboxGroups().forEach(group -> array.add(group.groupKey()));
        return array;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                values.add(elem.getAsString());
            }
        }
        return values;
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required.");
        }
        return value;
    }

    private static JsonObject requiredObject(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required.");
        }
        return obj.getAsJsonObject(key);
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required.");
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonArray optionalArray(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : defaultValue;
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : defaultValue;
    }

    public record Result(JsonObject structureAnchorPlan, JsonObject structureAnchorMap, JsonObject qualityReport) {
        public JsonObject asJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("ok", qualityReport.get("passed").getAsBoolean());
            obj.add("structureAnchorPlan", structureAnchorPlan);
            obj.add("structureAnchorMap", structureAnchorMap);
            obj.add("qualityReport", qualityReport);
            obj.add("timingMs", structureAnchorMap.getAsJsonObject("timingMs"));
            return obj;
        }
    }
}
