package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.terrain.CityContinuousTerrainRunPlanner;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Strict JSON codec and canonical hash owner for {@link CityLandUseSurfacePrintPlan}. */
public final class CityLandUseSurfacePrintPlanCodec {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "cityId", "sourceLandUsePlanHash", "catalogHash", "planHash", "areas");
    private static final Set<String> AREA_FIELDS = Set.of(
            "printAreaId", "landUseAreaId", "sourceGroupIds", "surfaceSettings", "origin",
            "memberSpans", "exclusionSpans", "continuationAxis", "directionMode", "directionCenter", "recipe");
    private static final Set<String> SETTINGS_FIELDS = Set.of(
            "surfacePrintEnabled", "autoConnect", "surfaceBlockId", "cropBlockId", "compatibilityCategory",
            "directionMode", "directionCenter");
    private static final Set<String> UNIFORM_FIELDS = Set.of("recipeType", "surfaceBlockId");
    private static final Set<String> CULTIVATE_FIELDS = Set.of(
            "recipeType", "surfaceBlockId", "cropBlockId", "repeatPeriodBlocks", "fieldBeforeBlocks",
            "channelWidthBlocks", "fieldAfterBlocks", "channelOffsetBlocks", "straightPrefab", "endCapPrefab",
            "terrainPolicy", "runs", "foundationSegments");
    private static final Set<String> PREFAB_FIELDS = Set.of(
            "contentRef", "contentHash", "widthBlocks", "heightBlocks", "depthBlocks");
    private static final Set<String> TERRAIN_POLICY_FIELDS = Set.of(
            "maxSlopeDelta", "allowWater", "maxContinuousDropBlocks", "continuousDropWindowBlocks",
            "foundationMode", "maxFoundationDepthBlocks", "foundationShoulderBlocks");
    private static final Set<String> RUN_FIELDS = Set.of(
            "runId", "continuationAxis", "crossCoordinate", "placements", "terminationOrdinal",
            "terminationReasonCode", "foundationSegments");
    private static final Set<String> PLACEMENT_FIELDS = Set.of(
            "placementId", "runId", "runOrdinal", "terrainSamplePoint", "placementAnchor", "rotationDegrees",
            "footprint", "surfaceY", "targetY", "water", "terrainClass", "decision", "contentRef",
            "contentHash", "appliedContentRef", "appliedContentHash", "reasonCode");
    private static final Set<String> POINT_FIELDS = Set.of("x", "z");
    private static final Set<String> BOUNDS_FIELDS = Set.of("minX", "minZ", "maxX", "maxZ");
    private static final Set<String> SPAN_FIELDS = Set.of("z", "minX", "maxX");
    private static final Set<String> FOUNDATION_FIELDS = Set.of(
            "runId", "x0", "z0", "y0", "x1", "z1", "y1", "halfWidth", "maxDepthBlocks",
            "shoulderBlocks");

    public CityLandUseSurfacePrintPlan withComputedHash(CityLandUseSurfacePrintPlan plan) {
        return plan.withPlanHash(computePlanHash(plan));
    }

    public String computePlanHash(CityLandUseSurfacePrintPlan plan) {
        JsonObject json = toJson(plan.withPlanHash(""));
        json.remove("planHash");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public JsonObject toJson(CityLandUseSurfacePrintPlan plan) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", plan.schemaVersion());
        root.addProperty("cityId", plan.cityId());
        root.addProperty("sourceLandUsePlanHash", plan.sourceLandUsePlanHash());
        root.addProperty("catalogHash", plan.catalogHash());
        if (!plan.planHash().isBlank()) root.addProperty("planHash", plan.planHash());
        JsonArray areas = new JsonArray();
        plan.areas().forEach(area -> areas.add(areaJson(area)));
        root.add("areas", areas);
        return root;
    }

    public CityLandUseSurfacePrintPlan fromJson(JsonObject root) {
        requireObject(root, "root");
        rejectUnknown(root, ROOT_FIELDS, "root");
        List<CityLandUseSurfacePrintPlan.AreaPrint> areas = new ArrayList<>();
        for (JsonElement element : array(root, "areas")) areas.add(area(object(element, "areas[]")));
        CityLandUseSurfacePrintPlan plan = new CityLandUseSurfacePrintPlan(
                text(root, "schemaVersion", false), text(root, "cityId", false),
                text(root, "sourceLandUsePlanHash", false), text(root, "catalogHash", true),
                optionalText(root, "planHash"), areas);
        if (!plan.planHash().isBlank() && !plan.planHash().equals(computePlanHash(plan))) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_PLAN_HASH_MISMATCH", "planHash does not match payload");
        }
        return plan;
    }

    private static JsonObject areaJson(CityLandUseSurfacePrintPlan.AreaPrint area) {
        JsonObject value = new JsonObject();
        value.addProperty("printAreaId", area.printAreaId());
        value.addProperty("landUseAreaId", area.landUseAreaId());
        value.add("sourceGroupIds", stringsJson(area.sourceGroupIds()));
        value.add("surfaceSettings", settingsJson(area.surfaceSettings()));
        value.add("memberSpans", spansJson(area.memberSpans()));
        value.add("exclusionSpans", spansJson(area.exclusionSpans()));
        value.add("origin", pointJson(area.origin()));
        value.addProperty("continuationAxis", area.continuationAxis().name().toLowerCase());
        value.addProperty("directionMode", area.directionMode().name().toLowerCase());
        value.add("directionCenter", nullablePointJson(area.directionCenter()));
        value.add("recipe", recipeJson(area.recipe()));
        return value;
    }

    private static CityLandUseSurfacePrintPlan.AreaPrint area(JsonObject value) {
        rejectUnknown(value, AREA_FIELDS, "area");
        return new CityLandUseSurfacePrintPlan.AreaPrint(
                text(value, "printAreaId", false), text(value, "landUseAreaId", false),
                strings(array(value, "sourceGroupIds")), settings(object(value, "surfaceSettings")),
                spans(array(value, "memberSpans")), spans(array(value, "exclusionSpans")),
                point(object(value, "origin")), enumValue(CityLandUseSurfaceRunCompiler.WorldAxis.class,
                text(value, "continuationAxis", false)),
                enumValue(LandUseSurfaceSettings.DirectionMode.class, text(value, "directionMode", false)),
                nullablePoint(value, "directionCenter"), recipe(object(value, "recipe")));
    }

    private static JsonObject settingsJson(LandUseSurfaceSettings settings) {
        JsonObject value = new JsonObject();
        value.addProperty("surfacePrintEnabled", settings.surfacePrintEnabled());
        value.addProperty("autoConnect", settings.autoConnect());
        value.addProperty("surfaceBlockId", settings.surfaceBlockId());
        value.addProperty("cropBlockId", settings.cropBlockId());
        value.addProperty("compatibilityCategory", settings.compatibilityCategory());
        value.addProperty("directionMode", settings.directionMode().name().toLowerCase());
        value.add("directionCenter", nullablePointJson(settings.directionCenter()));
        return value;
    }

    private static LandUseSurfaceSettings settings(JsonObject value) {
        rejectUnknown(value, SETTINGS_FIELDS, "surfaceSettings");
        return new LandUseSurfaceSettings(bool(value, "surfacePrintEnabled"), bool(value, "autoConnect"),
                text(value, "surfaceBlockId", true), text(value, "cropBlockId", true),
                text(value, "compatibilityCategory", true),
                enumValue(LandUseSurfaceSettings.DirectionMode.class, text(value, "directionMode", false)),
                nullablePoint(value, "directionCenter"));
    }

    private static JsonObject recipeJson(CityLandUseSurfacePrintPlan.Recipe recipe) {
        JsonObject value = new JsonObject();
        if (recipe instanceof CityLandUseSurfacePrintPlan.UniformRecipe uniform) {
            value.addProperty("recipeType", "uniform");
            value.addProperty("surfaceBlockId", uniform.surfaceBlockId());
            return value;
        }
        CityLandUseSurfacePrintPlan.CultivateLinedRecipe cultivate =
                (CityLandUseSurfacePrintPlan.CultivateLinedRecipe) recipe;
        value.addProperty("recipeType", "cultivate_lined");
        value.addProperty("surfaceBlockId", cultivate.surfaceBlockId());
        value.addProperty("cropBlockId", cultivate.cropBlockId());
        value.addProperty("repeatPeriodBlocks", cultivate.repeatPeriodBlocks());
        value.addProperty("fieldBeforeBlocks", cultivate.fieldBeforeBlocks());
        value.addProperty("channelWidthBlocks", cultivate.channelWidthBlocks());
        value.addProperty("fieldAfterBlocks", cultivate.fieldAfterBlocks());
        value.addProperty("channelOffsetBlocks", cultivate.channelOffsetBlocks());
        value.add("straightPrefab", prefabJson(cultivate.straightPrefab()));
        value.add("endCapPrefab", prefabJson(cultivate.endCapPrefab()));
        value.add("terrainPolicy", terrainPolicyJson(cultivate.terrainPolicy()));
        JsonArray runs = new JsonArray();
        cultivate.runs().forEach(run -> runs.add(runJson(run)));
        value.add("runs", runs);
        value.add("foundationSegments", foundationSegmentsJson(cultivate.foundationSegments()));
        return value;
    }

    private static CityLandUseSurfacePrintPlan.Recipe recipe(JsonObject value) {
        String type = text(value, "recipeType", false);
        if ("uniform".equals(type)) {
            rejectUnknown(value, UNIFORM_FIELDS, "recipe");
            return new CityLandUseSurfacePrintPlan.UniformRecipe(text(value, "surfaceBlockId", false));
        }
        if (!"cultivate_lined".equals(type)) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_RECIPE_TYPE_INVALID", type);
        }
        rejectUnknown(value, CULTIVATE_FIELDS, "recipe");
        List<CityLandUseSurfacePrintPlan.SurfaceRun> runs = new ArrayList<>();
        for (JsonElement element : array(value, "runs")) runs.add(run(object(element, "runs[]")));
        List<CityContinuousTerrainRunPlanner.FoundationSegment> foundationSegments =
                foundationSegments(array(value, "foundationSegments"));
        return new CityLandUseSurfacePrintPlan.CultivateLinedRecipe(
                text(value, "surfaceBlockId", false), text(value, "cropBlockId", false),
                integer(value, "repeatPeriodBlocks"), integer(value, "fieldBeforeBlocks"),
                integer(value, "channelWidthBlocks"), integer(value, "fieldAfterBlocks"),
                integer(value, "channelOffsetBlocks"), prefab(object(value, "straightPrefab")),
                prefab(object(value, "endCapPrefab")), terrainPolicy(object(value, "terrainPolicy")), runs,
                foundationSegments);
    }

    private static JsonObject prefabJson(CityLandUseSurfaceRunCompiler.PrefabSpec prefab) {
        JsonObject value = new JsonObject();
        value.addProperty("contentRef", prefab.contentRef());
        value.addProperty("contentHash", prefab.contentHash());
        value.addProperty("widthBlocks", prefab.widthBlocks());
        value.addProperty("heightBlocks", prefab.heightBlocks());
        value.addProperty("depthBlocks", prefab.depthBlocks());
        return value;
    }

    private static CityLandUseSurfaceRunCompiler.PrefabSpec prefab(JsonObject value) {
        rejectUnknown(value, PREFAB_FIELDS, "prefab");
        return new CityLandUseSurfaceRunCompiler.PrefabSpec(text(value, "contentRef", false),
                text(value, "contentHash", false), integer(value, "widthBlocks"),
                integer(value, "heightBlocks"), integer(value, "depthBlocks"));
    }

    private static JsonObject terrainPolicyJson(CityLandUseSurfaceRunCompiler.TerrainPolicy policy) {
        JsonObject value = new JsonObject();
        value.addProperty("maxSlopeDelta", policy.maxSlopeDelta());
        value.addProperty("allowWater", policy.allowWater());
        value.addProperty("maxContinuousDropBlocks", policy.maxContinuousDropBlocks());
        value.addProperty("continuousDropWindowBlocks", policy.continuousDropWindowBlocks());
        value.addProperty("foundationMode", policy.foundationMode().name().toLowerCase());
        value.addProperty("maxFoundationDepthBlocks", policy.maxFoundationDepthBlocks());
        value.addProperty("foundationShoulderBlocks", policy.foundationShoulderBlocks());
        return value;
    }

    private static CityLandUseSurfaceRunCompiler.TerrainPolicy terrainPolicy(JsonObject value) {
        rejectUnknown(value, TERRAIN_POLICY_FIELDS, "terrainPolicy");
        return new CityLandUseSurfaceRunCompiler.TerrainPolicy(integer(value, "maxSlopeDelta"),
                bool(value, "allowWater"), integer(value, "maxContinuousDropBlocks"),
                integer(value, "continuousDropWindowBlocks"),
                enumValue(CityContinuousTerrainRunPlanner.FoundationMode.class,
                        text(value, "foundationMode", false)),
                integer(value, "maxFoundationDepthBlocks"), integer(value, "foundationShoulderBlocks"));
    }

    private static JsonObject runJson(CityLandUseSurfacePrintPlan.SurfaceRun run) {
        JsonObject value = new JsonObject();
        value.addProperty("runId", run.runId());
        value.addProperty("continuationAxis", run.continuationAxis().name().toLowerCase());
        value.addProperty("crossCoordinate", run.crossCoordinate());
        JsonArray placements = new JsonArray();
        run.placements().forEach(placement -> placements.add(placementJson(placement)));
        value.add("placements", placements);
        if (run.terminationOrdinal() == null) value.add("terminationOrdinal", JsonNull.INSTANCE);
        else value.addProperty("terminationOrdinal", run.terminationOrdinal());
        value.addProperty("terminationReasonCode", run.terminationReasonCode());
        value.add("foundationSegments", foundationSegmentsJson(run.foundationSegments()));
        return value;
    }

    private static CityLandUseSurfacePrintPlan.SurfaceRun run(JsonObject value) {
        rejectUnknown(value, RUN_FIELDS, "run");
        List<CityLandUseSurfacePrintPlan.SurfacePlacement> placements = new ArrayList<>();
        for (JsonElement element : array(value, "placements")) {
            placements.add(placement(object(element, "placements[]")));
        }
        return new CityLandUseSurfacePrintPlan.SurfaceRun(text(value, "runId", false),
                enumValue(CityLandUseSurfaceRunCompiler.WorldAxis.class,
                        text(value, "continuationAxis", false)), integer(value, "crossCoordinate"),
                placements, nullableInteger(value, "terminationOrdinal"),
                text(value, "terminationReasonCode", true),
                foundationSegments(array(value, "foundationSegments")));
    }

    private static JsonObject placementJson(CityLandUseSurfacePrintPlan.SurfacePlacement placement) {
        JsonObject value = new JsonObject();
        value.addProperty("placementId", placement.placementId());
        value.addProperty("runId", placement.runId());
        value.addProperty("runOrdinal", placement.runOrdinal());
        value.add("terrainSamplePoint", pointJson(placement.terrainSamplePoint()));
        value.add("placementAnchor", pointJson(placement.placementAnchor()));
        value.addProperty("rotationDegrees", placement.rotationDegrees());
        value.add("footprint", boundsJson(placement.footprint()));
        value.addProperty("surfaceY", placement.surfaceY());
        value.addProperty("targetY", placement.targetY());
        value.addProperty("water", placement.water());
        value.addProperty("terrainClass", placement.terrainClass().name().toLowerCase());
        value.addProperty("decision", placement.decision().name().toLowerCase());
        value.addProperty("contentRef", placement.contentRef());
        value.addProperty("contentHash", placement.contentHash());
        value.addProperty("appliedContentRef", placement.appliedContentRef());
        value.addProperty("appliedContentHash", placement.appliedContentHash());
        value.addProperty("reasonCode", placement.reasonCode());
        return value;
    }

    private static CityLandUseSurfacePrintPlan.SurfacePlacement placement(JsonObject value) {
        rejectUnknown(value, PLACEMENT_FIELDS, "placement");
        return new CityLandUseSurfacePrintPlan.SurfacePlacement(
                text(value, "placementId", false), text(value, "runId", false),
                integer(value, "runOrdinal"), point(object(value, "terrainSamplePoint")),
                point(object(value, "placementAnchor")), integer(value, "rotationDegrees"),
                bounds(object(value, "footprint")), integer(value, "surfaceY"),
                integer(value, "targetY"), bool(value, "water"),
                enumValue(CityContinuousTerrainRunPlanner.TerrainClass.class,
                        text(value, "terrainClass", false)),
                enumValue(CityContinuousTerrainRunPlanner.Decision.class, text(value, "decision", false)),
                text(value, "contentRef", false), text(value, "contentHash", false),
                text(value, "appliedContentRef", false), text(value, "appliedContentHash", false),
                text(value, "reasonCode", false));
    }

    private static JsonObject pointJson(BlockPoint point) {
        JsonObject value = new JsonObject();
        value.addProperty("x", point.x());
        value.addProperty("z", point.z());
        return value;
    }

    private static JsonElement nullablePointJson(BlockPoint point) {
        return point == null ? JsonNull.INSTANCE : pointJson(point);
    }

    private static JsonArray foundationSegmentsJson(
            List<CityContinuousTerrainRunPlanner.FoundationSegment> segments) {
        JsonArray result = new JsonArray();
        for (CityContinuousTerrainRunPlanner.FoundationSegment segment : segments) {
            JsonObject value = new JsonObject();
            value.addProperty("runId", segment.runId());
            value.addProperty("x0", segment.x0());
            value.addProperty("z0", segment.z0());
            value.addProperty("y0", segment.y0());
            value.addProperty("x1", segment.x1());
            value.addProperty("z1", segment.z1());
            value.addProperty("y1", segment.y1());
            value.addProperty("halfWidth", segment.halfWidth());
            value.addProperty("maxDepthBlocks", segment.maxDepthBlocks());
            value.addProperty("shoulderBlocks", segment.shoulderBlocks());
            result.add(value);
        }
        return result;
    }

    private static List<CityContinuousTerrainRunPlanner.FoundationSegment> foundationSegments(JsonArray values) {
        List<CityContinuousTerrainRunPlanner.FoundationSegment> result = new ArrayList<>();
        for (JsonElement element : values) {
            JsonObject value = object(element, "foundationSegments[]");
            rejectUnknown(value, FOUNDATION_FIELDS, "foundationSegment");
            result.add(new CityContinuousTerrainRunPlanner.FoundationSegment(
                    text(value, "runId", false), integer(value, "x0"), integer(value, "z0"),
                    integer(value, "y0"), integer(value, "x1"), integer(value, "z1"),
                    integer(value, "y1"), integer(value, "halfWidth"),
                    integer(value, "maxDepthBlocks"), integer(value, "shoulderBlocks")));
        }
        return result;
    }

    private static JsonArray spansJson(List<com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan.ScanlineSpan> spans) {
        JsonArray result = new JsonArray();
        for (com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan.ScanlineSpan span : spans) {
            JsonObject value = new JsonObject();
            value.addProperty("z", span.z());
            value.addProperty("minX", span.minX());
            value.addProperty("maxX", span.maxX());
            result.add(value);
        }
        return result;
    }

    private static List<com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan.ScanlineSpan> spans(JsonArray values) {
        List<com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan.ScanlineSpan> result = new ArrayList<>();
        for (JsonElement element : values) {
            JsonObject value = object(element, "spans[]");
            rejectUnknown(value, SPAN_FIELDS, "span");
            result.add(new com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan.ScanlineSpan(
                    integer(value, "z"), integer(value, "minX"), integer(value, "maxX")));
        }
        return result;
    }

    private static BlockPoint point(JsonObject value) {
        rejectUnknown(value, POINT_FIELDS, "point");
        return new BlockPoint(integer(value, "x"), integer(value, "z"));
    }

    private static BlockPoint nullablePoint(JsonObject owner, String key) {
        if (!owner.has(key)) throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_REQUIRED", key);
        return owner.get(key).isJsonNull() ? null : point(object(owner, key));
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject value = new JsonObject();
        value.addProperty("minX", bounds.minX());
        value.addProperty("minZ", bounds.minZ());
        value.addProperty("maxX", bounds.maxX());
        value.addProperty("maxZ", bounds.maxZ());
        return value;
    }

    private static BlockBounds bounds(JsonObject value) {
        rejectUnknown(value, BOUNDS_FIELDS, "bounds");
        return new BlockBounds(integer(value, "minX"), integer(value, "minZ"),
                integer(value, "maxX"), integer(value, "maxZ"));
    }

    private static JsonArray stringsJson(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static List<String> strings(JsonArray values) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().isBlank()) {
                throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_INVALID", "Expected non-blank string array");
            }
            result.add(value.getAsString());
        }
        return result;
    }

    private static void requireObject(JsonObject value, String owner) {
        if (value == null) throw fail("CITY_LAND_USE_SURFACE_PRINT_JSON_REQUIRED", owner);
    }

    private static JsonObject object(JsonObject owner, String key) {
        if (!owner.has(key) || !owner.get(key).isJsonObject()) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_REQUIRED", key);
        }
        return owner.getAsJsonObject(key);
    }

    private static JsonObject object(JsonElement value, String owner) {
        if (value == null || !value.isJsonObject()) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_INVALID", owner);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject owner, String key) {
        if (!owner.has(key) || !owner.get(key).isJsonArray()) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_REQUIRED", key);
        }
        return owner.getAsJsonArray(key);
    }

    private static String text(JsonObject owner, String key, boolean allowEmpty) {
        if (!owner.has(key) || !owner.get(key).isJsonPrimitive()
                || !owner.getAsJsonPrimitive(key).isString()) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_REQUIRED", key);
        }
        String value = owner.get(key).getAsString();
        if (!allowEmpty && value.isBlank()) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_INVALID", key);
        }
        return value;
    }

    private static String optionalText(JsonObject owner, String key) {
        return owner.has(key) ? text(owner, key, true) : "";
    }

    private static int integer(JsonObject owner, String key) {
        if (!owner.has(key) || !owner.get(key).isJsonPrimitive()
                || !owner.getAsJsonPrimitive(key).isNumber()) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_REQUIRED", key);
        }
        try {
            return owner.get(key).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException ex) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_INVALID", key);
        }
    }

    private static Integer nullableInteger(JsonObject owner, String key) {
        if (!owner.has(key)) throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_REQUIRED", key);
        return owner.get(key).isJsonNull() ? null : integer(owner, key);
    }

    private static boolean bool(JsonObject owner, String key) {
        if (!owner.has(key) || !owner.get(key).isJsonPrimitive()
                || !owner.getAsJsonPrimitive(key).isBoolean()) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_REQUIRED", key);
        }
        return owner.get(key).getAsBoolean();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException ex) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_ENUM_INVALID", type.getSimpleName() + ':' + value);
        }
    }

    private static void rejectUnknown(JsonObject value, Set<String> allowed, String owner) {
        for (String key : value.keySet()) {
            if (!allowed.contains(key)) {
                throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_UNKNOWN", owner + '.' + key);
            }
        }
    }

    private static IllegalArgumentException fail(String reason, String detail) {
        return new IllegalArgumentException(reason + ": " + detail);
    }
}
