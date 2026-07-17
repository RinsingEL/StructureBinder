package com.rinsing.geomantia.systems.city.application.dressing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.Axis;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.ContentEntry;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.CrossSectionBand;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.LocalPoint;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.PaletteSlot;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.PatternSpec;
import com.rinsing.geomantia.systems.city.application.dressing.CompiledDecorationProgram.ShapeSpec;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Strict persistence codec for resolved internal programs. Never use this codec on AI input. */
public final class CompiledDecorationProgramCodec {
    public CompiledDecorationProgramPlan parsePlan(JsonObject source) {
        requireOnly(source, Set.of("schemaVersion", "cityId", "catalogHash", "styleProfileId", "styleProfileHash",
                "hardObstacles", "programs"),
                "compiled program plan");
        String schema = requiredString(source, "schemaVersion");
        if (!CompiledDecorationProgramPlan.SCHEMA.equals(schema)
                && !CompiledDecorationProgramPlan.LEGACY_SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_PLAN_SCHEMA_UNSUPPORTED: " + schema);
        }
        JsonArray programsJson = requiredArray(source, "programs");
        List<CompiledDecorationProgram> programs = new ArrayList<>();
        for (JsonElement element : programsJson) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_INVALID: programs[] entries must be objects");
            }
            programs.add(parseProgram(element.getAsJsonObject()));
        }
        List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles = new ArrayList<>();
        for (JsonElement element : requiredArray(source, "hardObstacles")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("CITY_DECORATION_HARD_OBSTACLE_INVALID");
            }
            JsonObject obstacle = element.getAsJsonObject();
            requireOnly(obstacle, Set.of("obstacleType", "sourceRef", "blockBounds"), "hard obstacle");
            hardObstacles.add(new CompiledDecorationProgramPlan.HardObstacle(
                    requiredString(obstacle, "obstacleType"), requiredString(obstacle, "sourceRef"),
                    parseBounds(requiredObject(obstacle, "blockBounds"))));
        }
        return new CompiledDecorationProgramPlan(schema, requiredString(source, "cityId"),
                requiredString(source, "catalogHash"), requiredString(source, "styleProfileId"),
                requiredString(source, "styleProfileHash"), hardObstacles, programs);
    }

    public CompiledDecorationProgram parseProgram(JsonObject source) {
        requireOnly(source, Set.of("schemaVersion", "programId", "priority", "seed", "targetMask",
                "coordinateFrame", "shape", "pattern", "contentPalette", "terrainPolicy", "conflictPolicy"),
                "program");
        String schema = requiredString(source, "schemaVersion");
        boolean legacySchema = CompiledDecorationProgram.LEGACY_SCHEMA.equals(schema);
        if (!CompiledDecorationProgram.SCHEMA.equals(schema) && !legacySchema) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_SCHEMA_UNSUPPORTED: " + schema);
        }
        CompiledDecorationProgram program = new CompiledDecorationProgram(schema, requiredString(source, "programId"),
                requiredInt(source, "priority"), requiredLong(source, "seed"),
                parseTargetMask(requiredObject(source, "targetMask")),
                parseCoordinateFrame(requiredObject(source, "coordinateFrame")),
                parseShape(requiredObject(source, "shape")),
                parsePattern(requiredObject(source, "pattern")),
                parseContentPalette(requiredObject(source, "contentPalette")),
                parseTerrainPolicy(requiredObject(source, "terrainPolicy"), legacySchema),
                parseConflictPolicy(requiredObject(source, "conflictPolicy")));
        for (String paletteSlotId : referencedPaletteSlots(program.pattern())) {
            program.contentPalette().requireSlot(paletteSlotId);
        }
        return program;
    }

    public JsonObject toJson(CompiledDecorationProgramPlan plan) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", plan.schemaVersion());
        obj.addProperty("cityId", plan.cityId());
        obj.addProperty("catalogHash", plan.catalogHash());
        obj.addProperty("styleProfileId", plan.styleProfileId());
        obj.addProperty("styleProfileHash", plan.styleProfileHash());
        JsonArray hardObstacles = new JsonArray();
        plan.hardObstacles().forEach(obstacle -> {
            JsonObject obstacleJson = new JsonObject();
            obstacleJson.addProperty("obstacleType", obstacle.obstacleType());
            obstacleJson.addProperty("sourceRef", obstacle.sourceRef());
            obstacleJson.add("blockBounds", boundsJson(obstacle.blockBounds()));
            hardObstacles.add(obstacleJson);
        });
        obj.add("hardObstacles", hardObstacles);
        JsonArray programs = new JsonArray();
        plan.programs().forEach(program -> programs.add(toJson(program)));
        obj.add("programs", programs);
        return obj;
    }

    public JsonObject toJson(CompiledDecorationProgram program) {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", program.schemaVersion());
        obj.addProperty("programId", program.programId());
        obj.addProperty("priority", program.priority());
        obj.addProperty("seed", program.seed());
        obj.add("targetMask", targetMaskJson(program.targetMask()));
        obj.add("coordinateFrame", coordinateFrameJson(program.coordinateFrame()));
        obj.add("shape", shapeJson(program.shape()));
        obj.add("pattern", patternJson(program.pattern()));
        obj.add("contentPalette", contentPaletteJson(program.contentPalette()));
        JsonObject terrain = new JsonObject();
        terrain.addProperty("maxSlopeDelta", program.terrainPolicy().maxSlopeDelta());
        terrain.addProperty("allowWater", program.terrainPolicy().allowWater());
        terrain.addProperty("invalidTerrainAction", program.terrainPolicy().invalidTerrainAction().serializedName());
        if (!CompiledDecorationProgram.LEGACY_SCHEMA.equals(program.schemaVersion())) {
            terrain.addProperty("maxContinuousDropBlocks", program.terrainPolicy().maxContinuousDropBlocks());
            terrain.addProperty("continuousDropWindowBlocks", program.terrainPolicy().continuousDropWindowBlocks());
            terrain.addProperty("foundationMode", program.terrainPolicy().foundationMode().serializedName());
            terrain.addProperty("maxFoundationDepthBlocks", program.terrainPolicy().maxFoundationDepthBlocks());
            terrain.addProperty("foundationShoulderBlocks", program.terrainPolicy().foundationShoulderBlocks());
        }
        obj.add("terrainPolicy", terrain);
        JsonObject conflict = new JsonObject();
        conflict.addProperty("onConflict", program.conflictPolicy().onConflict().serializedName());
        conflict.addProperty("clearanceBlocks", program.conflictPolicy().clearanceBlocks());
        obj.add("conflictPolicy", conflict);
        return obj;
    }

    private CompiledDecorationProgram.TargetMask parseTargetMask(JsonObject obj) {
        requireOnly(obj, Set.of("maskId", "memberBounds"), "targetMask");
        List<BlockBounds> members = new ArrayList<>();
        for (JsonElement element : requiredArray(obj, "memberBounds")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("CITY_DECORATION_TARGET_MASK_MEMBER_INVALID");
            }
            members.add(parseBounds(element.getAsJsonObject()));
        }
        return new CompiledDecorationProgram.TargetMask(requiredString(obj, "maskId"), members);
    }

    private CompiledDecorationProgram.CoordinateFrame parseCoordinateFrame(JsonObject obj) {
        requireOnly(obj, Set.of("origin", "axisU", "axisV"), "coordinateFrame");
        return new CompiledDecorationProgram.CoordinateFrame(parsePoint(requiredObject(obj, "origin")),
                parseVector(requiredObject(obj, "axisU"), "axisU"),
                parseVector(requiredObject(obj, "axisV"), "axisV"));
    }

    ShapeSpec parseShape(JsonObject obj) {
        String type = requiredString(obj, "type");
        return switch (type) {
            case "target_mask" -> {
                requireOnly(obj, Set.of("type"), "shape target_mask");
                yield new CompiledDecorationProgram.TargetMaskShape();
            }
            case "rectangle" -> {
                requireOnly(obj, Set.of("type", "minU", "minV", "maxU", "maxV"), "shape rectangle");
                yield new CompiledDecorationProgram.RectangleShape(requiredInt(obj, "minU"), requiredInt(obj, "minV"),
                        requiredInt(obj, "maxU"), requiredInt(obj, "maxV"));
            }
            case "ellipse" -> {
                requireOnly(obj, Set.of("type", "centerU", "centerV", "radiusU", "radiusV"), "shape ellipse");
                yield new CompiledDecorationProgram.EllipseShape(requiredInt(obj, "centerU"), requiredInt(obj, "centerV"),
                        requiredInt(obj, "radiusU"), requiredInt(obj, "radiusV"));
            }
            case "ring" -> {
                requireOnly(obj, Set.of("type", "centerU", "centerV", "innerRadiusU", "innerRadiusV",
                        "outerRadiusU", "outerRadiusV"), "shape ring");
                yield new CompiledDecorationProgram.RingShape(requiredInt(obj, "centerU"), requiredInt(obj, "centerV"),
                        requiredInt(obj, "innerRadiusU"), requiredInt(obj, "innerRadiusV"),
                        requiredInt(obj, "outerRadiusU"), requiredInt(obj, "outerRadiusV"));
            }
            case "polygon" -> {
                requireOnly(obj, Set.of("type", "vertices"), "shape polygon");
                List<LocalPoint> vertices = new ArrayList<>();
                for (JsonElement element : requiredArray(obj, "vertices")) {
                    if (!element.isJsonObject()) {
                        throw new IllegalArgumentException("CITY_DECORATION_POLYGON_VERTEX_INVALID");
                    }
                    JsonObject vertex = element.getAsJsonObject();
                    requireOnly(vertex, Set.of("u", "v"), "polygon vertex");
                    vertices.add(new LocalPoint(requiredInt(vertex, "u"), requiredInt(vertex, "v")));
                }
                yield new CompiledDecorationProgram.PolygonShape(vertices);
            }
            default -> throw new IllegalArgumentException("CITY_DECORATION_SHAPE_UNSUPPORTED: " + type);
        };
    }

    PatternSpec parsePattern(JsonObject obj) {
        String type = requiredString(obj, "type");
        return switch (type) {
            case "uniform_fill" -> {
                requireOnly(obj, Set.of("type", "paletteSlotId"), "pattern uniform_fill");
                yield new CompiledDecorationProgram.UniformFillPattern(requiredString(obj, "paletteSlotId"));
            }
            case "cross_section_repeat" -> {
                requireOnly(obj, Set.of("type", "axis", "offsetBlocks", "bands"), "pattern cross_section_repeat");
                List<CrossSectionBand> bands = new ArrayList<>();
                for (JsonElement element : requiredArray(obj, "bands")) {
                    if (!element.isJsonObject()) {
                        throw new IllegalArgumentException("CITY_DECORATION_CROSS_SECTION_BAND_INVALID");
                    }
                    JsonObject band = element.getAsJsonObject();
                    requireOnly(band, Set.of("paletteSlotId", "widthBlocks"), "cross-section band");
                    bands.add(new CrossSectionBand(requiredString(band, "paletteSlotId"),
                            requiredInt(band, "widthBlocks")));
                }
                yield new CompiledDecorationProgram.CrossSectionRepeatPattern(Axis.parse(requiredString(obj, "axis")),
                        requiredInt(obj, "offsetBlocks"), bands);
            }
            case "parallel_rows" -> {
                requireOnly(obj, Set.of("type", "axis", "paletteSlotId", "rowWidthBlocks",
                        "spacingBlocks", "offsetBlocks"), "pattern parallel_rows");
                yield new CompiledDecorationProgram.ParallelRowsPattern(Axis.parse(requiredString(obj, "axis")),
                        requiredString(obj, "paletteSlotId"), requiredInt(obj, "rowWidthBlocks"),
                        requiredInt(obj, "spacingBlocks"), requiredInt(obj, "offsetBlocks"));
            }
            case "edge_repeat" -> {
                requireOnly(obj, Set.of("type", "paletteSlotId", "spacingBlocks", "offsetBlocks"),
                        "pattern edge_repeat");
                yield new CompiledDecorationProgram.EdgeRepeatPattern(requiredString(obj, "paletteSlotId"),
                        requiredInt(obj, "spacingBlocks"), requiredInt(obj, "offsetBlocks"));
            }
            case "grid_repeat" -> {
                requireOnly(obj, Set.of("type", "paletteSlotId", "spacingUBlocks", "spacingVBlocks",
                        "offsetUBlocks", "offsetVBlocks"), "pattern grid_repeat");
                yield new CompiledDecorationProgram.GridRepeatPattern(requiredString(obj, "paletteSlotId"),
                        requiredInt(obj, "spacingUBlocks"), requiredInt(obj, "spacingVBlocks"),
                        requiredInt(obj, "offsetUBlocks"), requiredInt(obj, "offsetVBlocks"));
            }
            case "deterministic_scatter" -> {
                requireOnly(obj, Set.of("type", "paletteSlotId", "cellSizeBlocks", "densityPermille"),
                        "pattern deterministic_scatter");
                yield new CompiledDecorationProgram.DeterministicScatterPattern(requiredString(obj, "paletteSlotId"),
                        requiredInt(obj, "cellSizeBlocks"), requiredInt(obj, "densityPermille"));
            }
            default -> throw new IllegalArgumentException("CITY_DECORATION_PATTERN_UNSUPPORTED: " + type);
        };
    }

    CompiledDecorationProgram.ContentPalette parseContentPalette(JsonObject obj) {
        requireOnly(obj, Set.of("slots"), "contentPalette");
        List<PaletteSlot> slots = new ArrayList<>();
        for (JsonElement element : requiredArray(obj, "slots")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("CITY_DECORATION_PALETTE_SLOT_INVALID");
            }
            JsonObject slot = element.getAsJsonObject();
            requireOnly(slot, Set.of("slotId", "phase", "entries", "required"), "palette slot");
            List<ContentEntry> entries = new ArrayList<>();
            for (JsonElement entryElement : requiredArray(slot, "entries")) {
                if (!entryElement.isJsonObject()) {
                    throw new IllegalArgumentException("CITY_DECORATION_CONTENT_ENTRY_INVALID");
                }
                JsonObject entry = entryElement.getAsJsonObject();
                requireOnly(entry, Set.of("contentRef", "weight"), "content entry");
                entries.add(new ContentEntry(requiredString(entry, "contentRef"), requiredDouble(entry, "weight")));
            }
            slots.add(new PaletteSlot(requiredString(slot, "slotId"),
                    CompiledDecorationProgram.Phase.parse(requiredString(slot, "phase")), entries,
                    requiredBoolean(slot, "required")));
        }
        return new CompiledDecorationProgram.ContentPalette(slots);
    }

    CompiledDecorationProgram.TerrainPolicy parseTerrainPolicy(JsonObject obj) {
        return parseTerrainPolicy(obj, false);
    }

    CompiledDecorationProgram.TerrainPolicy parseTerrainPolicy(JsonObject obj, boolean legacySchema) {
        if (legacySchema) {
            requireOnly(obj, Set.of("maxSlopeDelta", "allowWater", "invalidTerrainAction"), "terrainPolicy");
            return new CompiledDecorationProgram.TerrainPolicy(requiredInt(obj, "maxSlopeDelta"),
                    requiredBoolean(obj, "allowWater"),
                    CompiledDecorationProgram.InvalidTerrainAction.parse(requiredString(obj, "invalidTerrainAction")));
        }
        requireOnly(obj, Set.of("maxSlopeDelta", "allowWater", "invalidTerrainAction",
                "maxContinuousDropBlocks", "continuousDropWindowBlocks", "foundationMode",
                "maxFoundationDepthBlocks", "foundationShoulderBlocks"), "terrainPolicy");
        return new CompiledDecorationProgram.TerrainPolicy(requiredInt(obj, "maxSlopeDelta"),
                requiredBoolean(obj, "allowWater"),
                CompiledDecorationProgram.InvalidTerrainAction.parse(requiredString(obj, "invalidTerrainAction")),
                requiredInt(obj, "maxContinuousDropBlocks"),
                requiredInt(obj, "continuousDropWindowBlocks"),
                CompiledDecorationProgram.FoundationMode.parse(requiredString(obj, "foundationMode")),
                requiredInt(obj, "maxFoundationDepthBlocks"),
                requiredInt(obj, "foundationShoulderBlocks"));
    }

    CompiledDecorationProgram.ConflictPolicy parseConflictPolicy(JsonObject obj) {
        requireOnly(obj, Set.of("onConflict", "clearanceBlocks"), "conflictPolicy");
        return new CompiledDecorationProgram.ConflictPolicy(
                CompiledDecorationProgram.ConflictAction.parse(requiredString(obj, "onConflict")),
                requiredInt(obj, "clearanceBlocks"));
    }

    Set<String> referencedPaletteSlots(PatternSpec pattern) {
        Set<String> result = new LinkedHashSet<>();
        if (pattern instanceof CompiledDecorationProgram.UniformFillPattern value) {
            result.add(value.paletteSlotId());
        } else if (pattern instanceof CompiledDecorationProgram.CrossSectionRepeatPattern value) {
            value.bands().forEach(band -> result.add(band.paletteSlotId()));
        } else if (pattern instanceof CompiledDecorationProgram.ParallelRowsPattern value) {
            result.add(value.paletteSlotId());
        } else if (pattern instanceof CompiledDecorationProgram.EdgeRepeatPattern value) {
            result.add(value.paletteSlotId());
        } else if (pattern instanceof CompiledDecorationProgram.GridRepeatPattern value) {
            result.add(value.paletteSlotId());
        } else if (pattern instanceof CompiledDecorationProgram.DeterministicScatterPattern value) {
            result.add(value.paletteSlotId());
        }
        return result;
    }

    private JsonObject targetMaskJson(CompiledDecorationProgram.TargetMask mask) {
        JsonObject obj = new JsonObject();
        obj.addProperty("maskId", mask.maskId());
        JsonArray members = new JsonArray();
        mask.memberBounds().forEach(bounds -> members.add(boundsJson(bounds)));
        obj.add("memberBounds", members);
        return obj;
    }

    private JsonObject coordinateFrameJson(CompiledDecorationProgram.CoordinateFrame frame) {
        JsonObject obj = new JsonObject();
        obj.add("origin", pointJson(frame.origin()));
        obj.add("axisU", vectorJson(frame.axisU()));
        obj.add("axisV", vectorJson(frame.axisV()));
        return obj;
    }

    JsonObject shapeJson(ShapeSpec shape) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", shape.type());
        if (shape instanceof CompiledDecorationProgram.RectangleShape value) {
            obj.addProperty("minU", value.minU());
            obj.addProperty("minV", value.minV());
            obj.addProperty("maxU", value.maxU());
            obj.addProperty("maxV", value.maxV());
        } else if (shape instanceof CompiledDecorationProgram.EllipseShape value) {
            obj.addProperty("centerU", value.centerU());
            obj.addProperty("centerV", value.centerV());
            obj.addProperty("radiusU", value.radiusU());
            obj.addProperty("radiusV", value.radiusV());
        } else if (shape instanceof CompiledDecorationProgram.RingShape value) {
            obj.addProperty("centerU", value.centerU());
            obj.addProperty("centerV", value.centerV());
            obj.addProperty("innerRadiusU", value.innerRadiusU());
            obj.addProperty("innerRadiusV", value.innerRadiusV());
            obj.addProperty("outerRadiusU", value.outerRadiusU());
            obj.addProperty("outerRadiusV", value.outerRadiusV());
        } else if (shape instanceof CompiledDecorationProgram.PolygonShape value) {
            JsonArray vertices = new JsonArray();
            value.vertices().forEach(vertex -> {
                JsonObject point = new JsonObject();
                point.addProperty("u", vertex.u());
                point.addProperty("v", vertex.v());
                vertices.add(point);
            });
            obj.add("vertices", vertices);
        }
        return obj;
    }

    JsonObject patternJson(PatternSpec pattern) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", pattern.type());
        if (pattern instanceof CompiledDecorationProgram.UniformFillPattern value) {
            obj.addProperty("paletteSlotId", value.paletteSlotId());
        } else if (pattern instanceof CompiledDecorationProgram.CrossSectionRepeatPattern value) {
            obj.addProperty("axis", value.axis().serializedName());
            obj.addProperty("offsetBlocks", value.offsetBlocks());
            JsonArray bands = new JsonArray();
            value.bands().forEach(band -> {
                JsonObject bandJson = new JsonObject();
                bandJson.addProperty("paletteSlotId", band.paletteSlotId());
                bandJson.addProperty("widthBlocks", band.widthBlocks());
                bands.add(bandJson);
            });
            obj.add("bands", bands);
        } else if (pattern instanceof CompiledDecorationProgram.ParallelRowsPattern value) {
            obj.addProperty("axis", value.axis().serializedName());
            obj.addProperty("paletteSlotId", value.paletteSlotId());
            obj.addProperty("rowWidthBlocks", value.rowWidthBlocks());
            obj.addProperty("spacingBlocks", value.spacingBlocks());
            obj.addProperty("offsetBlocks", value.offsetBlocks());
        } else if (pattern instanceof CompiledDecorationProgram.EdgeRepeatPattern value) {
            obj.addProperty("paletteSlotId", value.paletteSlotId());
            obj.addProperty("spacingBlocks", value.spacingBlocks());
            obj.addProperty("offsetBlocks", value.offsetBlocks());
        } else if (pattern instanceof CompiledDecorationProgram.GridRepeatPattern value) {
            obj.addProperty("paletteSlotId", value.paletteSlotId());
            obj.addProperty("spacingUBlocks", value.spacingUBlocks());
            obj.addProperty("spacingVBlocks", value.spacingVBlocks());
            obj.addProperty("offsetUBlocks", value.offsetUBlocks());
            obj.addProperty("offsetVBlocks", value.offsetVBlocks());
        } else if (pattern instanceof CompiledDecorationProgram.DeterministicScatterPattern value) {
            obj.addProperty("paletteSlotId", value.paletteSlotId());
            obj.addProperty("cellSizeBlocks", value.cellSizeBlocks());
            obj.addProperty("densityPermille", value.densityPermille());
        }
        return obj;
    }

    JsonObject contentPaletteJson(CompiledDecorationProgram.ContentPalette palette) {
        JsonObject obj = new JsonObject();
        JsonArray slots = new JsonArray();
        palette.slots().forEach(slot -> {
            JsonObject slotJson = new JsonObject();
            slotJson.addProperty("slotId", slot.slotId());
            slotJson.addProperty("phase", slot.phase().serializedName());
            slotJson.addProperty("required", slot.required());
            JsonArray entries = new JsonArray();
            slot.entries().forEach(entry -> {
                JsonObject entryJson = new JsonObject();
                entryJson.addProperty("contentRef", entry.contentRef());
                entryJson.addProperty("weight", entry.weight());
                entries.add(entryJson);
            });
            slotJson.add("entries", entries);
            slots.add(slotJson);
        });
        obj.add("slots", slots);
        return obj;
    }

    private BlockBounds parseBounds(JsonObject obj) {
        requireOnly(obj, Set.of("minX", "minZ", "maxX", "maxZ"), "target mask member bounds");
        return new BlockBounds(requiredInt(obj, "minX"), requiredInt(obj, "minZ"),
                requiredInt(obj, "maxX"), requiredInt(obj, "maxZ"));
    }

    private BlockPoint parsePoint(JsonObject obj) {
        requireOnly(obj, Set.of("x", "z"), "coordinate frame origin");
        return new BlockPoint(requiredInt(obj, "x"), requiredInt(obj, "z"));
    }

    private CompiledDecorationProgram.Vector2 parseVector(JsonObject obj, String context) {
        requireOnly(obj, Set.of("x", "z"), context);
        return new CompiledDecorationProgram.Vector2(requiredInt(obj, "x"), requiredInt(obj, "z"));
    }

    private JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private JsonObject pointJson(BlockPoint point) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", point.x());
        obj.addProperty("z", point.z());
        return obj;
    }

    private JsonObject vectorJson(CompiledDecorationProgram.Vector2 vector) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", vector.x());
        obj.addProperty("z", vector.z());
        return obj;
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

    private double requiredDouble(JsonObject obj, String key) {
        if (!hasPrimitive(obj, key) || !obj.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_FIELD_REQUIRED: " + key);
        }
        return obj.get(key).getAsDouble();
    }

    private boolean requiredBoolean(JsonObject obj, String key) {
        if (!hasPrimitive(obj, key) || !obj.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException("CITY_DECORATION_PROGRAM_FIELD_REQUIRED: " + key);
        }
        return obj.get(key).getAsBoolean();
    }

    private boolean hasPrimitive(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive();
    }
}
