package com.rinsing.geomantia.systems.city.application.dressing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.util.ArrayList;
import java.util.List;

/** Resolves DecorationProgram targets from a compiled block-level LandUseAreaPlan. */
public final class LandUseAreaDecorationProgramContextResolver
        implements ResolvedDecorationProgramContext.Resolver {
    public static final String PLAN_SCHEMA = "city_land_use_area_plan.v0.1";

    private final JsonObject areaPlan;
    private final List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles;

    public LandUseAreaDecorationProgramContextResolver(JsonObject areaPlan) {
        this(areaPlan, List.of());
    }

    public LandUseAreaDecorationProgramContextResolver(
            JsonObject areaPlan,
            List<CompiledDecorationProgramPlan.HardObstacle> hardObstacles) {
        if (areaPlan == null || !PLAN_SCHEMA.equals(stringValue(areaPlan, "schemaVersion"))) {
            throw new IllegalArgumentException("CITY_DECORATION_LAND_USE_PLAN_SCHEMA_UNSUPPORTED");
        }
        this.areaPlan = areaPlan.deepCopy();
        this.hardObstacles = hardObstacles == null ? List.of() : List.copyOf(hardObstacles);
    }

    @Override
    public ResolvedDecorationProgramContext resolve(DecorationProgramIntent intent) {
        DecorationProgramIntent.TargetArea target = intent.targetArea();
        if (!"land_use_area".equals(target.sourceType())) {
            throw new IllegalArgumentException("CITY_DECORATION_TARGET_SOURCE_UNSUPPORTED: "
                    + target.sourceType());
        }
        JsonObject area = findArea(target.ref());
        List<BlockBounds> rows = memberRows(area);
        if (target.insetBlocks() > 0 || !hardObstacles.isEmpty()) {
            rows = erodeSubtract(rows, bounds(rows), target.insetBlocks());
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CITY_DECORATION_TARGET_AREA_EMPTY: " + target.ref());
        }
        CompiledDecorationProgram.TargetMask mask =
                new CompiledDecorationProgram.TargetMask("land_use_area:" + target.ref(), rows);
        return new ResolvedDecorationProgramContext(mask, resolveFrame(intent.coordinateFrame(), mask));
    }

    private JsonObject findArea(String areaId) {
        for (JsonElement elem : array(areaPlan, "areas")) {
            if (elem.isJsonObject() && areaId.equals(stringValue(elem.getAsJsonObject(), "areaId"))) {
                return elem.getAsJsonObject();
            }
        }
        throw new IllegalArgumentException("CITY_DECORATION_TARGET_LAND_USE_AREA_UNKNOWN: " + areaId);
    }

    private List<BlockBounds> memberRows(JsonObject area) {
        List<BlockBounds> rows = new ArrayList<>();
        for (JsonElement elem : array(area, "memberSpans")) {
            if (!elem.isJsonObject()) {
                throw new IllegalArgumentException("CITY_DECORATION_LAND_USE_SPAN_INVALID");
            }
            JsonObject span = elem.getAsJsonObject();
            int z = requiredInt(span, "z");
            int minX = requiredInt(span, "minX");
            int maxX = requiredInt(span, "maxX");
            if (maxX < minX) {
                throw new IllegalArgumentException("CITY_DECORATION_LAND_USE_SPAN_INVALID");
            }
            rows.add(new BlockBounds(minX, z, maxX, z));
        }
        return List.copyOf(rows);
    }

    private List<BlockBounds> erodeSubtract(List<BlockBounds> source, BlockBounds scanBounds, int inset) {
        List<BlockBounds> result = new ArrayList<>();
        for (int z = scanBounds.minZ(); z <= scanBounds.maxZ(); z++) {
            int runStart = Integer.MIN_VALUE;
            for (int x = scanBounds.minX(); x <= scanBounds.maxX(); x++) {
                boolean accepted = hasInsetSupport(source, x, z, inset) && !isHardBlocked(x, z);
                if (accepted && runStart == Integer.MIN_VALUE) {
                    runStart = x;
                } else if (!accepted && runStart != Integer.MIN_VALUE) {
                    result.add(new BlockBounds(runStart, z, x - 1, z));
                    runStart = Integer.MIN_VALUE;
                }
            }
            if (runStart != Integer.MIN_VALUE) {
                result.add(new BlockBounds(runStart, z, scanBounds.maxX(), z));
            }
        }
        return List.copyOf(result);
    }

    private boolean hasInsetSupport(List<BlockBounds> source, int x, int z, int inset) {
        for (int dz = -inset; dz <= inset; dz++) {
            for (int dx = -inset; dx <= inset; dx++) {
                if (!contains(source, x + dx, z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean contains(List<BlockBounds> rows, int x, int z) {
        return rows.stream().anyMatch(row -> row.contains(x, z));
    }

    private boolean isHardBlocked(int x, int z) {
        return hardObstacles.stream().anyMatch(obstacle -> obstacle.blockBounds().contains(x, z));
    }

    private CompiledDecorationProgram.CoordinateFrame resolveFrame(
            DecorationProgramIntent.CoordinateFrameIntent intent,
            CompiledDecorationProgram.TargetMask mask) {
        if (!"target_centroid".equals(intent.originMode())) {
            throw new IllegalArgumentException("CITY_DECORATION_ORIGIN_MODE_UNSUPPORTED: " + intent.originMode());
        }
        CompiledDecorationProgram.Vector2 axisU;
        CompiledDecorationProgram.Vector2 axisV;
        switch (intent.orientationMode()) {
            case "world_x" -> {
                axisU = new CompiledDecorationProgram.Vector2(1, 0);
                axisV = new CompiledDecorationProgram.Vector2(0, 1);
            }
            case "world_z" -> {
                axisU = new CompiledDecorationProgram.Vector2(0, 1);
                axisV = new CompiledDecorationProgram.Vector2(-1, 0);
            }
            case "area_long_axis", "target_long_axis" -> {
                if (mask.bounds().widthBlocks() >= mask.bounds().heightBlocks()) {
                    axisU = new CompiledDecorationProgram.Vector2(1, 0);
                    axisV = new CompiledDecorationProgram.Vector2(0, 1);
                } else {
                    axisU = new CompiledDecorationProgram.Vector2(0, 1);
                    axisV = new CompiledDecorationProgram.Vector2(-1, 0);
                }
            }
            default -> throw new IllegalArgumentException(
                    "CITY_DECORATION_ORIENTATION_MODE_UNSUPPORTED: " + intent.orientationMode());
        }
        for (int turn = 0; turn < intent.quarterTurns(); turn++) {
            CompiledDecorationProgram.Vector2 oldU = axisU;
            axisU = axisV;
            axisV = new CompiledDecorationProgram.Vector2(-oldU.x(), -oldU.z());
        }
        BlockPoint centroid = centroid(mask.memberBounds());
        BlockPoint origin = new BlockPoint(
                centroid.x() + axisU.x() * intent.offsetUBlocks() + axisV.x() * intent.offsetVBlocks(),
                centroid.z() + axisU.z() * intent.offsetUBlocks() + axisV.z() * intent.offsetVBlocks());
        return new CompiledDecorationProgram.CoordinateFrame(origin, axisU, axisV);
    }

    private static BlockPoint centroid(List<BlockBounds> members) {
        long count = 0L;
        long sumXTimesTwo = 0L;
        long sumZTimesTwo = 0L;
        for (BlockBounds member : members) {
            long width = member.widthBlocks();
            long height = member.heightBlocks();
            long cells = width * height;
            count += cells;
            sumXTimesTwo += height * width * ((long) member.minX() + member.maxX());
            sumZTimesTwo += width * height * ((long) member.minZ() + member.maxZ());
        }
        return new BlockPoint((int) Math.round(sumXTimesTwo / (2.0 * count)),
                (int) Math.round(sumZTimesTwo / (2.0 * count)));
    }

    private static BlockBounds bounds(List<BlockBounds> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CITY_DECORATION_TARGET_AREA_EMPTY");
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockBounds row : rows) {
            minX = Math.min(minX, row.minX());
            minZ = Math.min(minZ, row.minZ());
            maxX = Math.max(maxX, row.maxX());
            maxZ = Math.max(maxZ, row.maxZ());
        }
        return new BlockBounds(minX, minZ, maxX, maxZ);
    }

    private static com.google.gson.JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray()
                ? obj.getAsJsonArray(key) : new com.google.gson.JsonArray();
    }

    private static String stringValue(JsonObject obj, String key) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : "";
    }

    private static int requiredInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()
                || !obj.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("CITY_DECORATION_LAND_USE_SPAN_INVALID");
        }
        return obj.get(key).getAsInt();
    }
}
