package com.user.terra_script.world.city;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.user.terra_script.config.ForbiddenZoneConfig;
import com.user.terra_script.world.city.district.District;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

public class CityStage1Processor {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int ROUGHNESS_RADIUS = 2;
    private static final double SLOPE_CLIFF_THRESHOLD = 3.0;

    public static class Stage1Result {
        public String cityId;
        public int originX;
        public int originZ;
        public int width;
        public int height;
        public transient int[][] heightMap;
        public transient double[][] slopeMap;
        public transient double[][] roughnessMap;
        public String dataFile;
        public transient List<ForbiddenBlock> forbidden = new ArrayList<>();
        public transient List<List<BlockCoord>> buildableGroups = new ArrayList<>();
        public List<BuildableStats> buildableStats = new ArrayList<>();
        public Map<Integer, String> districtZones = new HashMap<>();
    }

    public static class ForbiddenBlock {
        public int x;
        public int z;
        public String reason;
    }

    public static class BlockCoord {
        public int x;
        public int z;
    }

    public static class BuildableStats {
        public int districtId;
        public int totalArea;
        public int buildableArea;
        public int forbiddenArea;
        public double buildablePercent;
        public double forbiddenPercent;
        public int area;
        public double avgHeight;
        public double avgRoughness;
    }

    public static Stage1Result compute(ServerLevel level, CityInstance city) {
        Stage1Result result = new Stage1Result();
        result.cityId = city.id;

        Bounds bounds = computeBounds(city.claimedChunks.keySet());
        result.originX = bounds.minX;
        result.originZ = bounds.minZ;
        result.width = bounds.width;
        result.height = bounds.height;

        boolean[][] cityMask = buildCityMask(city, bounds);
        result.heightMap = new int[bounds.width][bounds.height];

        for (int x = 0; x < bounds.width; x++) {
            for (int z = 0; z < bounds.height; z++) {
                if (!cityMask[x][z]) {
                    result.heightMap[x][z] = 0;
                    continue;
                }
                int worldX = bounds.minX + x;
                int worldZ = bounds.minZ + z;
                result.heightMap[x][z] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
            }
        }

        result.slopeMap = computeSlope(result.heightMap, cityMask);
        result.roughnessMap = computeRoughness(result.heightMap, cityMask);

        Map<Long, String> forbiddenChunks = ForbiddenZoneConfig.toChunkReasonMap(ForbiddenZoneConfig.load());
        Set<Long> forbiddenBlocks = new HashSet<>();
        Set<Long> buildableBlocks = new HashSet<>();

        for (int x = 0; x < bounds.width; x++) {
            for (int z = 0; z < bounds.height; z++) {
                if (!cityMask[x][z]) continue;
                int worldX = bounds.minX + x;
                int worldZ = bounds.minZ + z;
                long blockKey = packBlock(worldX, worldZ);

                String reason = null;
                if (isLiquid(level, worldX, worldZ, result.heightMap[x][z])) {
                    reason = "WATER";
                } else if (result.slopeMap[x][z] > SLOPE_CLIFF_THRESHOLD) {
                    reason = "CLIFF";
                } else {
                    long chunkKey = ChunkPos.asLong(worldX >> 4, worldZ >> 4);
                    String cfgReason = forbiddenChunks.get(chunkKey);
                    if (cfgReason != null) {
                        reason = cfgReason;
                    }
                }

                if (reason != null) {
                    forbiddenBlocks.add(blockKey);
                    ForbiddenBlock fb = new ForbiddenBlock();
                    fb.x = worldX;
                    fb.z = worldZ;
                    fb.reason = reason;
                    result.forbidden.add(fb);
                } else {
                    buildableBlocks.add(blockKey);
                }
            }
        }

        result.buildableGroups = groupBuildable(buildableBlocks);
        result.buildableStats = computeDistrictBuildableStats(
                city,
                buildableBlocks,
                forbiddenBlocks,
                cityMask,
                result.heightMap,
                result.roughnessMap,
                bounds
        );
        result.districtZones = buildDistrictZones(city);
        return result;
    }

    public static File save(Stage1Result result) throws Exception {
        File dataFile = CityStage1BinaryIO.save(result);
        result.dataFile = dataFile.getName();
        File file = FMLPaths.GAMEDIR.get()
                .resolve("terra_script_city_stage1_" + result.cityId + ".json")
                .toFile();
        Files.writeString(file.toPath(), GSON.toJson(result));
        return file;
    }

