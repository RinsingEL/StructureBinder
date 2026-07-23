package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public record CitySiteContext(
        String schemaVersion,
        String cityId,
        String realmId,
        String dimensionId,
        String seedId,
        String siteCandidateId,
        BlockBounds bounds,
        PlanningGrid grid,
        BlockPoint anchorBlock,
        String cityRole,
        CityScale scaleClass,
        int planningRadiusBlocks,
        List<EntryCandidate> entryCandidates,
        TerritoryCheckResult territoryCheckResult) {

    public static final String CURRENT_SCHEMA_VERSION = "city_site_context.v0.1";

    public CitySiteContext {
        if (schemaVersion == null) throw new IllegalArgumentException("schemaVersion is required");
        if (cityId == null || cityId.isBlank()) throw new IllegalArgumentException("cityId is required");
        if (realmId == null || realmId.isBlank()) throw new IllegalArgumentException("realmId is required");
        if (dimensionId == null) throw new IllegalArgumentException("dimensionId is required");
        if (seedId == null) throw new IllegalArgumentException("seedId is required");
        if (siteCandidateId == null) throw new IllegalArgumentException("siteCandidateId is required");
        if (bounds == null) throw new IllegalArgumentException("bounds is required");
        if (grid == null) throw new IllegalArgumentException("grid is required");
        if (anchorBlock == null) throw new IllegalArgumentException("anchorBlock is required");
        if (cityRole == null) throw new IllegalArgumentException("cityRole is required");
        if (scaleClass == null) throw new IllegalArgumentException("scaleClass is required");
        if (territoryCheckResult == null) throw new IllegalArgumentException("territoryCheckResult is required");
        entryCandidates = List.copyOf(entryCandidates);
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", schemaVersion);
        obj.addProperty("cityId", cityId);
        obj.addProperty("realmId", realmId);
        obj.addProperty("dimensionId", dimensionId);
        obj.addProperty("seedId", seedId);
        obj.addProperty("siteCandidateId", siteCandidateId);
        obj.addProperty("cityRole", cityRole);
        obj.addProperty("scaleClass", scaleClass.contractName());
        obj.addProperty("planningRadiusBlocks", planningRadiusBlocks);
        obj.addProperty("territoryCheckResult", territoryCheckResult.contractName());

        JsonObject boundsJson = new JsonObject();
        boundsJson.addProperty("minX", bounds.minX());
        boundsJson.addProperty("minZ", bounds.minZ());
        boundsJson.addProperty("maxX", bounds.maxX());
        boundsJson.addProperty("maxZ", bounds.maxZ());
        obj.add("bounds", boundsJson);

        obj.add("grid", grid.asJson());

        JsonObject anchorJson = new JsonObject();
        anchorJson.addProperty("x", anchorBlock.x());
        anchorJson.addProperty("z", anchorBlock.z());
        obj.add("anchorBlock", anchorJson);

        JsonArray entries = new JsonArray();
        for (EntryCandidate ec : entryCandidates) {
            JsonObject ecObj = new JsonObject();
            ecObj.addProperty("id", ec.id());
            ecObj.addProperty("direction", ec.direction());
            ecObj.addProperty("description", ec.description());
            JsonObject bp = new JsonObject();
            bp.addProperty("x", ec.block().x());
            bp.addProperty("z", ec.block().z());
            ecObj.add("block", bp);
            entries.add(ecObj);
        }
        obj.add("entryCandidates", entries);

        return obj;
    }
}
