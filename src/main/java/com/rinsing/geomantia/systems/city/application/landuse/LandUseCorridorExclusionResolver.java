package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LandUseCorridorExclusionResolver {
    public List<LandUseAreaPlan.CorridorExclusion> fromD5ReservationMask(JsonObject reservationMaskPlan) {
        if (reservationMaskPlan == null || !reservationMaskPlan.has("gateCorridorMask")) return List.of();
        if (!reservationMaskPlan.get("gateCorridorMask").isJsonArray()) {
            throw new IllegalArgumentException("LAND_USE_D5_GATE_CORRIDOR_MASK_INVALID");
        }
        List<LandUseAreaPlan.CorridorExclusion> corridors = new ArrayList<>();
        JsonArray masks = reservationMaskPlan.getAsJsonArray("gateCorridorMask");
        for (int index = 0; index < masks.size(); index++) {
            JsonElement element = masks.get(index);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("LAND_USE_D5_GATE_CORRIDOR_ENTRY_INVALID: " + index);
            }
            JsonObject mask = element.getAsJsonObject();
            String maskType = stringValue(mask, "maskType", "gate_corridor");
            if (!"gate_corridor".equals(maskType)) {
                throw new IllegalArgumentException("LAND_USE_D5_GATE_CORRIDOR_TYPE_INVALID: " + maskType);
            }
            String maskId = requiredString(mask, "maskId");
            JsonObject bounds = requiredObject(mask, "blockBounds");
            corridors.add(new LandUseAreaPlan.CorridorExclusion(maskId,
                    new BlockBounds(requiredInt(bounds, "minX"), requiredInt(bounds, "minZ"),
                            requiredInt(bounds, "maxX"), requiredInt(bounds, "maxZ")),
                    stringValue(mask, "sourceRef", maskId)));
        }
        return stableMerge(List.of(), corridors);
    }

    public List<LandUseAreaPlan.CorridorExclusion> stableMerge(
            List<LandUseAreaPlan.CorridorExclusion> derived,
            List<LandUseAreaPlan.CorridorExclusion> external) {
        List<LandUseAreaPlan.CorridorExclusion> sorted = new ArrayList<>();
        if (derived != null) sorted.addAll(derived);
        if (external != null) sorted.addAll(external);
        sorted.sort(Comparator.comparing(LandUseAreaPlan.CorridorExclusion::exclusionId)
                .thenComparingInt(value -> value.blockBounds().minZ())
                .thenComparingInt(value -> value.blockBounds().minX())
                .thenComparingInt(value -> value.blockBounds().maxZ())
                .thenComparingInt(value -> value.blockBounds().maxX())
                .thenComparing(LandUseAreaPlan.CorridorExclusion::sourceRef));
        Map<String, BlockBounds> boundsById = new HashMap<>();
        Set<BlockBounds> seenGeometry = new HashSet<>();
        List<LandUseAreaPlan.CorridorExclusion> result = new ArrayList<>();
        for (LandUseAreaPlan.CorridorExclusion corridor : sorted) {
            BlockBounds previous = boundsById.putIfAbsent(corridor.exclusionId(), corridor.blockBounds());
            if (previous != null && !previous.equals(corridor.blockBounds())) {
                throw new IllegalArgumentException("LAND_USE_CORRIDOR_ID_GEOMETRY_CONFLICT: "
                        + corridor.exclusionId());
            }
            if (previous == null && seenGeometry.add(corridor.blockBounds())) result.add(corridor);
        }
        return List.copyOf(result);
    }

    private static JsonObject requiredObject(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " object is required");
        }
        return obj.getAsJsonObject(key);
    }

    private static String requiredString(JsonObject obj, String key) {
        String value = stringValue(obj, key, "");
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static int requiredInt(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) throw new IllegalArgumentException(key + " is required");
        return obj.get(key).getAsInt();
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }
}
