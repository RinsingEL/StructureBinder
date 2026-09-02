package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.landuse.BoundaryPolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.CardinalDirection;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy;
import com.rinsing.geomantia.systems.city.domain.landuse.VegetationPolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class LandUseAreaPlanCodec {
    public LandUseAreaPlan withComputedHash(LandUseAreaPlan plan) {
        return plan.withPlanHash(computePlanHash(plan));
    }

    public String computePlanHash(LandUseAreaPlan plan) {
        JsonObject json = toJson(plan.withPlanHash(""));
        json.remove("planHash");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public JsonObject toJson(LandUseAreaPlan plan) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schema", plan.schema());
        obj.addProperty("ruleVersion", plan.ruleVersion());
        obj.addProperty("cityId", plan.cityId());
        if (!plan.planHash().isBlank()) obj.addProperty("planHash", plan.planHash());
        obj.add("planningBounds", boundsJson(plan.planningBounds()));
        JsonArray areas = new JsonArray();
        plan.areas().forEach(area -> areas.add(areaJson(area)));
        obj.add("areas", areas);
        JsonArray shared = new JsonArray();
        for (LandUseAreaPlan.SharedBoundarySpan span : plan.sharedBoundarySpans()) {
            JsonObject value = new JsonObject();
            value.addProperty("z", span.z());
            value.addProperty("minX", span.minX());
            value.addProperty("maxX", span.maxX());
            value.addProperty("writerAreaId", span.writerAreaId());
            value.addProperty("neighborAreaId", span.neighborAreaId());
            value.addProperty("relation", span.relation().name());
            shared.add(value);
        }
        obj.add("sharedBoundarySpans", shared);
        obj.add("unclaimedSpans", spansJson(plan.unclaimedSpans()));
        JsonArray corridors = new JsonArray();
        for (LandUseAreaPlan.CorridorExclusion corridor : plan.corridorExclusions()) {
            JsonObject value = new JsonObject();
            value.addProperty("exclusionId", corridor.exclusionId());
            value.add("blockBounds", boundsJson(corridor.blockBounds()));
            value.addProperty("sourceRef", corridor.sourceRef());
            corridors.add(value);
        }
        obj.add("corridorExclusions", corridors);
        obj.add("warnings", stringsJson(plan.warnings()));
        return obj;
    }

    public LandUseAreaPlan fromJson(JsonObject obj) {
        if (obj == null) throw new IllegalArgumentException("LandUse area plan JSON is required");
        List<LandUseAreaPlan.Area> areas = new ArrayList<>();
        for (JsonElement element : requiredArray(obj, "areas")) areas.add(area(element.getAsJsonObject()));
        List<LandUseAreaPlan.CorridorExclusion> corridors = new ArrayList<>();
        for (JsonElement element : requiredArray(obj, "corridorExclusions")) {
            JsonObject value = element.getAsJsonObject();
            corridors.add(new LandUseAreaPlan.CorridorExclusion(requiredString(value, "exclusionId"),
                    bounds(requiredObject(value, "blockBounds")), stringValue(value, "sourceRef", "")));
        }
        List<LandUseAreaPlan.SharedBoundarySpan> shared = new ArrayList<>();
        JsonArray sharedJson = requiredArray(obj, "sharedBoundarySpans");
        for (JsonElement element : sharedJson) {
            JsonObject value = element.getAsJsonObject();
            shared.add(new LandUseAreaPlan.SharedBoundarySpan(requiredInt(value, "z"),
                    requiredInt(value, "minX"), requiredInt(value, "maxX"),
                    requiredString(value, "writerAreaId"), requiredString(value, "neighborAreaId"),
                    LandUseAreaPlan.SharedBoundaryRelation.valueOf(requiredString(value, "relation"))));
        }
        LandUseAreaPlan plan = new LandUseAreaPlan(requiredString(obj, "schema"),
                requiredString(obj, "ruleVersion"), requiredString(obj, "cityId"),
                stringValue(obj, "planHash", ""), bounds(requiredObject(obj, "planningBounds")), areas,
                shared, spans(requiredArray(obj, "unclaimedSpans")), corridors,
                strings(requiredArray(obj, "warnings")));
        if (!plan.planHash().isBlank() && !plan.planHash().equals(computePlanHash(plan))) {
            throw new IllegalArgumentException("LAND_USE_PLAN_HASH_MISMATCH");
        }
        return plan;
    }

    private JsonObject areaJson(LandUseAreaPlan.Area area) {
        JsonObject obj = new JsonObject();
        obj.addProperty("areaId", area.areaId());
        obj.addProperty("ruleRef", area.ruleRef());
        obj.addProperty("landUseType", area.landUseType());
        obj.add("sourceGroupIds", stringsJson(area.sourceGroupIds()));
        obj.add("sourceAnchorIds", stringsJson(area.sourceAnchorIds()));
        obj.add("seedPoints", pointsJson(area.seedPoints()));
        obj.add("memberSpans", spansJson(area.memberSpans()));
        JsonArray exclusions = new JsonArray();
        area.structureFootprintExclusions().forEach(bounds -> exclusions.add(boundsJson(bounds)));
        obj.add("structureFootprintExclusions", exclusions);
        JsonArray loops = new JsonArray();
        for (LandUseAreaPlan.BoundaryLoop loop : area.boundaryLoops()) {
            JsonObject value = new JsonObject();
            value.add("points", pointsJson(loop.points()));
            value.addProperty("hole", loop.hole());
            loops.add(value);
        }
        obj.add("boundaryLoops", loops);
        JsonArray gates = new JsonArray();
        for (LandUseAreaPlan.GateSlot gate : area.gateSlots()) {
            JsonObject value = new JsonObject();
            value.addProperty("gateId", gate.gateId());
            value.add("block", pointJson(gate.block()));
            value.addProperty("direction", gate.direction().name().toLowerCase());
            value.addProperty("sourceAnchorId", gate.sourceAnchorId());
            gates.add(value);
        }
        obj.add("gateSlots", gates);
        obj.addProperty("claimCostTotal", area.claimCostTotal());
        obj.addProperty("surfacePolicy", area.surfacePolicy().name().toLowerCase());
        obj.addProperty("vegetationPolicy", area.vegetationPolicy().name().toLowerCase());
        obj.addProperty("boundaryPolicy", area.boundaryPolicy().name().toLowerCase());
        return obj;
    }

    private LandUseAreaPlan.Area area(JsonObject obj) {
        List<BlockBounds> exclusions = new ArrayList<>();
        for (JsonElement element : requiredArray(obj, "structureFootprintExclusions")) {
            exclusions.add(bounds(element.getAsJsonObject()));
        }
        List<LandUseAreaPlan.BoundaryLoop> loops = new ArrayList<>();
        for (JsonElement element : requiredArray(obj, "boundaryLoops")) {
            JsonObject value = element.getAsJsonObject();
            loops.add(new LandUseAreaPlan.BoundaryLoop(points(requiredArray(value, "points")),
                    booleanValue(value, "hole", false)));
        }
        List<LandUseAreaPlan.GateSlot> gates = new ArrayList<>();
        for (JsonElement element : requiredArray(obj, "gateSlots")) {
            JsonObject value = element.getAsJsonObject();
            gates.add(new LandUseAreaPlan.GateSlot(requiredString(value, "gateId"),
                    point(requiredObject(value, "block")), CardinalDirection.from(requiredString(value, "direction"),
                    CardinalDirection.NORTH), stringValue(value, "sourceAnchorId", "")));
        }
        return new LandUseAreaPlan.Area(requiredString(obj, "areaId"), requiredString(obj, "ruleRef"),
                requiredString(obj, "landUseType"), strings(requiredArray(obj, "sourceGroupIds")),
                strings(requiredArray(obj, "sourceAnchorIds")), points(requiredArray(obj, "seedPoints")),
                spans(requiredArray(obj, "memberSpans")), exclusions, loops, gates,
                doubleValue(obj, "claimCostTotal", 0), enumValue(SurfacePolicy.class, requiredString(obj, "surfacePolicy")),
                enumValue(VegetationPolicy.class, requiredString(obj, "vegetationPolicy")),
                enumValue(BoundaryPolicy.class, requiredString(obj, "boundaryPolicy")));
    }

    private static JsonArray spansJson(List<LandUseAreaPlan.ScanlineSpan> spans) {
        JsonArray values = new JsonArray();
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            JsonObject value = new JsonObject();
            value.addProperty("z", span.z());
            value.addProperty("minX", span.minX());
            value.addProperty("maxX", span.maxX());
            values.add(value);
        }
        return values;
    }

    private static List<LandUseAreaPlan.ScanlineSpan> spans(JsonArray array) {
        List<LandUseAreaPlan.ScanlineSpan> values = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject value = element.getAsJsonObject();
            values.add(new LandUseAreaPlan.ScanlineSpan(requiredInt(value, "z"), requiredInt(value, "minX"),
                    requiredInt(value, "maxX")));
        }
        return values;
    }

    private static JsonArray pointsJson(List<BlockPoint> points) {
        JsonArray values = new JsonArray();
        points.forEach(point -> values.add(pointJson(point)));
        return values;
    }

    private static JsonObject pointJson(BlockPoint point) {
        JsonObject value = new JsonObject();
        value.addProperty("x", point.x());
        value.addProperty("z", point.z());
        return value;
    }

    private static List<BlockPoint> points(JsonArray array) {
        List<BlockPoint> points = new ArrayList<>();
        for (JsonElement element : array) points.add(point(element.getAsJsonObject()));
        return points;
    }

    private static BlockPoint point(JsonObject obj) {
        return new BlockPoint(requiredInt(obj, "x"), requiredInt(obj, "z"));
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        return LandUseTerrainFieldCodec.boundsJson(bounds);
    }

    private static BlockBounds bounds(JsonObject obj) {
        return LandUseTerrainFieldCodec.bounds(obj);
    }

    private static JsonArray stringsJson(List<String> strings) {
        JsonArray values = new JsonArray();
        strings.forEach(values::add);
        return values;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) if (!element.isJsonNull()) values.add(element.getAsString());
        return values;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, value.toUpperCase());
    }

    private static JsonObject requiredObject(JsonObject obj, String key) {
        return LandUseTerrainFieldCodec.requiredObject(obj, key);
    }

    private static JsonArray requiredArray(JsonObject obj, String key) {
        return LandUseTerrainFieldCodec.requiredArray(obj, key);
    }

    private static String requiredString(JsonObject obj, String key) {
        return LandUseTerrainFieldCodec.requiredString(obj, key);
    }

    private static int requiredInt(JsonObject obj, String key) {
        return LandUseTerrainFieldCodec.requiredInt(obj, key);
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        return LandUseTerrainFieldCodec.stringValue(obj, key, fallback);
    }

    private static double doubleValue(JsonObject obj, String key, double fallback) {
        return LandUseTerrainFieldCodec.doubleValue(obj, key, fallback);
    }

    private static boolean booleanValue(JsonObject obj, String key, boolean fallback) {
        return LandUseTerrainFieldCodec.booleanValue(obj, key, fallback);
    }
}
