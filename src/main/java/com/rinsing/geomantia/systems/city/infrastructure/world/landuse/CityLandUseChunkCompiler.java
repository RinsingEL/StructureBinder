package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.application.landuse.LandUseAreaPlanCodec;
import com.rinsing.geomantia.systems.city.domain.landuse.LandUseAreaPlan;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure block-to-owner compiler. It never samples terrain and never writes a level. */
public final class CityLandUseChunkCompiler {
    public static final String RESULT_SCHEMA = "city_land_use_chunk_fragment.v0.1";
    public static final String PLAN_SCHEMA = "city_land_use_area_plan.v0.1";

    private final MaterialPalette palette;
    private final LandUseAreaPlanCodec codec = new LandUseAreaPlanCodec();

    public CityLandUseChunkCompiler() {
        this(MaterialPalette.defaults());
    }

    public CityLandUseChunkCompiler(MaterialPalette palette) {
        this.palette = Objects.requireNonNull(palette, "palette");
    }

    /**
     * JSON is deliberately accepted at this infrastructure boundary. The application codec remains
     * authoritative and the registry validates its plan hash before this method is reached.
     */
    public ChunkFragment compile(JsonObject plan, int chunkX, int chunkZ) {
        return compile(codec.fromJson(Objects.requireNonNull(plan, "plan")), chunkX, chunkZ);
    }

