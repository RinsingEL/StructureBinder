package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CityLandformReviewPackage(
        String schemaVersion,
        String cityId,
        PlanningGrid grid,
        TargetScale targetScale,
        String reviewMapImage,
        List<LegendEntry> legend,
        List<LandformPatchSummary> landformPatches,
        List<String> planningContext,
        String aiPromptContext,
        List<String> debugRefs) {

    public static final String CURRENT_SCHEMA_VERSION = "city_landform_review.v0.1";

    public CityLandformReviewPackage {
        if (schemaVersion == null) throw new IllegalArgumentException("schemaVersion is required");
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        if (grid == null) throw new IllegalArgumentException("grid is required");
        if (targetScale == null) throw new IllegalArgumentException("targetScale is required");
        if (reviewMapImage == null) throw new IllegalArgumentException("reviewMapImage is required");
        legend = List.copyOf(legend);
        landformPatches = List.copyOf(landformPatches);
        planningContext = List.copyOf(planningContext);
        debugRefs = List.copyOf(debugRefs);
        if (aiPromptContext == null) throw new IllegalArgumentException("aiPromptContext is required");
    }

    public record LegendEntry(String color, String label, String landformType) {
        public LegendEntry {
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(landformType, "landformType");
        }
    }

    public CityLandformReviewPackage withReviewMap(String reviewMapImage, List<String> debugRefs) {
        return new CityLandformReviewPackage(
                schemaVersion,
                cityId,
                grid,
                targetScale,
                reviewMapImage,
                legend,
                landformPatches,
                planningContext,
                aiPromptContext,
                debugRefs);
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", schemaVersion);
        obj.addProperty("cityId", cityId);
        obj.add("grid", grid.asJson());

        JsonObject scaleObj = new JsonObject();
        scaleObj.addProperty("scale", targetScale.scale().contractName());
        scaleObj.addProperty("radiusBlocks", targetScale.radiusBlocks());
        scaleObj.addProperty("cellStepBlocks", targetScale.cellStepBlocks());
        obj.add("targetScale", scaleObj);

        obj.addProperty("reviewMapImage", reviewMapImage);

        JsonArray legendArr = new JsonArray();
        for (LegendEntry e : legend) {
            JsonObject le = new JsonObject();
            le.addProperty("color", e.color());
            le.addProperty("label", e.label());
            le.addProperty("landformType", e.landformType());
            legendArr.add(le);
        }
        obj.add("legend", legendArr);

        JsonArray patchArr = new JsonArray();
        for (LandformPatchSummary p : landformPatches) {
            JsonObject pj = new JsonObject();
            pj.addProperty("landformPatchId", p.landformPatchId());
            pj.addProperty("mapLabel", p.mapLabel());
            pj.addProperty("displayLandformName", p.displayLandformName());
            pj.addProperty("landformType", p.landformType().contractName());
            pj.addProperty("areaBlocks", p.areaBlocks());
            pj.addProperty("cellCount", p.cellCount());
            pj.addProperty("areaClass", p.areaClass().contractName());

            JsonObject center = new JsonObject();
            center.addProperty("x", p.centerBlock().x());
            center.addProperty("z", p.centerBlock().z());
            pj.add("centerBlock", center);

            JsonObject bounds = new JsonObject();
            bounds.addProperty("minX", p.blockBounds().minX());
            bounds.addProperty("minZ", p.blockBounds().minZ());
            bounds.addProperty("maxX", p.blockBounds().maxX());
            bounds.addProperty("maxZ", p.blockBounds().maxZ());
            pj.add("blockBounds", bounds);
            pj.addProperty("geometryMode", p.geometryMode());
            JsonArray memberCells = new JsonArray();
            p.memberCells().forEach(cell -> memberCells.add(cell.asJson()));
            pj.add("memberCells", memberCells);

            JsonObject metrics = new JsonObject();
            metrics.addProperty("meanElevation", p.metricsSummary().meanElevation());
            metrics.addProperty("minElevation", p.metricsSummary().minElevation());
            metrics.addProperty("maxElevation", p.metricsSummary().maxElevation());
            metrics.addProperty("meanSlope", p.metricsSummary().meanSlope());
            metrics.addProperty("meanWaterDistance", p.metricsSummary().meanWaterDistance());
            pj.add("metricsSummary", metrics);

            JsonArray tags = new JsonArray();
            p.landformTags().forEach(tags::add);
            pj.add("landformTags", tags);

            JsonArray overlays = new JsonArray();
            p.overlayTags().forEach(overlays::add);
            pj.add("overlayTags", overlays);

            pj.add("biomeSummary", biomeSummaryToJson(p.biomeSummary()));

            JsonArray facts = new JsonArray();
            p.summaryFacts().forEach(facts::add);
            pj.add("summaryFacts", facts);

            JsonArray neighbors = new JsonArray();
            p.neighborLandformPatchIds().forEach(neighbors::add);
            pj.add("neighborLandformPatchIds", neighbors);

            patchArr.add(pj);
        }
        obj.add("landformPatches", patchArr);

        JsonArray ctxArr = new JsonArray();
        planningContext.forEach(ctxArr::add);
        obj.add("planningContext", ctxArr);

        obj.addProperty("aiPromptContext", aiPromptContext);

        JsonArray debugArr = new JsonArray();
        debugRefs.forEach(debugArr::add);
        obj.add("debugRefs", debugArr);

        return obj;
    }

    public static CityLandformReviewPackage fromJson(JsonObject obj) {
        if (obj == null) throw new IllegalArgumentException("CityLandformReviewPackage JSON is required");
        PlanningGrid grid = gridFromJson(requiredObject(obj, "grid"));
        TargetScale targetScale = targetScaleFromJson(requiredObject(obj, "targetScale"));
        return new CityLandformReviewPackage(
                requiredString(obj, "schemaVersion"),
                requiredString(obj, "cityId"),
                grid,
                targetScale,
                stringValue(obj, "reviewMapImage", ""),
                legendFromJson(requiredArray(obj, "legend")),
                patchesFromJson(requiredArray(obj, "landformPatches")),
                stringsFromArray(requiredArray(obj, "planningContext")),
                requiredString(obj, "aiPromptContext"),
                stringsFromArray(requiredArray(obj, "debugRefs")));
    }

    private static PlanningGrid gridFromJson(JsonObject obj) {
        return new PlanningGrid(
                intValue(obj, "originBlockX", 0),
                intValue(obj, "originBlockZ", 0),
                intValue(obj, "cellStepBlocks", 1),
                intValue(obj, "cellsX", 1),
                intValue(obj, "cellsZ", 1));
    }

    private static TargetScale targetScaleFromJson(JsonObject obj) {
        return new TargetScale(
                CityScale.fromContractName(requiredString(obj, "scale")),
                intValue(obj, "radiusBlocks", 1),
                intValue(obj, "cellStepBlocks", 1));
    }

    private static List<LegendEntry> legendFromJson(JsonArray array) {
        List<LegendEntry> result = new ArrayList<>();
        for (JsonElement elem : array) {
            JsonObject obj = elem.getAsJsonObject();
            result.add(new LegendEntry(
                    requiredString(obj, "color"),
                    requiredString(obj, "label"),
                    requiredString(obj, "landformType")));
        }
        return result;
    }

    private static List<LandformPatchSummary> patchesFromJson(JsonArray array) {
        List<LandformPatchSummary> result = new ArrayList<>();
        for (JsonElement elem : array) {
            JsonObject obj = elem.getAsJsonObject();
            JsonObject center = requiredObject(obj, "centerBlock");
            JsonObject boundsObj = obj.has("blockBounds") && obj.get("blockBounds").isJsonObject()
                    ? obj.getAsJsonObject("blockBounds")
                    : null;
            BlockPoint centerBlock = new BlockPoint(intValue(center, "x", 0), intValue(center, "z", 0));
            int areaBlocks = intValue(obj, "areaBlocks", 0);
            int half = Math.max(1, (int) Math.round(Math.sqrt(Math.max(1, areaBlocks)) / 2.0));
            BlockBounds bounds = boundsObj == null
                    ? new BlockBounds(centerBlock.x() - half, centerBlock.z() - half,
                    centerBlock.x() + half, centerBlock.z() + half)
                    : new BlockBounds(
                    intValue(boundsObj, "minX", centerBlock.x()),
                    intValue(boundsObj, "minZ", centerBlock.z()),
                    intValue(boundsObj, "maxX", centerBlock.x()),
                    intValue(boundsObj, "maxZ", centerBlock.z()));

            JsonObject metrics = requiredObject(obj, "metricsSummary");
            MetricsSummary metricsSummary = new MetricsSummary(
                    doubleValue(metrics, "meanElevation", 0),
                    doubleValue(metrics, "minElevation", 0),
                    doubleValue(metrics, "maxElevation", 0),
                    doubleValue(metrics, "meanSlope", 0),
                    doubleValue(metrics, "meanWaterDistance", 0));
            result.add(new LandformPatchSummary(
                    requiredString(obj, "landformPatchId"),
                    requiredString(obj, "mapLabel"),
                    requiredString(obj, "displayLandformName"),
                    centerBlock,
                    bounds,
                    stringValue(obj, "geometryMode", "patch_envelope"),
                    memberCellsFromJson(optionalArray(obj, "memberCells")),
                    areaBlocks,
                    intValue(obj, "cellCount", 0),
                    landformType(requiredString(obj, "landformType")),
                    stringsFromArray(optionalArray(obj, "landformTags")),
                    stringsFromArray(optionalArray(obj, "overlayTags")),
                    areaClass(requiredString(obj, "areaClass")),
                    metricsSummary,
                    stringsFromArray(requiredArray(obj, "summaryFacts")),
                    stringsFromArray(requiredArray(obj, "neighborLandformPatchIds")),
                    biomeSummaryFromJson(optionalObject(obj, "biomeSummary"))));
        }
        return result;
    }

    private static JsonObject biomeSummaryToJson(BiomeSummary summary) {
        BiomeSummary normalized = summary == null ? BiomeSummary.empty() : summary;
        JsonObject obj = new JsonObject();
        obj.addProperty("dominantBiome", normalized.dominantBiome());
        JsonObject histogram = new JsonObject();
        for (Map.Entry<String, Integer> entry : normalized.biomeHistogram().entrySet()) {
            histogram.addProperty(entry.getKey(), entry.getValue());
        }
        obj.add("biomeHistogram", histogram);
        obj.addProperty("mixedBiome", normalized.mixedBiome());
        obj.addProperty("sampledCellCount", normalized.sampledCellCount());
        return obj;
    }

    private static BiomeSummary biomeSummaryFromJson(JsonObject obj) {
        if (obj == null || obj.size() == 0) {
            return BiomeSummary.empty();
        }
        JsonObject histogramJson = optionalObject(obj, "biomeHistogram");
        Map<String, Integer> histogram = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : histogramJson.entrySet()) {
            if (!entry.getValue().isJsonNull()) {
                histogram.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        if (!histogram.isEmpty()) {
            return BiomeSummary.fromHistogram(histogram);
        }
        return new BiomeSummary(
                stringValue(obj, "dominantBiome", "unknown"),
                histogram,
                booleanValue(obj, "mixedBiome", false),
                intValue(obj, "sampledCellCount", 0));
    }

    private static List<PatchMemberCell> memberCellsFromJson(JsonArray array) {
        List<PatchMemberCell> result = new ArrayList<>();
        for (JsonElement elem : array) {
            JsonObject obj = elem.getAsJsonObject();
            result.add(new PatchMemberCell(
                    intValue(obj, "cellX", 0),
                    intValue(obj, "cellZ", 0),
                    intValue(obj, "blockMinX", 0),
                    intValue(obj, "blockMinZ", 0)));
        }
        return result;
    }

    private static LandformType landformType(String raw) {
        for (LandformType type : LandformType.values()) {
            if (type.contractName().equalsIgnoreCase(raw)) {
                return type;
            }
        }
        return LandformType.UNKNOWN;
    }

    private static AreaClass areaClass(String raw) {
        for (AreaClass areaClass : AreaClass.values()) {
            if (areaClass.contractName().equalsIgnoreCase(raw)) {
                return areaClass;
            }
        }
        throw new IllegalArgumentException("Unknown areaClass: " + raw);
    }

    private static JsonObject requiredObject(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required");
        }
        return obj.getAsJsonObject(key);
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " array is required");
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonArray optionalArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonObject optionalObject(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return obj.getAsJsonObject(key);
    }

    private static List<String> stringsFromArray(JsonArray array) {
        List<String> result = new ArrayList<>();
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                result.add(elem.getAsString());
            }
        }
        return result;
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String stringValue(JsonObject obj, String key, String defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsString();
    }

    private static int intValue(JsonObject obj, String key, int defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsInt();
    }

    private static boolean booleanValue(JsonObject obj, String key, boolean defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsBoolean();
    }

    private static double doubleValue(JsonObject obj, String key, double defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(key).getAsDouble();
    }
}