    private static boolean[][] buildCityMask(CityInstance city, Bounds bounds) {
        boolean[][] mask = new boolean[bounds.width][bounds.height];
        for (long chunkKey : city.claimedChunks.keySet()) {
            int cx = ChunkPos.getX(chunkKey);
            int cz = ChunkPos.getZ(chunkKey);
            int baseX = (cx * 16) - bounds.minX;
            int baseZ = (cz * 16) - bounds.minZ;
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    if (x >= 0 && x < bounds.width && z >= 0 && z < bounds.height) {
                        mask[x][z] = true;
                    }
                }
            }
        }
        return mask;
    }

    private static double[][] computeSlope(int[][] height, boolean[][] mask) {
        int w = height.length;
        int h = height[0].length;
        double[][] slope = new double[w][h];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                if (!mask[x][z]) continue;
                int h0 = height[x][z];
                int hx = (x + 1 < w) ? height[x + 1][z] : h0;
                int hz = (z + 1 < h) ? height[x][z + 1] : h0;
                int dx = Math.abs(h0 - hx);
                int dz = Math.abs(h0 - hz);
                slope[x][z] = Math.sqrt((dx * dx) + (dz * dz));
            }
        }
        return slope;
    }

    private static double[][] computeRoughness(int[][] height, boolean[][] mask) {
        int w = height.length;
        int h = height[0].length;
        double[][] rough = new double[w][h];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                if (!mask[x][z]) continue;
                rough[x][z] = Math.sqrt(localVariance(height, mask, x, z, ROUGHNESS_RADIUS));
            }
        }
        return rough;
    }

    private static double localVariance(int[][] height, boolean[][] mask, int cx, int cz, int r) {
        int w = height.length;
        int h = height[0].length;
        int count = 0;
        double sum = 0;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (x < 0 || x >= w || z < 0 || z >= h) continue;
                if (!mask[x][z]) continue;
                sum += height[x][z];
                count++;
            }
        }
        if (count == 0) return 0;
        double avg = sum / count;
        double var = 0;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (x < 0 || x >= w || z < 0 || z >= h) continue;
                if (!mask[x][z]) continue;
                double diff = height[x][z] - avg;
                var += diff * diff;
            }
        }
        return var / count;
    }

    private static boolean isLiquid(ServerLevel level, int worldX, int worldZ, int surfaceY) {
        BlockPos pos = new BlockPos(worldX, surfaceY - 1, worldZ);
        return !level.getFluidState(pos).isEmpty();
    }

    private static List<List<BlockCoord>> groupBuildable(Set<Long> buildable) {
        List<List<BlockCoord>> groups = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        int[][] dirs = {{1,0}, {-1,0}, {0,1}, {0,-1}};

        for (long key : buildable) {
            if (visited.contains(key)) continue;
            List<BlockCoord> group = new ArrayList<>();
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(key);
            visited.add(key);

            while (!queue.isEmpty()) {
                long curr = queue.poll();
                int x = (int) (curr >> 32);
                int z = (int) curr;
                BlockCoord bc = new BlockCoord();
                bc.x = x;
                bc.z = z;
                group.add(bc);

                for (int[] d : dirs) {
                    long nk = packBlock(x + d[0], z + d[1]);
                    if (buildable.contains(nk) && !visited.contains(nk)) {
                        visited.add(nk);
                        queue.add(nk);
                    }
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private static List<BuildableStats> computeDistrictBuildableStats(
            CityInstance city,
            Set<Long> buildableBlocks,
            Set<Long> forbiddenBlocks,
            boolean[][] cityMask,
            int[][] heightMap,
            double[][] roughnessMap,
            Bounds bounds
    ) {
        List<BuildableStats> stats = new ArrayList<>();
        if (city.districts == null || city.districts.isEmpty()) {
            return stats;
        }
        for (District district : city.districts) {
            BuildableStats s = new BuildableStats();
            s.districtId = district.id;
            long sumH = 0;
            double sumR = 0.0;

            List<double[]> poly = district.polygonVertices;
            if (poly != null && !poly.isEmpty()) {
                Bounds polyBounds = computePolygonBounds(poly);
                int minX = Math.max(bounds.minX, polyBounds.minX);
                int minZ = Math.max(bounds.minZ, polyBounds.minZ);
                int maxX = Math.min(bounds.minX + bounds.width - 1, polyBounds.minX + polyBounds.width - 1);
                int maxZ = Math.min(bounds.minZ + bounds.height - 1, polyBounds.minZ + polyBounds.height - 1);

                for (int worldX = minX; worldX <= maxX; worldX++) {
                    for (int worldZ = minZ; worldZ <= maxZ; worldZ++) {
                        int ix = worldX - bounds.minX;
                        int iz = worldZ - bounds.minZ;
                        if (ix < 0 || iz < 0 || ix >= bounds.width || iz >= bounds.height) continue;
                        if (!cityMask[ix][iz]) continue;
                        if (!pointInPolygon(worldX + 0.5, worldZ + 0.5, poly)) continue;
                        s.totalArea++;
                        long blockKey = packBlock(worldX, worldZ);
                        if (buildableBlocks.contains(blockKey)) {
                            s.buildableArea++;
                            sumH += heightMap[ix][iz];
                            sumR += roughnessMap[ix][iz];
                            s.area++;
                        } else if (forbiddenBlocks.contains(blockKey)) {
                            s.forbiddenArea++;
                        }
                    }
                }
            } else if (district.memberChunks != null && !district.memberChunks.isEmpty()) {
                for (long chunkKey : district.memberChunks) {
                    int baseX = ChunkPos.getX(chunkKey) * 16;
                    int baseZ = ChunkPos.getZ(chunkKey) * 16;
                    for (int dx = 0; dx < 16; dx++) {
                        for (int dz = 0; dz < 16; dz++) {
                            int worldX = baseX + dx;
                            int worldZ = baseZ + dz;
                            int ix = worldX - bounds.minX;
                            int iz = worldZ - bounds.minZ;
                            if (ix < 0 || iz < 0 || ix >= bounds.width || iz >= bounds.height) continue;
                            if (!cityMask[ix][iz]) continue;
                            s.totalArea++;
                            long blockKey = packBlock(worldX, worldZ);
                            if (buildableBlocks.contains(blockKey)) {
                                s.buildableArea++;
                                sumH += heightMap[ix][iz];
                                sumR += roughnessMap[ix][iz];
                                s.area++;
                            } else if (forbiddenBlocks.contains(blockKey)) {
                                s.forbiddenArea++;
                            }
                        }
                    }
                }
            }
            if (s.area > 0) {
                s.avgHeight = (double) sumH / s.area;
                s.avgRoughness = sumR / s.area;
            }
            if (s.totalArea > 0) {
                s.buildablePercent = (double) s.buildableArea / s.totalArea;
                s.forbiddenPercent = (double) s.forbiddenArea / s.totalArea;
            }
            stats.add(s);
        }
        return stats;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static Map<Integer, String> buildDistrictZones(CityInstance city) {
        Map<Integer, String> zones = new HashMap<>();
        if (city.districts == null) return zones;
        for (District district : city.districts) {
            if (district == null) continue;
            zones.put(district.id, district.zoneType);
        }
        return zones;
    }

    private static Bounds computePolygonBounds(List<double[]> poly) {
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double[] v : poly) {
            if (v == null || v.length < 2) continue;
            minX = Math.min(minX, v[0]);
            minZ = Math.min(minZ, v[1]);
            maxX = Math.max(maxX, v[0]);
            maxZ = Math.max(maxZ, v[1]);
        }
        Bounds b = new Bounds();
        b.minX = (int) Math.floor(minX);
        b.minZ = (int) Math.floor(minZ);
        b.width = (int) Math.max(0, Math.ceil(maxX) - Math.floor(minX) + 1);
        b.height = (int) Math.max(0, Math.ceil(maxZ) - Math.floor(minZ) + 1);
        return b;
    }

    private static boolean pointInPolygon(double x, double z, List<double[]> poly) {
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            double[] vi = poly.get(i);
            double[] vj = poly.get(j);
            if (vi == null || vj == null || vi.length < 2 || vj.length < 2) continue;
            double xi = vi[0], zi = vi[1];
            double xj = vj[0], zj = vj[1];
            boolean intersect = ((zi > z) != (zj > z)) &&
                    (x < (xj - xi) * (z - zi) / (zj - zi + 0.0) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    private static class Bounds {
        int minX;
        int minZ;
        int width;
        int height;
    }

    private static Bounds computeBounds(Set<Long> claimedChunks) {
        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;

        for (long key : claimedChunks) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            minChunkX = Math.min(minChunkX, cx);
            maxChunkX = Math.max(maxChunkX, cx);
            minChunkZ = Math.min(minChunkZ, cz);
            maxChunkZ = Math.max(maxChunkZ, cz);
        }

        Bounds b = new Bounds();
        b.minX = minChunkX * 16;
        b.minZ = minChunkZ * 16;
        b.width = ((maxChunkX - minChunkX) + 1) * 16;
        b.height = ((maxChunkZ - minChunkZ) + 1) * 16;
        return b;
    }
}
