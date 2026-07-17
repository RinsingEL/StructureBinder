package com.rinsing.geomantia.systems.city.application.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict codec for AI-submitted v0.2 intent. Resolved masks and world coordinates are forbidden. */
public final class DecorationProgramIntentCodec {
    private final CompiledDecorationProgramCodec primitiveCodec = new CompiledDecorationProgramCodec();

    public DecorationProgramIntentPlan parsePlan(JsonObject source) {
        requireOnly(source, Set.of("schemaVersion", "cityId", "catalogHash", "styleProfileId", "styleProfileHash",
                "programs"), "program plan");
        String schema = requiredString(source, "schemaVersion");
        boolean legacySchema = DecorationProgramIntentPlan.LEGACY_SCHEMA.equals(schema);
        if (!DecorationProgramIntentPlan.SCHEMA.equals(schema) && !legacySchema) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_SCHEMA_UNSUPPORTED: " + schema);
        }
        List<DecorationProgramIntent> programs = new ArrayList<>();
        for (JsonElement element : requiredArray(source, "programs")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_INVALID: programs[] entries must be objects");
            }
            programs.add(parseIntent(element.getAsJsonObject(), legacySchema));
        }
        return new DecorationProgramIntentPlan(schema, requiredString(source, "cityId"),
                requiredString(source, "catalogHash"), requiredString(source, "styleProfileId"),
                requiredString(source, "styleProfileHash"), programs);
    }

    public DecorationProgramIntent parseIntent(JsonObject source) {
        return parseIntent(source, false);
    }

    private DecorationProgramIntent parseIntent(JsonObject source, boolean legacySchema) {
        requireOnly(source, Set.of("programId", "targetArea", "coordinateFrame", "shape", "pattern",
                "contentPalette", "terrainPolicy", "conflictPolicy", "priority", "seed"), "program");
        DecorationProgramIntent.TargetArea targetArea = parseTargetArea(requiredObject(source, "targetArea"));
        DecorationProgramIntent.CoordinateFrameIntent frame =
                parseCoordinateFrame(requiredObject(source, "coordinateFrame"));
        CompiledDecorationProgram.ShapeSpec shape = parseShapeIntent(requiredObject(source, "shape"));
        CompiledDecorationProgram.PatternSpec pattern = parsePatternIntent(requiredObject(source, "pattern"));
        CompiledDecorationProgram.ContentPalette palette =
                primitiveCodec.parseContentPalette(requiredObject(source, "contentPalette"));
        for (String paletteSlotId : primitiveCodec.referencedPaletteSlots(pattern)) {
            palette.requireSlot(paletteSlotId);
        }
        return new DecorationProgramIntent(requiredString(source, "programId"), targetArea, frame, shape, pattern,
                palette, primitiveCodec.parseTerrainPolicy(requiredObject(source, "terrainPolicy"), legacySchema),
                primitiveCodec.parseConflictPolicy(requiredObject(source, "conflictPolicy")),
                requiredInt(source, "priority"), requiredLong(source, "seed"));
    }

    public JsonObject toJson(DecorationProgramIntentPlan plan) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", plan.schemaVersion());
        obj.addProperty("cityId", plan.cityId());
        obj.addProperty("catalogHash", plan.catalogHash());
        obj.addProperty("styleProfileId", plan.styleProfileId());
        obj.addProperty("styleProfileHash", plan.styleProfileHash());
        JsonArray programs = new JsonArray();
        plan.programs().forEach(program -> programs.add(toJson(program)));
        obj.add("programs", programs);
        return obj;
    }

    public JsonObject toJson(DecorationProgramIntent intent) {
        JsonObject obj = new JsonObject();
        obj.addProperty("programId", intent.programId());
        JsonObject targetArea = new JsonObject();
        targetArea.addProperty("sourceType", intent.targetArea().sourceType());
        targetArea.addProperty("ref", intent.targetArea().ref());
        targetArea.addProperty("insetBlocks", intent.targetArea().insetBlocks());
        obj.add("targetArea", targetArea);
        JsonObject frame = new JsonObject();
        frame.addProperty("originMode", intent.coordinateFrame().originMode());
        frame.addProperty("orientationMode", intent.coordinateFrame().orientationMode());
        frame.addProperty("quarterTurns", intent.coordinateFrame().quarterTurns());
        frame.addProperty("offsetUBlocks", intent.coordinateFrame().offsetUBlocks());
        frame.addProperty("offsetVBlocks", intent.coordinateFrame().offsetVBlocks());
        obj.add("coordinateFrame", frame);
        obj.add("shape", wrapParams(primitiveCodec.shapeJson(intent.shape())));
        obj.add("pattern", wrapParams(primitiveCodec.patternJson(intent.pattern())));
        obj.add("contentPalette", primitiveCodec.contentPaletteJson(intent.contentPalette()));
        JsonObject terrain = new JsonObject();
        terrain.addProperty("maxSlopeDelta", intent.terrainPolicy().maxSlopeDelta());
        terrain.addProperty("allowWater", intent.terrainPolicy().allowWater());
        terrain.addProperty("invalidTerrainAction", intent.terrainPolicy().invalidTerrainAction().serializedName());
        terrain.addProperty("maxContinuousDropBlocks", intent.terrainPolicy().maxContinuousDropBlocks());
        terrain.addProperty("continuousDropWindowBlocks", intent.terrainPolicy().continuousDropWindowBlocks());
        terrain.addProperty("foundationMode", intent.terrainPolicy().foundationMode().serializedName());
        terrain.addProperty("maxFoundationDepthBlocks", intent.terrainPolicy().maxFoundationDepthBlocks());
        terrain.addProperty("foundationShoulderBlocks", intent.terrainPolicy().foundationShoulderBlocks());
        obj.add("terrainPolicy", terrain);
        JsonObject conflict = new JsonObject();
        conflict.addProperty("onConflict", intent.conflictPolicy().onConflict().serializedName());
        conflict.addProperty("clearanceBlocks", intent.conflictPolicy().clearanceBlocks());
        obj.add("conflictPolicy", conflict);
        obj.addProperty("priority", intent.priority());
        obj.addProperty("seed", intent.seed());
        return obj;
    }

    private DecorationProgramIntent.TargetArea parseTargetArea(JsonObject obj) {
        requireOnly(obj, Set.of("sourceType", "ref", "insetBlocks"), "targetArea");
        return new DecorationProgramIntent.TargetArea(requiredString(obj, "sourceType"),
                requiredString(obj, "ref"), requiredInt(obj, "insetBlocks"));
    }

    private DecorationProgramIntent.CoordinateFrameIntent parseCoordinateFrame(JsonObject obj) {
        requireOnly(obj, Set.of("originMode", "orientationMode", "quarterTurns", "offsetUBlocks",
                "offsetVBlocks"), "coordinateFrame");
        return new DecorationProgramIntent.CoordinateFrameIntent(requiredString(obj, "originMode"),
                requiredString(obj, "orientationMode"), requiredInt(obj, "quarterTurns"),
                requiredInt(obj, "offsetUBlocks"), requiredInt(obj, "offsetVBlocks"));
    }

    private CompiledDecorationProgram.ShapeSpec parseShapeIntent(JsonObject obj) {
        requireOnly(obj, Set.of("type", "params"), "shape");
        return primitiveCodec.parseShape(flattenParams(requiredString(obj, "type"),
                requiredObject(obj, "params")));
    }

    private CompiledDecorationProgram.PatternSpec parsePatternIntent(JsonObject obj) {
        requireOnly(obj, Set.of("type", "params"), "pattern");
        return primitiveCodec.parsePattern(flattenParams(requiredString(obj, "type"),
                requiredObject(obj, "params")));
    }

    private JsonObject flattenParams(String type, JsonObject params) {
        JsonObject flattened = params.deepCopy();
        if (flattened.has("type")) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_FIELD_UNSUPPORTED: params does not accept type");
        }
        flattened.addProperty("type", type);
        return flattened;
    }

    private JsonObject wrapParams(JsonObject flattened) {
        JsonObject copy = flattened.deepCopy();
        String type = copy.remove("type").getAsString();
        JsonObject result = new JsonObject();
        result.addProperty("type", type);
        result.add("params", copy);
        return result;
    }

    private void requireOnly(JsonObject obj, Set<String> allowed, String context) {
        for (String key : obj.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_FIELD_UNSUPPORTED: "
                        + context + " does not accept " + key);
            }
        }
    }

    private JsonObject requiredObject(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonObject()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_FIELD_REQUIRED: " + key);
        }
        return obj.getAsJsonObject(key);
    }

    private JsonArray requiredArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_FIELD_REQUIRED: " + key);
        }
        return obj.getAsJsonArray(key);
    }

    private String requiredString(JsonObject obj, String key) {
        if (!hasPrimitive(obj, key) || !obj.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_FIELD_REQUIRED: " + key);
        }
        String value = obj.get(key).getAsString();
        if (value.isBlank()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_FIELD_REQUIRED: " + key);
        }
        return value;
    }

    private int requiredInt(JsonObject obj, String key) {
        if (!hasPrimitive(obj, key) || !obj.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_FIELD_REQUIRED: " + key);
        }
        return obj.get(key).getAsInt();
    }

    private long requiredLong(JsonObject obj, String key) {
        if (!hasPrimitive(obj, key) || !obj.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_FIELD_REQUIRED: " + key);
        }
        return obj.get(key).getAsLong();
    }

    private boolean hasPrimitive(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive();
    }
}