    public ChunkFragment compile(LandUseAreaPlan plan, int chunkX, int chunkZ) {
        Objects.requireNonNull(plan, "plan");
        if (plan.planHash().isBlank() || !plan.planHash().equals(codec.computePlanHash(plan))) {
            throw new IllegalArgumentException("LAND_USE_PLAN_HASH_MISMATCH");
        }
        int minChunkX = chunkX * 16;
        int minChunkZ = chunkZ * 16;
        int maxChunkX = minChunkX + 15;
        int maxChunkZ = minChunkZ + 15;
        Set<BlockCell> corridorExclusions = new HashSet<>();
        for (LandUseAreaPlan.CorridorExclusion exclusion : plan.corridorExclusions()) {
            addBoundsClipped(corridorExclusions, exclusion.blockBounds(),
                    minChunkX, minChunkZ, maxChunkX, maxChunkZ);
        }
        Map<BlockCell, SurfaceOperation> surfaces = new HashMap<>();
        Map<BlockCell, BoundaryOperation> boundaries = new HashMap<>();
        int relevantCellCount = 0;
        int footprintExcluded = 0;
        int corridorExcluded = 0;
        int gateExcluded = 0;

        List<LandUseAreaPlan.Area> stableAreas = new ArrayList<>(plan.areas());
        stableAreas.sort(Comparator.comparing(LandUseAreaPlan.Area::areaId));

        for (LandUseAreaPlan.Area area : stableAreas) {
            String areaId = area.areaId();
            String landUseType = area.landUseType();
            String surfacePolicy = area.surfacePolicy().name();
            String boundaryPolicy = area.boundaryPolicy().name();
            // Agriculture interiors are owned by Decoration prefabs, not LandUse column replacement.
            String surfaceBlock = area.surfacePolicy() == com.rinsing.geomantia.systems.city.domain.landuse.SurfacePolicy.CULTIVATE
                    ? null : palette.surfaceMaterial(surfacePolicy);
            String boundaryBlock = palette.boundaryMaterial(boundaryPolicy);
            Set<BlockCell> footprints = new HashSet<>();
            area.structureFootprintExclusions().forEach(bounds -> addBoundsClipped(footprints, bounds,
                    minChunkX, minChunkZ, maxChunkX, maxChunkZ));
            Set<BlockCell> gates = new HashSet<>();
            area.gateSlots().forEach(gate -> gates.add(cell(gate.block())));

            for (LandUseAreaPlan.ScanlineSpan span : area.memberSpans()) {
                int z = span.z();
                int minX = span.minX();
                int maxX = span.maxX();
                if (z < minChunkZ || z > maxChunkZ || maxX < minChunkX || minX > maxChunkX) {
                    continue;
                }
                for (int x = Math.max(minX, minChunkX); x <= Math.min(maxX, maxChunkX); x++) {
                    BlockCell cell = new BlockCell(x, z);
                    relevantCellCount++;
                    if (footprints.contains(cell)) {
                        footprintExcluded++;
                    } else if (corridorExclusions.contains(cell)) {
                        corridorExcluded++;
                    } else if (gates.contains(cell)) {
                        gateExcluded++;
                    } else if (surfaceBlock != null) {
                        surfaces.putIfAbsent(cell,
                                new SurfaceOperation(areaId, landUseType, x, z, surfaceBlock));
                    }
                }
            }

            if (boundaryBlock == null) {
                continue;
            }
            for (LandUseAreaPlan.BoundaryLoop loop : area.boundaryLoops()) {
                for (BlockPoint point : loop.points()) {
                    BlockCell cell = cell(point);
                    if (!insideChunk(cell, minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
                        continue;
                    }
                    relevantCellCount++;
                    if (footprints.contains(cell)) {
                        footprintExcluded++;
                    } else if (corridorExclusions.contains(cell)) {
                        corridorExcluded++;
                    } else if (gates.contains(cell)) {
                        gateExcluded++;
                    } else {
                        boundaries.putIfAbsent(cell,
                                new BoundaryOperation(areaId, landUseType, cell.x(), cell.z(), boundaryBlock));
                    }
                }
            }
        }

        List<SurfaceOperation> surfaceOperations = new ArrayList<>(surfaces.values());
        surfaceOperations.sort(SurfaceOperation.STABLE_ORDER);
        List<BoundaryOperation> boundaryOperations = new ArrayList<>(boundaries.values());
        boundaryOperations.sort(BoundaryOperation.STABLE_ORDER);
        return new ChunkFragment(RESULT_SCHEMA, plan.cityId(), plan.planHash(), palette.paletteHash(), chunkX, chunkZ,
                relevantCellCount, footprintExcluded, corridorExcluded, gateExcluded,
                List.copyOf(surfaceOperations), List.copyOf(boundaryOperations));
    }

    private static void addBoundsClipped(Set<BlockCell> cells,
                                         BlockBounds bounds,
                                         int clipMinX,
                                         int clipMinZ,
                                         int clipMaxX,
                                         int clipMaxZ) {
        for (int z = Math.max(bounds.minZ(), clipMinZ); z <= Math.min(bounds.maxZ(), clipMaxZ); z++) {
            for (int x = Math.max(bounds.minX(), clipMinX); x <= Math.min(bounds.maxX(), clipMaxX); x++) {
                cells.add(new BlockCell(x, z));
            }
        }
    }

    private static BlockCell cell(BlockPoint point) {
        return new BlockCell(point.x(), point.z());
    }

    private static boolean insideChunk(BlockCell cell, int minX, int minZ, int maxX, int maxZ) {
        return cell.x() >= minX && cell.x() <= maxX && cell.z() >= minZ && cell.z() <= maxZ;
    }

    private static String normalizedPolicy(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public record MaterialPalette(Map<String, String> surfaceMaterials,
                                  Map<String, String> boundaryMaterials,
                                  String paletteHash) {
        public MaterialPalette(Map<String, String> surfaceMaterials,
                               Map<String, String> boundaryMaterials) {
            this(copyNormalized(surfaceMaterials), copyNormalized(boundaryMaterials),
                    computeHash(surfaceMaterials, boundaryMaterials));
        }

        public MaterialPalette {
            surfaceMaterials = Map.copyOf(copyNormalized(
                    Objects.requireNonNull(surfaceMaterials, "surfaceMaterials")));
            boundaryMaterials = Map.copyOf(copyNormalized(
                    Objects.requireNonNull(boundaryMaterials, "boundaryMaterials")));
            Objects.requireNonNull(paletteHash, "paletteHash");
            if (!paletteHash.equals(computeHash(surfaceMaterials, boundaryMaterials))) {
                throw new IllegalArgumentException("CITY_LAND_USE_PALETTE_HASH_MISMATCH");
            }
        }

        public static MaterialPalette defaults() {
            Map<String, String> surfaces = new LinkedHashMap<>();
            surfaces.put("PAVE", "minecraft:stone_bricks");
            Map<String, String> boundaries = new LinkedHashMap<>();
            boundaries.put("FENCE", "minecraft:oak_fence");
            boundaries.put("HEDGE", "minecraft:oak_leaves");
            boundaries.put("LOW_WALL", "minecraft:cobblestone_wall");
            return new MaterialPalette(surfaces, boundaries);
        }

        public String surfaceMaterial(String policy) {
            return surfaceMaterials.get(normalizedPolicy(policy));
        }

        public String boundaryMaterial(String policy) {
            return boundaryMaterials.get(normalizedPolicy(policy));
        }

        public JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("paletteHash", paletteHash);
            root.add("surfaceMaterials", materialsJson(surfaceMaterials));
            root.add("boundaryMaterials", materialsJson(boundaryMaterials));
            return root;
        }

        public static MaterialPalette fromJson(JsonObject root) {
            if (root == null) {
                throw new IllegalArgumentException("CITY_LAND_USE_MATERIAL_PALETTE_REQUIRED");
            }
            return new MaterialPalette(materials(root, "surfaceMaterials"),
                    materials(root, "boundaryMaterials"), string(root, "paletteHash"));
        }

        private static JsonObject materialsJson(Map<String, String> values) {
            JsonObject object = new JsonObject();
            values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> object.addProperty(entry.getKey(), entry.getValue()));
            return object;
        }

        private static Map<String, String> materials(JsonObject root, String key) {
            if (!root.has(key) || !root.get(key).isJsonObject()) {
                throw new IllegalArgumentException("CITY_LAND_USE_MATERIAL_PALETTE_FIELD_REQUIRED: " + key);
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry
                    : root.getAsJsonObject(key).entrySet()) {
                if (!entry.getValue().isJsonPrimitive() || entry.getValue().getAsString().isBlank()) {
                    throw new IllegalArgumentException("CITY_LAND_USE_MATERIAL_PALETTE_FIELD_INVALID: " + key);
                }
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
            return result;
        }

        private static String string(JsonObject root, String key) {
            if (!root.has(key) || !root.get(key).isJsonPrimitive()
                    || root.get(key).getAsString().isBlank()) {
                throw new IllegalArgumentException("CITY_LAND_USE_MATERIAL_PALETTE_FIELD_REQUIRED: " + key);
            }
            return root.get(key).getAsString();
        }

        private static Map<String, String> copyNormalized(Map<String, String> values) {
            Objects.requireNonNull(values, "values");
            Map<String, String> result = new LinkedHashMap<>();
            values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    result.put(normalizedPolicy(entry.getKey()), Objects.requireNonNull(entry.getValue(), "blockId")));
            return result;
        }

        private static String computeHash(Map<String, String> surfaces, Map<String, String> boundaries) {
            StringBuilder canonical = new StringBuilder();
            copyNormalized(surfaces).forEach((key, value) -> canonical.append("s:").append(key)
                    .append('=').append(value).append('\n'));
            copyNormalized(boundaries).forEach((key, value) -> canonical.append("b:").append(key)
                    .append('=').append(value).append('\n'));
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
                return "sha256:" + HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
            }
        }
    }

    public record ChunkFragment(String schemaVersion,
                                String cityId,
                                String planHash,
                                String paletteHash,
                                int chunkX,
                                int chunkZ,
                                int relevantCellCount,
                                int footprintExcludedCount,
                                int corridorExcludedCount,
                                int gateExcludedCount,
                                List<SurfaceOperation> surfaceOperations,
                                List<BoundaryOperation> boundaryOperations) {
        public ChunkFragment {
            if (!RESULT_SCHEMA.equals(schemaVersion)) {
                throw new IllegalArgumentException("CITY_LAND_USE_FRAGMENT_SCHEMA_UNSUPPORTED");
            }
            Objects.requireNonNull(cityId, "cityId");
            Objects.requireNonNull(planHash, "planHash");
            Objects.requireNonNull(paletteHash, "paletteHash");
            surfaceOperations = List.copyOf(surfaceOperations);
            boundaryOperations = List.copyOf(boundaryOperations);
        }

        public boolean hasRelevantCells() {
            return relevantCellCount > 0;
        }
    }

    public record SurfaceOperation(String areaId,
                                   String landUseType,
                                   int x,
                                   int z,
                                   String blockId) {
        public static final Comparator<SurfaceOperation> STABLE_ORDER =
                Comparator.comparingInt(SurfaceOperation::z)
                        .thenComparingInt(SurfaceOperation::x)
                        .thenComparing(SurfaceOperation::areaId);

        public SurfaceOperation {
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(landUseType, "landUseType");
            Objects.requireNonNull(blockId, "blockId");
        }
    }

    public record BoundaryOperation(String areaId,
                                    String landUseType,
                                    int x,
                                    int z,
                                    String blockId) {
        public static final Comparator<BoundaryOperation> STABLE_ORDER =
                Comparator.comparingInt(BoundaryOperation::z)
                        .thenComparingInt(BoundaryOperation::x)
                        .thenComparing(BoundaryOperation::areaId);

        public BoundaryOperation {
            Objects.requireNonNull(areaId, "areaId");
            Objects.requireNonNull(landUseType, "landUseType");
            Objects.requireNonNull(blockId, "blockId");
        }
    }

    private record BlockCell(int x, int z) {
    }
}
