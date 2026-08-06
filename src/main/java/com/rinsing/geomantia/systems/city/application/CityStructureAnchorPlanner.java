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
    public static final String PLAN_SCHEMA = "city_structure_anchor_plan.v0.2";
    public static final String MAP_SCHEMA = "city_structure_anchor_map.v0.2";
    public static final int DEFAULT_CLEARANCE_BLOCKS = 8;
    public static final int DEFAULT_SMALL_CLEARANCE_BLOCKS = 4;
    public static final int DEFAULT_MASK_MARGIN_BLOCKS = 8;
    public static final int DEFAULT_ROAD_ACCESS_MARGIN_BLOCKS = 6;
    public static final int DEFAULT_VEGETATION_MARGIN_BLOCKS = 8;

    public Result plan(Path baseDirectory,
                       CityLandformReviewPackage reviewPackage,
                       JsonObject terraSenseProfileSource,
                       JsonObject structureAnchorPlan) throws IOException {
        long started = System.nanoTime();
        if (reviewPackage == null) {
            throw new IllegalArgumentException("CityLandformReviewPackage is required for D4.");
        }
        rejectLegacyPayload(structureAnchorPlan);
        if (structureAnchorPlan == null || !structureAnchorPlan.has("anchors")) {
            throw new IllegalArgumentException("structureAnchorPlan.anchors array is required.");
        }
        for (JsonElement element : requiredArray(structureAnchorPlan, "anchors")) {
            if (!element.isJsonObject() || !isTemplatePlacement(element.getAsJsonObject())) {
                throw new IllegalArgumentException("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED");
            }
        }
        String cityId = stringValue(structureAnchorPlan, "cityId", reviewPackage.cityId());
        if (!reviewPackage.cityId().equals(cityId)) {
            throw new IllegalArgumentException("StructureAnchorPlan cityId mismatch.");
        }

        CityStructureProfileCatalog.ImportedCatalog catalog =
                CityStructureProfileCatalog.importCatalog(baseDirectory, terraSenseProfileSource);
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
        }

        JsonObject anchorMap = new JsonObject();
        anchorMap.addProperty("schemaVersion", MAP_SCHEMA);
        anchorMap.addProperty("cityId", reviewPackage.cityId());
        anchorMap.add("grid", reviewPackage.grid().asJson());
        anchorMap.add("sourceTerraSenseProfileSource", terraSenseProfileSource.deepCopy());
        anchorMap.add("semanticProfileSource", terraSenseProfileSource.deepCopy());
        if (structureAnchorPlan.has("cityBlueprintCompileProvenance")
                && structureAnchorPlan.get("cityBlueprintCompileProvenance").isJsonObject()) {
            anchorMap.add("cityBlueprintCompileProvenance",
                    structureAnchorPlan.getAsJsonObject("cityBlueprintCompileProvenance").deepCopy());
        }
        anchorMap.add("anchors", anchors);
        JsonObject quality = quality(hardBlocks, warnings, needsReview, anchors.size());
        anchorMap.add("quality", quality);
        anchorMap.add("timingMs", timing(started));
        JsonObject normalizedPlan = structureAnchorPlan.deepCopy();
        normalizedPlan.addProperty("schemaVersion", PLAN_SCHEMA);
        for (JsonElement elem : requiredArray(normalizedPlan, "anchors")) {
            if (elem.isJsonObject()) applyPlacementProvenance(elem.getAsJsonObject(), elem.getAsJsonObject());
        }
        return new Result(normalizedPlan, anchorMap, quality);
    }

    private static JsonObject templateAnchorJson(JsonObject source,
                                                  List<LandformPatchSummary> patches,
                                                  BlockPoint anchorBlock,
                                                  TemplateAnchorDecision template) {
        JsonObject obj = source.deepCopy();
        obj.remove("templateFootprint");
        obj.remove("lockedActualFootprint");
        String templateRef = template.templateRef();
        obj.addProperty("anchorId", requiredString(source, "anchorId"));
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
        if (template.templateSize() != null) {
            obj.add("rawSize", sizeJson(template.templateSize()));
        }
        obj.add("plannedFootprint", boundsJson(template.actualFootprint()));
        obj.add("actualFootprint", boundsJson(template.actualFootprint()));
        obj.add("reservedEnvelope", boundsJson(template.collisionEnvelope()));
        obj.add("collisionEnvelope", boundsJson(template.collisionEnvelope()));
        obj.add("maskEnvelope", boundsJson(template.maskEnvelope()));
        obj.addProperty("clearanceBlocks", template.clearanceBlocks());
        obj.addProperty("maskMarginBlocks", template.maskMarginBlocks());
        obj.addProperty("reservedEnvelopePolicy", "exactFootprint+clearance");
        applyPlacementProvenance(source, obj);
        return obj;
    }

    public static void applyPlacementProvenance(JsonObject source, JsonObject target) {
        JsonObject nested = source != null && source.has("placementProvenance")
                && source.get("placementProvenance").isJsonObject()
                ? source.getAsJsonObject("placementProvenance") : new JsonObject();
        String slotId = firstString(source, nested, "slotId", "sourceSlotId");
        String arrayId = firstString(source, nested, "arrayId");
        String parentArrayId = firstString(source, nested, "parentArrayId");
        String subZoneId = firstString(source, nested, "subZoneId", "targetSubZoneId");
        String groupId = firstString(source, nested, "placementGroupId", "groupId");
        if (groupId.isBlank()) {
            groupId = !parentArrayId.isBlank() ? parentArrayId
                    : !arrayId.isBlank() ? arrayId
                    : !slotId.isBlank() ? slotId
                    : stringValue(source, "anchorId", stringValue(target, "anchorId", ""));
        }
        if (groupId.isBlank()) throw new IllegalArgumentException("D4 placementGroupId cannot be derived");
        target.addProperty("placementGroupId", groupId);
        JsonObject provenance = new JsonObject();
        provenance.addProperty("slotId", slotId);
        provenance.addProperty("arrayId", arrayId);
        provenance.addProperty("parentArrayId", parentArrayId);
        provenance.addProperty("subZoneId", subZoneId);
        target.add("placementProvenance", provenance);
    }

    private static String firstString(JsonObject source, JsonObject nested, String... keys) {
        for (String key : keys) {
            String value = stringValue(source, key, "");
            if (!value.isBlank()) return value;
            value = stringValue(nested, key, "");
            if (!value.isBlank()) return value;
        }
        return "";
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
        CityTemplatePlacementGeometry.Size templateSize = firstTemplateSize(source, nested);
        BlockBounds suppliedFootprint = firstTemplateBounds(source, nested, "actualFootprint", "templateFootprint");
        int clearance = Math.max(0, intValue(source, "clearanceBlocks", 0));
        int maskMargin = Math.max(0, intValue(source, "maskMarginBlocks", DEFAULT_MASK_MARGIN_BLOCKS));
        List<String> errors = new ArrayList<>();
        if (templateRef.isBlank()) errors.add("TEMPLATE_REF_MISSING");
        if (templateHash.isBlank()) errors.add("TEMPLATE_HASH_MISSING");
        if (variantId.isBlank()) errors.add("TEMPLATE_VARIANT_MISSING");
        if (rotation.isBlank() || mirror.isBlank()) errors.add("TEMPLATE_TRANSFORM_MISSING");
        if (templateSize == null && suppliedFootprint == null) errors.add("TEMPLATE_SIZE_MISSING");
        if (suppliedFootprint != null
                && (suppliedFootprint.maxX() < suppliedFootprint.minX()
                || suppliedFootprint.maxZ() < suppliedFootprint.minZ())) {
            errors.add("TEMPLATE_BBOX_INVALID");
        }
        BlockBounds footprint = suppliedFootprint;
        if (templateSize != null && !rotation.isBlank() && !mirror.isBlank()) {
            try {
                CityTemplatePlacementGeometry geometry = CityTemplatePlacementGeometry.of(templateSize,
                        CityTemplatePlacementGeometry.Rotation.valueOf(rotation),
                        CityTemplatePlacementGeometry.Mirror.valueOf(mirror), List.of());
                footprint = geometry.worldBounds(anchorBlock);
                if (suppliedFootprint != null && !footprint.equals(suppliedFootprint)) {
                    errors.add("TEMPLATE_FOOTPRINT_DRIFT");
                }
            } catch (IllegalArgumentException ex) {
                errors.add("TEMPLATE_TRANSFORM_INVALID");
            }
        }
        BlockBounds collision = footprint == null ? null : expand(footprint, clearance);
        BlockBounds mask = collision == null ? null : expand(collision, maskMargin);
        String reason = errors.isEmpty() ? "" : String.join(",", errors);
        return new TemplateAnchorDecision(templateRef, templateHash, variantId, rotation, mirror, templateSize, footprint,
                collision, mask, clearance, maskMargin, reason);
    }

    private static boolean isTemplatePlacement(JsonObject source) {
        return source != null && (source.has("templateRef") || source.has("templateHash")
                || source.has("rawSize") || source.has("templateFootprint") || source.has("structureTemplate")
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

    private static CityTemplatePlacementGeometry.Size firstTemplateSize(JsonObject source, JsonObject nested) {
        JsonObject size = firstTemplateObject(source, nested, "templateSize", "rawSize");
        if (size == null) {
            return null;
        }
        int width = intValue(size, "width", 0);
        int height = intValue(size, "height", 0);
        int depth = intValue(size, "depth", 0);
        try {
            return new CityTemplatePlacementGeometry.Size(width, height, depth);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static JsonObject firstTemplateObject(JsonObject source, JsonObject nested, String... keys) {
        for (String key : keys) {
            if (source != null && source.has(key) && source.get(key).isJsonObject()) {
                return source.getAsJsonObject(key);
            }
            if (nested != null && nested.has(key) && nested.get(key).isJsonObject()) {
                return nested.getAsJsonObject(key);
            }
        }
        return null;
    }

    private static JsonObject sizeJson(CityTemplatePlacementGeometry.Size size) {
        JsonObject obj = new JsonObject();
        obj.addProperty("width", size.width());
        obj.addProperty("height", size.height());
        obj.addProperty("depth", size.depth());
        return obj;
    }

    private record TemplateAnchorDecision(String templateRef, String templateHash, String variantId,
                                          String rotation, String mirror, CityTemplatePlacementGeometry.Size templateSize,
                                          BlockBounds actualFootprint,
                                          BlockBounds collisionEnvelope, BlockBounds maskEnvelope,
                                          int clearanceBlocks, int maskMarginBlocks, String hardBlockReason) {
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
        rejectConfiguredIdentity(plan);
    }

    private static void rejectConfiguredIdentity(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) rejectConfiguredIdentity(child);
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("structureId") || object.has("structureIds")) {
            throw new IllegalArgumentException("CITY_CONFIGURED_STRUCTURE_FLOW_REMOVED: structureId(s)");
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            rejectConfiguredIdentity(entry.getValue());
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
            int step = grid.cellStepBlocks();
            for (PatchMemberCell cell : patch.memberCells()) {
                if (point.x() >= cell.blockMinX() && point.x() < cell.blockMinX() + step
                        && point.z() >= cell.blockMinZ() && point.z() < cell.blockMinZ() + step) {
                    return true;
                }
            }
            return false;
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
