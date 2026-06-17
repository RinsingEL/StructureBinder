package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
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
}
