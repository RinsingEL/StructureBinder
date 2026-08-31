package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Freezes outward-guided fill buildings as explicit, road-owning residential subzones. */
final class CityResidentialOverflowPlanner {
    static final String SCHEMA = "city_residential_overflow_plan";
    private static final int MINIMUM_BUILDING_COUNT = 3;

    Result plan(List<JsonObject> anchors, List<JsonObject> streetBands) {
        Map<String, List<JsonObject>> candidatesByGroup = new LinkedHashMap<>();
        for (JsonObject anchor : anchors) {
            if (!"fill".equals(string(anchor, "blueprintPlacementPhase"))) continue;
            JsonObject layout = object(anchor, "blueprintLayout");
            if (!bool(layout, "outwardGuided")) continue;
            String groupId = string(anchor, "placementGroupId");
            if (!groupId.isBlank()) {
                candidatesByGroup.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(anchor);
            }
        }
        JsonArray zones = new JsonArray();
        candidatesByGroup.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            List<JsonObject> candidates = entry.getValue().stream()
                    .sorted(Comparator.comparing(anchor -> string(anchor, "anchorId"))).toList();
            if (candidates.size() < MINIMUM_BUILDING_COUNT) return;
            BlockBounds extent = union(candidates.stream().map(anchor -> CityStructureCandidateEnvelope.bounds(
                    anchor.getAsJsonObject("collisionEnvelope"))).toList());
            List<JsonObject> roads = streetBands.stream()
                    .filter(road -> entry.getKey().equals(string(road, "groupId")))
                    .filter(road -> object(road, "bounds").size() > 0)
                    .filter(road -> CityStructureCandidateEnvelope.bounds(object(road, "bounds")).overlaps(extent))
                    .sorted(Comparator.comparing(road -> string(road, "streetBandId"))).toList();
            if (roads.isEmpty()) return;
            String zoneId = entry.getKey() + "::residential_overflow_001";
            JsonObject zone = new JsonObject();
            zone.addProperty("zoneId", zoneId);
            zone.addProperty("parentGroupId", entry.getKey());
            zone.addProperty("zoneKind", "RESIDENTIAL_OVERFLOW");
            zone.addProperty("generationMode", "OUTWARD_GUIDED_FILL_BUILDINGS");
            zone.addProperty("buildingCount", candidates.size());
            zone.add("boundaryBounds", CityStructureCandidateEnvelope.boundsJson(extent));
            zone.addProperty("boundaryBlockId", "minecraft:stone_brick_wall");
            JsonArray anchorIds = new JsonArray();
            for (JsonObject anchor : candidates) {
                anchorIds.add(string(anchor, "anchorId"));
                object(anchor, "blueprintLayout").addProperty("residentialOverflowZoneId", zoneId);
            }
            zone.add("anchorIds", anchorIds);
            JsonArray streetBandIds = new JsonArray();
            roads.forEach(road -> streetBandIds.add(string(road, "streetBandId")));
            zone.add("streetBandIds", streetBandIds);
            zones.add(zone);
        });
        JsonObject plan = new JsonObject();
        plan.addProperty("schema", SCHEMA);
        plan.addProperty("minimumBuildingCount", MINIMUM_BUILDING_COUNT);
        plan.addProperty("zoneCount", zones.size());
        plan.add("zones", zones);
        return new Result(plan);
    }

    private static BlockBounds union(List<BlockBounds> bounds) {
        BlockBounds result = bounds.get(0);
        for (int index = 1; index < bounds.size(); index++) {
            BlockBounds next = bounds.get(index);
            result = new BlockBounds(Math.min(result.minX(), next.minX()),
                    Math.min(result.minZ(), next.minZ()), Math.max(result.maxX(), next.maxX()),
                    Math.max(result.maxZ(), next.maxZ()));
        }
        return result;
    }

    private static JsonObject object(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonObject()
                ? value.getAsJsonObject(key) : new JsonObject();
    }

    private static String string(JsonObject value, String key) {
        return value != null && value.has(key) && !value.get(key).isJsonNull()
                ? value.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonPrimitive()
                && value.getAsJsonPrimitive(key).isBoolean() && value.get(key).getAsBoolean();
    }

    record Result(JsonObject plan) {
    }
}
