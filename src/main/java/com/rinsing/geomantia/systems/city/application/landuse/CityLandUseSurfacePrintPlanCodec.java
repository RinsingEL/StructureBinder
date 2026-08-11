package com.rinsing.geomantia.systems.city.application.landuse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.landuse.LandscapeFillProgram;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseSurfaceSettings;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Strict current-only JSON codec and canonical hash owner for SurfacePrintPlan v0.6. */
public final class CityLandUseSurfacePrintPlanCodec {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "cityId", "sourceLandUsePlanHash", "planHash", "areas",
            "sharedBoundarySpans");
    private static final Set<String> AREA_FIELDS = Set.of(
            "printAreaId", "landUseAreaId", "sourceGroupIds", "surfaceSettings",
            "memberSpans", "exclusionSpans", "surfaceAlgorithm", "algorithmAnchor", "recipe");
    private static final Set<String> SETTINGS_FIELDS = Set.of(
            "surfacePrintEnabled", "autoConnect", "surfaceBlockId", "cropBlockId", "compatibilityCategory",
            "surfaceAlgorithm", "algorithmAnchor", "channelBankBlockId", "channelWaterBlockId",
            "channelBankOverlayBlockId", "boundaryBlockId", "fieldBeforeBlocks", "channelWidthBlocks",
            "fieldAfterBlocks");
    private static final Set<String> UNIFORM_FIELDS = Set.of("recipeType", "surfaceBlockId", "boundaryBlockId");
    private static final Set<String> CONTOUR_FIELDS = Set.of(
            "recipeType", "surfaceBlockId", "cropBlockId", "channelBankBlockId", "channelWaterBlockId",
            "channelBankOverlayBlockId", "repeatPeriodBlocks", "fieldBeforeBlocks", "channelWidthBlocks",
            "fieldAfterBlocks", "classificationMode", "anchor", "bandSpans", "boundaryBlockId");
    private static final Set<String> BAND_SPAN_FIELDS = Set.of("z", "minX", "maxX", "role");
    private static final Set<String> RELAY_FIELDS = Set.of(
            "recipeType", "surfaceBlockId", "cropBlockId", "channelBankBlockId", "channelWaterBlockId",
            "channelBankOverlayBlockId", "boundaryBlockId", "fillProfileRef", "primaryRoleRef",
            "stableSeed", "effectiveSource", "roleDefinitions", "contentWeights", "regionSpans",
            "regionTraces");
    private static final Set<String> RELAY_ROLE_FIELDS = Set.of(
            "roleRef", "materialRole", "growthForm", "targetShare");
    private static final Set<String> RELAY_CONTENT_FIELDS = Set.of("contentRef", "weight");
    private static final Set<String> REGION_SPAN_FIELDS = Set.of(
            "z", "minX", "maxX", "regionId", "roleRef");
    private static final Set<String> REGION_TRACE_FIELDS = Set.of(
            "regionId", "parentRegionId", "roleRef", "growthForm", "start", "sourceFrontier",
            "targetAreaBlocks", "actualAreaBlocks");
    private static final Set<String> POINT_FIELDS = Set.of("x", "z");
    private static final Set<String> SPAN_FIELDS = Set.of("z", "minX", "maxX");

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
        if (!plan.planHash().isBlank()) root.addProperty("planHash", plan.planHash());
        JsonArray areas = new JsonArray();
        plan.areas().forEach(area -> areas.add(areaJson(area)));
        root.add("areas", areas);
        JsonArray shared = new JsonArray();
        for (CityLandUseSurfacePrintPlan.SharedBoundaryPrintSpan span : plan.sharedBoundarySpans()) {
            JsonObject value = new JsonObject();
            value.addProperty("z", span.z());
            value.addProperty("minX", span.minX());
            value.addProperty("maxX", span.maxX());
            value.addProperty("writerAreaId", span.writerAreaId());
            value.addProperty("neighborAreaId", span.neighborAreaId());
            value.addProperty("relation", span.relation().name());
            value.addProperty("boundaryBlockId", span.boundaryBlockId());
            shared.add(value);
        }
        root.add("sharedBoundarySpans", shared);
        return root;
    }

    public CityLandUseSurfacePrintPlan fromJson(JsonObject root) {
        requireObject(root, "root");
        String schemaVersion = text(root, "schemaVersion", false);
        if (!CityLandUseSurfacePrintPlan.CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_SCHEMA_UNSUPPORTED", schemaVersion);
        }
        rejectUnknown(root, ROOT_FIELDS, "root");
        List<CityLandUseSurfacePrintPlan.AreaPrint> areas = new ArrayList<>();
        for (JsonElement element : array(root, "areas")) areas.add(area(object(element, "areas[]")));
        List<CityLandUseSurfacePrintPlan.SharedBoundaryPrintSpan> shared = new ArrayList<>();
        for (JsonElement element : array(root, "sharedBoundarySpans")) {
            JsonObject value = object(element, "sharedBoundarySpans[]");
            shared.add(new CityLandUseSurfacePrintPlan.SharedBoundaryPrintSpan(integer(value, "z"),
                    integer(value, "minX"), integer(value, "maxX"), text(value, "writerAreaId", false),
                    text(value, "neighborAreaId", false), LandUseAreaPlan.SharedBoundaryRelation.valueOf(
                    text(value, "relation", false)), optionalText(value, "boundaryBlockId")));
        }
        CityLandUseSurfacePrintPlan plan = new CityLandUseSurfacePrintPlan(
                schemaVersion, text(root, "cityId", false), text(root, "sourceLandUsePlanHash", false),
                optionalText(root, "planHash"), areas, shared);
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
        value.addProperty("surfaceAlgorithm", area.surfaceAlgorithm().name().toLowerCase());
        value.add("algorithmAnchor", nullablePointJson(area.algorithmAnchor()));
        value.add("recipe", recipeJson(area.recipe()));
        return value;
    }

    private static CityLandUseSurfacePrintPlan.AreaPrint area(JsonObject value) {
        rejectUnknown(value, AREA_FIELDS, "area");
        return new CityLandUseSurfacePrintPlan.AreaPrint(
                text(value, "printAreaId", false), text(value, "landUseAreaId", false),
                strings(array(value, "sourceGroupIds")), settings(object(value, "surfaceSettings")),
                spans(array(value, "memberSpans")), spans(array(value, "exclusionSpans")),
                enumValue(LandUseSurfaceSettings.SurfaceAlgorithm.class,
                        text(value, "surfaceAlgorithm", false)),
                nullablePoint(value, "algorithmAnchor"), recipe(object(value, "recipe")));
    }

    private static JsonObject settingsJson(LandUseSurfaceSettings settings) {
        JsonObject value = new JsonObject();
        value.addProperty("surfacePrintEnabled", settings.surfacePrintEnabled());
        value.addProperty("autoConnect", settings.autoConnect());
        value.addProperty("surfaceBlockId", settings.surfaceBlockId());
        value.addProperty("cropBlockId", settings.cropBlockId());
        value.addProperty("compatibilityCategory", settings.compatibilityCategory());
        value.addProperty("surfaceAlgorithm", settings.surfaceAlgorithm().name().toLowerCase());
        value.add("algorithmAnchor", nullablePointJson(settings.algorithmAnchor()));
        value.addProperty("channelBankBlockId", settings.channelBankBlockId());
        value.addProperty("channelWaterBlockId", settings.channelWaterBlockId());
        value.addProperty("channelBankOverlayBlockId", settings.channelBankOverlayBlockId());
        value.addProperty("boundaryBlockId", settings.boundaryBlockId());
        value.addProperty("fieldBeforeBlocks", settings.fieldBeforeBlocks());
        value.addProperty("channelWidthBlocks", settings.channelWidthBlocks());
        value.addProperty("fieldAfterBlocks", settings.fieldAfterBlocks());
        return value;
    }

    private static LandUseSurfaceSettings settings(JsonObject value) {
        rejectUnknown(value, SETTINGS_FIELDS, "surfaceSettings");
        return new LandUseSurfaceSettings(bool(value, "surfacePrintEnabled"), bool(value, "autoConnect"),
                text(value, "surfaceBlockId", true), text(value, "cropBlockId", true),
                text(value, "compatibilityCategory", true),
                enumValue(LandUseSurfaceSettings.SurfaceAlgorithm.class,
                        text(value, "surfaceAlgorithm", false)),
                nullablePoint(value, "algorithmAnchor"), text(value, "channelBankBlockId", true),
                text(value, "channelWaterBlockId", true), text(value, "channelBankOverlayBlockId", true),
                text(value, "boundaryBlockId", true), integer(value, "fieldBeforeBlocks"),
                integer(value, "channelWidthBlocks"), integer(value, "fieldAfterBlocks"));
    }

    private static JsonObject recipeJson(CityLandUseSurfacePrintPlan.Recipe recipe) {
        JsonObject value = new JsonObject();
        if (recipe instanceof CityLandUseSurfacePrintPlan.UniformRecipe uniform) {
            value.addProperty("recipeType", "uniform");
            value.addProperty("surfaceBlockId", uniform.surfaceBlockId());
            value.addProperty("boundaryBlockId", uniform.boundaryBlockId());
            return value;
        }
        if (recipe instanceof CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe relay) {
            value.addProperty("recipeType", "relay_region_growth");
            value.addProperty("surfaceBlockId", relay.surfaceBlockId());
            value.addProperty("cropBlockId", relay.cropBlockId());
            value.addProperty("channelBankBlockId", relay.channelBankBlockId());
            value.addProperty("channelWaterBlockId", relay.channelWaterBlockId());
            value.addProperty("channelBankOverlayBlockId", relay.channelBankOverlayBlockId());
            value.addProperty("boundaryBlockId", relay.boundaryBlockId());
            value.addProperty("fillProfileRef", relay.fillProfileRef());
            value.addProperty("primaryRoleRef", relay.primaryRoleRef());
            value.addProperty("stableSeed", relay.stableSeed());
            value.add("effectiveSource", pointJson(relay.effectiveSource()));
            JsonArray definitions = new JsonArray();
            for (CityLandUseSurfacePrintPlan.RelayRoleDefinition definition : relay.roleDefinitions()) {
                JsonObject item = new JsonObject();
                item.addProperty("roleRef", definition.roleRef());
                item.addProperty("materialRole", definition.materialRole().name().toLowerCase());
                item.addProperty("growthForm", definition.growthForm().name().toLowerCase());
                item.addProperty("targetShare", definition.targetShare());
                definitions.add(item);
            }
            value.add("roleDefinitions", definitions);
            JsonArray content = new JsonArray();
            for (CityLandUseSurfacePrintPlan.RelayContentWeight weight : relay.contentWeights()) {
                JsonObject item = new JsonObject();
                item.addProperty("contentRef", weight.contentRef());
                item.addProperty("weight", weight.weight());
                content.add(item);
            }
            value.add("contentWeights", content);
            JsonArray spans = new JsonArray();
            for (CityLandUseSurfacePrintPlan.RegionSpan span : relay.regionSpans()) {
                JsonObject item = new JsonObject();
                item.addProperty("z", span.z());
                item.addProperty("minX", span.minX());
                item.addProperty("maxX", span.maxX());
                item.addProperty("regionId", span.regionId());
                item.addProperty("roleRef", span.roleRef());
                spans.add(item);
            }
            value.add("regionSpans", spans);
            JsonArray traces = new JsonArray();
            for (CityLandUseSurfacePrintPlan.RegionTrace trace : relay.regionTraces()) {
                JsonObject item = new JsonObject();
                item.addProperty("regionId", trace.regionId());
                item.addProperty("parentRegionId", trace.parentRegionId());
                item.addProperty("roleRef", trace.roleRef());
                item.addProperty("growthForm", trace.growthForm().name().toLowerCase());
                item.add("start", pointJson(trace.start()));
                item.add("sourceFrontier", nullablePointJson(trace.sourceFrontier()));
                item.addProperty("targetAreaBlocks", trace.targetAreaBlocks());
                item.addProperty("actualAreaBlocks", trace.actualAreaBlocks());
                traces.add(item);
            }
            value.add("regionTraces", traces);
            return value;
        }
        CityLandUseSurfacePrintPlan.ContourBandsRecipe contour =
                (CityLandUseSurfacePrintPlan.ContourBandsRecipe) recipe;
        value.addProperty("recipeType", "contour_bands");
        value.addProperty("surfaceBlockId", contour.surfaceBlockId());
        value.addProperty("cropBlockId", contour.cropBlockId());
        value.addProperty("channelBankBlockId", contour.channelBankBlockId());
        value.addProperty("channelWaterBlockId", contour.channelWaterBlockId());
        value.addProperty("channelBankOverlayBlockId", contour.channelBankOverlayBlockId());
        value.addProperty("boundaryBlockId", contour.boundaryBlockId());
        value.addProperty("repeatPeriodBlocks", contour.repeatPeriodBlocks());
        value.addProperty("fieldBeforeBlocks", contour.fieldBeforeBlocks());
        value.addProperty("channelWidthBlocks", contour.channelWidthBlocks());
        value.addProperty("fieldAfterBlocks", contour.fieldAfterBlocks());
        value.addProperty("classificationMode", contour.classificationMode().name().toLowerCase());
        value.add("anchor", pointJson(contour.anchor()));
        JsonArray bandSpans = new JsonArray();
        contour.bandSpans().forEach(span -> bandSpans.add(bandSpanJson(span)));
        value.add("bandSpans", bandSpans);
        return value;
    }

    private static CityLandUseSurfacePrintPlan.Recipe recipe(JsonObject value) {
        String type = text(value, "recipeType", false);
        if ("uniform".equals(type)) {
            rejectUnknown(value, UNIFORM_FIELDS, "recipe");
            return new CityLandUseSurfacePrintPlan.UniformRecipe(text(value, "surfaceBlockId", false),
                    text(value, "boundaryBlockId", true));
        }
        if ("relay_region_growth".equals(type)) {
            rejectUnknown(value, RELAY_FIELDS, "recipe");
            List<CityLandUseSurfacePrintPlan.RelayRoleDefinition> definitions = new ArrayList<>();
            for (JsonElement element : array(value, "roleDefinitions")) {
                JsonObject item = object(element, "roleDefinitions[]");
                rejectUnknown(item, RELAY_ROLE_FIELDS, "roleDefinition");
                definitions.add(new CityLandUseSurfacePrintPlan.RelayRoleDefinition(
                        text(item, "roleRef", false),
                        enumValue(LandscapeFillProgram.MaterialRole.class,
                                text(item, "materialRole", false)),
                        enumValue(LandscapeFillProgram.GrowthForm.class,
                                text(item, "growthForm", false)),
                        number(item, "targetShare")));
            }
            List<CityLandUseSurfacePrintPlan.RelayContentWeight> content = new ArrayList<>();
            for (JsonElement element : array(value, "contentWeights")) {
                JsonObject item = object(element, "contentWeights[]");
                rejectUnknown(item, RELAY_CONTENT_FIELDS, "contentWeight");
                content.add(new CityLandUseSurfacePrintPlan.RelayContentWeight(
                        text(item, "contentRef", false), number(item, "weight")));
            }
            List<CityLandUseSurfacePrintPlan.RegionSpan> spans = new ArrayList<>();
            for (JsonElement element : array(value, "regionSpans")) {
                JsonObject item = object(element, "regionSpans[]");
                rejectUnknown(item, REGION_SPAN_FIELDS, "regionSpan");
                spans.add(new CityLandUseSurfacePrintPlan.RegionSpan(integer(item, "z"),
                        integer(item, "minX"), integer(item, "maxX"), text(item, "regionId", false),
                        text(item, "roleRef", false)));
            }
            List<CityLandUseSurfacePrintPlan.RegionTrace> traces = new ArrayList<>();
            for (JsonElement element : array(value, "regionTraces")) {
                JsonObject item = object(element, "regionTraces[]");
                rejectUnknown(item, REGION_TRACE_FIELDS, "regionTrace");
                traces.add(new CityLandUseSurfacePrintPlan.RegionTrace(text(item, "regionId", false),
                        text(item, "parentRegionId", true), text(item, "roleRef", false),
                        enumValue(LandscapeFillProgram.GrowthForm.class, text(item, "growthForm", false)),
                        point(object(item, "start")), nullablePoint(item, "sourceFrontier"),
                        integer(item, "targetAreaBlocks"), integer(item, "actualAreaBlocks")));
            }
            return new CityLandUseSurfacePrintPlan.RelayRegionGrowthRecipe(
                    text(value, "surfaceBlockId", false), text(value, "cropBlockId", true),
                    text(value, "channelBankBlockId", true), text(value, "channelWaterBlockId", true),
                    text(value, "channelBankOverlayBlockId", true), text(value, "boundaryBlockId", true),
                    text(value, "fillProfileRef", false), text(value, "primaryRoleRef", false),
                    longInteger(value, "stableSeed"), point(object(value, "effectiveSource")),
                    definitions, content, spans, traces);
        }
        if (!"contour_bands".equals(type)) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_RECIPE_TYPE_INVALID", type);
        }
        rejectUnknown(value, CONTOUR_FIELDS, "recipe");
        List<CityLandUseSurfacePrintPlan.BandSpan> bandSpans = new ArrayList<>();
        for (JsonElement element : array(value, "bandSpans")) {
            bandSpans.add(bandSpan(object(element, "bandSpans[]")));
        }
        return new CityLandUseSurfacePrintPlan.ContourBandsRecipe(
                text(value, "surfaceBlockId", false), text(value, "cropBlockId", false),
                text(value, "channelBankBlockId", false), text(value, "channelWaterBlockId", false),
                text(value, "channelBankOverlayBlockId", false), text(value, "boundaryBlockId", true),
                integer(value, "repeatPeriodBlocks"),
                integer(value, "fieldBeforeBlocks"), integer(value, "channelWidthBlocks"),
                integer(value, "fieldAfterBlocks"),
                enumValue(CityLandUseSurfacePrintPlan.ClassificationMode.class,
                        text(value, "classificationMode", false)),
                point(object(value, "anchor")), bandSpans);
    }

    private static JsonObject bandSpanJson(CityLandUseSurfacePrintPlan.BandSpan span) {
        JsonObject value = new JsonObject();
        value.addProperty("z", span.z());
        value.addProperty("minX", span.minX());
        value.addProperty("maxX", span.maxX());
        value.addProperty("role", span.role().name().toLowerCase());
        return value;
    }

    private static CityLandUseSurfacePrintPlan.BandSpan bandSpan(JsonObject value) {
        rejectUnknown(value, BAND_SPAN_FIELDS, "bandSpan");
        return new CityLandUseSurfacePrintPlan.BandSpan(integer(value, "z"), integer(value, "minX"),
                integer(value, "maxX"), enumValue(CityLandUseSurfacePrintPlan.BandRole.class,
                text(value, "role", false)));
    }

    private static JsonArray spansJson(List<LandUseAreaPlan.ScanlineSpan> spans) {
        JsonArray result = new JsonArray();
        for (LandUseAreaPlan.ScanlineSpan span : spans) {
            JsonObject value = new JsonObject();
            value.addProperty("z", span.z());
            value.addProperty("minX", span.minX());
            value.addProperty("maxX", span.maxX());
            result.add(value);
        }
        return result;
    }

    private static List<LandUseAreaPlan.ScanlineSpan> spans(JsonArray values) {
        List<LandUseAreaPlan.ScanlineSpan> result = new ArrayList<>();
        for (JsonElement element : values) {
            JsonObject value = object(element, "spans[]");
            rejectUnknown(value, SPAN_FIELDS, "span");
            result.add(new LandUseAreaPlan.ScanlineSpan(
                    integer(value, "z"), integer(value, "minX"), integer(value, "maxX")));
        }
        return result;
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

    private static BlockPoint point(JsonObject value) {
        rejectUnknown(value, POINT_FIELDS, "point");
        return new BlockPoint(integer(value, "x"), integer(value, "z"));
    }

    private static BlockPoint nullablePoint(JsonObject owner, String key) {
        if (!owner.has(key)) throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_REQUIRED", key);
        return owner.get(key).isJsonNull() ? null : point(object(owner, key));
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

    private static long longInteger(JsonObject owner, String key) {
        if (!owner.has(key) || !owner.get(key).isJsonPrimitive()
                || !owner.getAsJsonPrimitive(key).isNumber()) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_REQUIRED", key);
        }
        try {
            return owner.get(key).getAsBigDecimal().longValueExact();
        } catch (ArithmeticException ex) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_INVALID", key);
        }
    }

    private static double number(JsonObject owner, String key) {
        if (!owner.has(key) || !owner.get(key).isJsonPrimitive()
                || !owner.getAsJsonPrimitive(key).isNumber()) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_REQUIRED", key);
        }
        double value = owner.get(key).getAsDouble();
        if (!Double.isFinite(value)) {
            throw fail("CITY_LAND_USE_SURFACE_PRINT_FIELD_INVALID", key);
        }
        return value;
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
