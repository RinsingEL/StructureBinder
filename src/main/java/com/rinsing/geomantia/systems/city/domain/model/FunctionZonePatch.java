package com.rinsing.geomantia.systems.city.domain.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public record FunctionZonePatch(
        String zonePatchId,
        String sourceGroupId,
        String zoneName,
        CityFunctionType functionType,
        List<String> landformPatchRefs,
        BlockBounds cellShape,
        List<PatchMemberCell> memberCells,
        int areaBlocks,
        String mainBuildingRole,
        String terrainStatsRef,
        String groupReason,
        String generationNotes) {

    public FunctionZonePatch {
        if (zonePatchId == null || zonePatchId.isBlank()) {
            throw new IllegalArgumentException("zonePatchId is required");
        }
        if (sourceGroupId == null || sourceGroupId.isBlank()) {
            throw new IllegalArgumentException("sourceGroupId is required");
        }
        if (zoneName == null || zoneName.isBlank()) {
            throw new IllegalArgumentException("zoneName is required");
        }
        if (functionType == null) {
            throw new IllegalArgumentException("functionType is required");
        }
        landformPatchRefs = List.copyOf(landformPatchRefs);
        if (landformPatchRefs.isEmpty()) {
            throw new IllegalArgumentException("landformPatchRefs is required");
        }
        if (cellShape == null) {
            throw new IllegalArgumentException("cellShape is required");
        }
        memberCells = List.copyOf(memberCells);
        if (areaBlocks < 0) {
            throw new IllegalArgumentException("areaBlocks must be >= 0");
        }
        mainBuildingRole = mainBuildingRole == null ? "" : mainBuildingRole;
        if (terrainStatsRef == null || terrainStatsRef.isBlank()) {
            throw new IllegalArgumentException("terrainStatsRef is required");
        }
        groupReason = groupReason == null ? "" : groupReason;
        generationNotes = generationNotes == null ? "" : generationNotes;
    }

    public JsonObject asJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("zonePatchId", zonePatchId);
        obj.addProperty("sourceGroupId", sourceGroupId);
        obj.addProperty("zoneName", zoneName);
        obj.addProperty("functionType", functionType.contractName());
        obj.add("landformPatchRefs", stringArray(landformPatchRefs));
        JsonObject shape = new JsonObject();
        shape.addProperty("geometryMode", memberCells.isEmpty() ? "patch_envelope_union" : "patch_member_cells_union");
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minX", cellShape.minX());
        bounds.addProperty("minZ", cellShape.minZ());
        bounds.addProperty("maxX", cellShape.maxX());
        bounds.addProperty("maxZ", cellShape.maxZ());
        shape.add("blockBounds", bounds);
        JsonArray cells = new JsonArray();
        memberCells.forEach(cell -> cells.add(cell.asJson()));
        shape.add("memberCells", cells);
        obj.add("cellShape", shape);
        obj.addProperty("areaBlocks", areaBlocks);
        obj.addProperty("mainBuildingRole", mainBuildingRole);
        obj.addProperty("terrainStatsRef", terrainStatsRef);
        obj.addProperty("groupReason", groupReason);
        obj.addProperty("generationNotes", generationNotes);
        return obj;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
