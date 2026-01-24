package com.user.terra_script.util;

import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.world.city.CityConfig;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.CityStage1BinaryIO;
import com.user.terra_script.world.city.district.District;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

public class VoronoiComputer {
    private static final double TURN_PENALTY = 1.5;
    private static final double SHARP_TURN_PENALTY = 2.5;
    private static final double HEIGHT_CHANGE_WEIGHT = 0.6;
    private static final double SLOPE_WEIGHT = 0.4;
    private static final int CLIFF_THRESHOLD = 4;
    private static final int PATH_MARGIN = 32;
    private static final int CHAIKIN_ITERATIONS = 2;
    private static final int MAX_STEP_UP = 1;
    private static final int MAX_STEP_DOWN = 2;
    private static final int MAX_HEIGHT_DELTA_FOR_RAMP = 10;

    public static List<District> computeDistricts(CityInstance city) {
        List<District> districts = new ArrayList<>();
        if (city.claimedChunks.isEmpty()) return districts;

        Random rand = new Random(city.id.hashCode());
        CityConfig.LayerLayout layout = city.getLayerLayout();

        Map<Integer, List<Long>> layerChunks = new HashMap<>();
        for (Map.Entry<Long, CityInstance.LayerAssignment> entry : city.claimedChunks.entrySet()) {
            CityInstance.LayerAssignment assignment = entry.getValue();
            int layerIndex = assignment != null ? assignment.layerIndex : (layout.layers.size() - 1);
            layerChunks.computeIfAbsent(layerIndex, k -> new ArrayList<>()).add(entry.getKey());
        }

        Map<Integer, List<District>> districtsByLayer = new HashMap<>();
        int nextId = 1;

        for (int layerIndex = 0; layerIndex < layout.layers.size(); layerIndex++) {
            List<Long> candidates = layerChunks.get(layerIndex);
            if (candidates == null || candidates.isEmpty()) continue;
            CityConfig.LayerConfig layer = layout.layerAt(layerIndex);
            int targetDistricts = computeTargetDistricts(candidates.size(), layer.density);
            if (targetDistricts <= 0) continue;

            Collections.shuffle(candidates, rand);

            int count = Math.min(targetDistricts, candidates.size());
            for (int i = 0; i < count; i++) {
                long seedChunk = candidates.get(i);

                double cx = (ChunkPos.getX(seedChunk) * 16) + 8;
                double cz = (ChunkPos.getZ(seedChunk) * 16) + 8;

                District d = new District(nextId++, cx, cz);
                d.cityId = city.id;
                d.zoneType = layer.type;
                d.layerIndex = layerIndex;
                d.density = layer.density;

                districts.add(d);
                districtsByLayer.computeIfAbsent(layerIndex, k -> new ArrayList<>()).add(d);
            }
        }

        for (Map.Entry<Long, CityInstance.LayerAssignment> entry : city.claimedChunks.entrySet()) {
            long chunkKey = entry.getKey();
            CityInstance.LayerAssignment assignment = entry.getValue();
            int layerIndex = assignment != null ? assignment.layerIndex : (layout.layers.size() - 1);
            List<District> layerDistricts = districtsByLayer.get(layerIndex);
            if (layerDistricts == null || layerDistricts.isEmpty()) continue;

            int cx = ChunkPos.getX(chunkKey) * 16 + 8;
            int cz = ChunkPos.getZ(chunkKey) * 16 + 8;

            District nearest = null;
            double minDst = Double.MAX_VALUE;

            for (District d : layerDistricts) {
                double distSq = Math.pow(cx - d.centerX, 2) + Math.pow(cz - d.centerZ, 2);
                if (distSq < minDst) {
                    minDst = distSq;
                    nearest = d;
                }
            }

            if (nearest != null) {
                nearest.memberChunks.add(chunkKey);
            }
        }

        // 5. 重新计算中心�?(Lloyd Relaxation step 1) - 可选，让种子移动到几何中心

        return districts;
    }

    private static int computeTargetDistricts(int chunkCount, String density) {
        if (chunkCount <= 0) return 0;
        String normalized = CityConfig.normalizeDensity(density);
        if ("1".equals(normalized)) return 1;
        int avgChunks = averageChunksForDensity(normalized);
        avgChunks = Math.max(1, avgChunks);
        int target = (int) Math.round(chunkCount / (double) avgChunks);
        target = Math.max(1, target);
        return Math.min(target, chunkCount);
    }

    private static int averageChunksForDensity(String density) {
        if ("high".equals(density)) return 4;
        if ("low".equals(density)) return 12;
        return 7;
    }

    private static void computeFields(List<District> districts, ScanResultHolder holder) {
        if (holder.lastScanData == null) return;

        // 全局坐标转换参数
        int step = holder.scanStep;
        int radiusBlocks = holder.scanRadiusChunks * 16;
        int minX = -radiusBlocks;
        int minZ = -radiusBlocks;

        for (District d : districts) {
            // 映射到全局网格
            int gx = ((int)d.centerX - minX) / step;
            int gz = ((int)d.centerZ - minZ) / step;

            if (gx >= 0 && gx < holder.lastScanData.length && gz >= 0 && gz < holder.lastScanData[0].length) {
                var p = holder.lastScanData[gx][gz];
                if (p != null) {
                    // 平坦�?(简单用高度近似，或者取周围点方�?
                    // d.avgSlope = ...

                    // 水源距离
                    // 需�?BFS 搜索最近的水，太慢了�?
                    // 快速法：直接看 p.isLand()，如果是 false 则距离为 0�?
                    // 否则�?holder.distanceField (如果我们�?expandOceans 里存了的�?
                    // 或者是简单的：如果本来就�?BUFFER 且靠近海边（biom is beach），设为近水
                }
            }
        }
    }

    // ---------------- block级别拓展 ---------------------
    public static Map<Long, District> buildBlockOwnership(List<District> districts) {
        // key: blockPos packed as long, value: District
        Map<Long, District> blockOwner = new HashMap<>();

        for (District d : districts) {
            for (long chunkKey : d.memberChunks) {
                int chunkX = ChunkPos.getX(chunkKey);
                int chunkZ = ChunkPos.getZ(chunkKey);

                int baseX = chunkX * 16;
                int baseZ = chunkZ * 16;

                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        int bx = baseX + dx;
                        int bz = baseZ + dz;

                        long blockKey = packBlock(bx, bz);
                        blockOwner.put(blockKey, d);
                    }
                }
            }
        }
        return blockOwner;
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    
    public static Set<Long> computeSmoothRoads(List<District> districts) {
        RoadPlan plan = computeSmoothRoadPlan(districts);
        return plan.blocks;
    }

    public static RoadPlan computeSmoothRoadPlan(List<District> districts) {
        RoadPlan plan = new RoadPlan();
        if (districts == null || districts.isEmpty()) return plan;

        Map<Integer, District> byId = new HashMap<>();
        for (District d : districts) {
            byId.put(d.id, d);
        }

        Map<Long, District> blockOwner = buildBlockOwnership(districts);
        Set<Long> edges = computeAdjacencyPairs(blockOwner);
        CityBounds cityBounds = computeCityBounds(districts);
        boolean[][] cityMask = buildCityMask(districts, cityBounds);

        CityStage1BinaryIO.HeightData heightData = null;
        String cityId = districts.get(0).cityId;
        if (cityId != null && !cityId.isBlank()) {
            try {
                heightData = CityStage1BinaryIO.loadHeightData(cityId);
            } catch (Exception ignored) {
                heightData = null;
            }
        }

        int roadRadius = 1;
        for (long edge : edges) {
            int idA = (int) (edge >> 32);
            int idB = (int) edge;
            District a = byId.get(idA);
            District b = byId.get(idB);
            if (a == null || b == null) continue;

            int startX = (int) Math.round(a.centerX);
            int startZ = (int) Math.round(a.centerZ);
            int endX = (int) Math.round(b.centerX);
            int endZ = (int) Math.round(b.centerZ);

            CityBounds pathBounds = computePathBounds(cityBounds, startX, startZ, endX, endZ);
            List<int[]> path = aStarPath(startX, startZ, endX, endZ, pathBounds, cityMask, cityBounds, heightData);
            if (path.isEmpty()) {
                addSmoothLine(plan.blocks, a.centerX, a.centerZ, b.centerX, b.centerZ, roadRadius);
                continue;
            }
            List<double[]> smoothed = chaikinSmooth(path, CHAIKIN_ITERATIONS);
            Integer lastHeight = null;
            long lastKey = 0L;
            for (double[] p : smoothed) {
                int x = (int) Math.round(p[0]);
                int z = (int) Math.round(p[1]);
                if (!isWithinBounds(cityBounds, x, z)) continue;
                if (cityMask != null && !maskAt(cityMask, cityBounds, x, z)) continue;
                Integer clamped = null;
                if (heightData != null) {
                    Integer target = heightAtOrNull(heightData, x, z);
                    if (target != null) {
                        if (lastHeight == null) {
                            clamped = target;
                        } else {
                            int diff = target - lastHeight;
                            if (Math.abs(diff) > MAX_HEIGHT_DELTA_FOR_RAMP) {
                                break;
                            }
                            int minH = lastHeight - MAX_STEP_DOWN;
                            int maxH = lastHeight + MAX_STEP_UP;
                            clamped = Math.max(minH, Math.min(maxH, target));
                        }
                        if (lastHeight != null) {
                            int dh = clamped - lastHeight;
                            if (Math.abs(dh) == 1) {
                                long key = packBlock(x, z);
                                long slabKey = dh > 0 ? key : lastKey;
                                plan.slabBlocks.add(slabKey);
                            }
                        }
                        lastHeight = clamped;
                        lastKey = packBlock(x, z);
                    }
                }
                addDisc(plan.blocks, plan.heights, x, z, roadRadius, clamped);
            }
        }

        return plan;
    }

    private static Set<Long> computeAdjacencyPairs(Map<Long, District> blockOwner) {
        Set<Long> edges = new HashSet<>();
        for (var entry : blockOwner.entrySet()) {
            long key = entry.getKey();
            District self = entry.getValue();

            int x = (int) (key >> 32);
            int z = (int) key;

            addEdge(edges, self, blockOwner.get(packBlock(x + 1, z)));
            addEdge(edges, self, blockOwner.get(packBlock(x - 1, z)));
            addEdge(edges, self, blockOwner.get(packBlock(x, z + 1)));
            addEdge(edges, self, blockOwner.get(packBlock(x, z - 1)));
        }
        return edges;
    }

    private static void addEdge(Set<Long> edges, District a, District b) {
        if (a == null || b == null || a == b) return;
        int idA = a.id;
        int idB = b.id;
        long key = idA < idB ? (((long) idA) << 32) | (idB & 0xffffffffL) : (((long) idB) << 32) | (idA & 0xffffffffL);
        edges.add(key);
    }

    private static void addSmoothLine(Set<Long> roadBlocks, double ax, double az, double bx, double bz, int radius) {
        double dx = bx - ax;
        double dz = bz - az;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.5) return;

        double step = 0.5;
        double ux = dx / length;
        double uz = dz / length;

        for (double t = 0; t <= length; t += step) {
            double x = ax + ux * t;
            double z = az + uz * t;
            int cx = (int) Math.round(x);
            int cz = (int) Math.round(z);
            addDisc(roadBlocks, cx, cz, radius);
        }
    }

    private static void addDisc(Set<Long> roadBlocks, int cx, int cz, int radius) {
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= r2) {
                    roadBlocks.add(packBlock(cx + dx, cz + dz));
                }
            }
        }
    }

    private static void addDisc(Set<Long> roadBlocks, Map<Long, Integer> heights, int cx, int cz, int radius, Integer height) {
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= r2) {
                    long key = packBlock(cx + dx, cz + dz);
                    roadBlocks.add(key);
                    if (heights != null && height != null && !heights.containsKey(key)) {
                        heights.put(key, height);
                    }
                }
            }
        }
    }

    public static Set<Long> computeDistrictBoundaries(
            Map<Long, District> blockOwner
    ) {
        Set<Long> boundaries = new HashSet<>();

        for (var entry : blockOwner.entrySet()) {
            long key = entry.getKey();
            District self = entry.getValue();

            int x = (int) (key >> 32);
            int z = (int) key;

            // 只看水平四邻
            if (isDifferent(blockOwner, self, x + 1, z) ||
                    isDifferent(blockOwner, self, x - 1, z) ||
                    isDifferent(blockOwner, self, x, z + 1) ||
                    isDifferent(blockOwner, self, x, z - 1)) {

                boundaries.add(key);
            }
        }
        return boundaries;
    }

    private static boolean isDifferent(
            Map<Long, District> owner,
            District self,
            int x, int z
    ) {
        long k = packBlock(x, z);
        District other = owner.get(k);
        return other != null && other != self;
    }

    public static Set<Long> expandBoundary(Set<Long> boundary, int radius) {
        Set<Long> expanded = new HashSet<>(boundary);

        for (long key : boundary) {
            int x = (int) (key >> 32);
            int z = (int) key;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    expanded.add(packBlock(x + dx, z + dz));
                }
            }
        }
        return expanded;
    }

    private static CityBounds computeCityBounds(List<District> districts) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (District d : districts) {
            for (long chunkKey : d.memberChunks) {
                int baseX = ChunkPos.getX(chunkKey) * 16;
                int baseZ = ChunkPos.getZ(chunkKey) * 16;
                minX = Math.min(minX, baseX);
                minZ = Math.min(minZ, baseZ);
                maxX = Math.max(maxX, baseX + 15);
                maxZ = Math.max(maxZ, baseZ + 15);
            }
        }

        CityBounds bounds = new CityBounds();
        bounds.minX = minX;
        bounds.minZ = minZ;
        bounds.maxX = maxX;
        bounds.maxZ = maxZ;
        return bounds;
    }

    private static CityBounds computePathBounds(CityBounds cityBounds, int startX, int startZ, int endX, int endZ) {
        CityBounds bounds = new CityBounds();
        bounds.minX = Math.max(cityBounds.minX, Math.min(startX, endX) - PATH_MARGIN);
        bounds.maxX = Math.min(cityBounds.maxX, Math.max(startX, endX) + PATH_MARGIN);
        bounds.minZ = Math.max(cityBounds.minZ, Math.min(startZ, endZ) - PATH_MARGIN);
        bounds.maxZ = Math.min(cityBounds.maxZ, Math.max(startZ, endZ) + PATH_MARGIN);
        return bounds;
    }

    private static boolean[][] buildCityMask(List<District> districts, CityBounds bounds) {
        if (bounds.minX > bounds.maxX || bounds.minZ > bounds.maxZ) return null;
        int width = bounds.maxX - bounds.minX + 1;
        int height = bounds.maxZ - bounds.minZ + 1;
        boolean[][] mask = new boolean[width][height];
        for (District d : districts) {
            for (long chunkKey : d.memberChunks) {
                int baseX = ChunkPos.getX(chunkKey) * 16;
                int baseZ = ChunkPos.getZ(chunkKey) * 16;
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        int x = baseX + dx;
                        int z = baseZ + dz;
                        if (x < bounds.minX || x > bounds.maxX || z < bounds.minZ || z > bounds.maxZ) continue;
                        mask[x - bounds.minX][z - bounds.minZ] = true;
                    }
                }
            }
        }
        return mask;
    }

    private static boolean isWithinBounds(CityBounds bounds, int x, int z) {
        return x >= bounds.minX && x <= bounds.maxX && z >= bounds.minZ && z <= bounds.maxZ;
    }

    private static boolean maskAt(boolean[][] mask, CityBounds bounds, int x, int z) {
        if (mask == null) return true;
        int ix = x - bounds.minX;
        int iz = z - bounds.minZ;
        if (ix < 0 || iz < 0 || ix >= mask.length || iz >= mask[0].length) return false;
        return mask[ix][iz];
    }

    private static List<int[]> aStarPath(
            int startX,
            int startZ,
            int endX,
            int endZ,
            CityBounds bounds,
            boolean[][] cityMask,
            CityBounds maskBounds,
            CityStage1BinaryIO.HeightData heightData
    ) {
        List<int[]> empty = Collections.emptyList();
        if (!isWithinBounds(bounds, startX, startZ) || !isWithinBounds(bounds, endX, endZ)) {
            return empty;
        }

        int width = bounds.maxX - bounds.minX + 1;
        int height = bounds.maxZ - bounds.minZ + 1;
        int dirCount = 9;
        int[] dxs = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dzs = {0, 0, 1, -1, 1, -1, 1, -1};

        int totalStates = width * height * dirCount;
        double[] gScore = new double[totalStates];
        long[] parent = new long[totalStates];
        Arrays.fill(gScore, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1L);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        int startIndex = index(bounds, startX, startZ, 8, height, dirCount);
        gScore[startIndex] = 0.0;
        open.add(new Node(startX, startZ, 8, 0.0, heuristic(startX, startZ, endX, endZ)));

        int bestEndIndex = -1;
        double bestEndScore = Double.POSITIVE_INFINITY;

        while (!open.isEmpty()) {
            Node current = open.poll();
            int currIndex = index(bounds, current.x, current.z, current.dir, height, dirCount);
            if (current.g > gScore[currIndex]) continue;

            if (current.x == endX && current.z == endZ) {
                bestEndIndex = currIndex;
                bestEndScore = current.g;
                break;
            }

            for (int dir = 0; dir < dxs.length; dir++) {
                int nx = current.x + dxs[dir];
                int nz = current.z + dzs[dir];
                if (!isWithinBounds(bounds, nx, nz)) continue;
                if (cityMask != null && !maskAt(cityMask, maskBounds, nx, nz)) continue;

                int h0 = heightAt(heightData, current.x, current.z);
                int h1 = heightAt(heightData, nx, nz);
                int diff = Math.abs(h1 - h0);
                if (diff >= CLIFF_THRESHOLD) continue;

                double stepCost = (dxs[dir] != 0 && dzs[dir] != 0) ? 1.414 : 1.0;
                stepCost += diff * HEIGHT_CHANGE_WEIGHT;
                stepCost += diff * SLOPE_WEIGHT;

                if (current.dir < 8) {
                    int prevDx = dxs[current.dir];
                    int prevDz = dzs[current.dir];
                    if (prevDx != dxs[dir] || prevDz != dzs[dir]) {
                        stepCost += TURN_PENALTY;
                        int dot = prevDx * dxs[dir] + prevDz * dzs[dir];
                        if (dot <= 0) stepCost += SHARP_TURN_PENALTY;
                    }
                }

                double tentativeG = current.g + stepCost;
                int nextIndex = index(bounds, nx, nz, dir, height, dirCount);
                if (tentativeG < gScore[nextIndex]) {
                    gScore[nextIndex] = tentativeG;
                    parent[nextIndex] = currIndex;
                    double f = tentativeG + heuristic(nx, nz, endX, endZ);
                    open.add(new Node(nx, nz, dir, tentativeG, f));
                }
            }
        }

        if (bestEndIndex == -1) {
            for (int dir = 0; dir < dirCount; dir++) {
                int idx = index(bounds, endX, endZ, dir, height, dirCount);
                if (gScore[idx] < bestEndScore) {
                    bestEndScore = gScore[idx];
                    bestEndIndex = idx;
                }
            }
        }

        if (bestEndIndex == -1 || Double.isInfinite(bestEndScore)) return empty;
        List<int[]> path = new ArrayList<>();
        int idx = bestEndIndex;
        while (idx != -1) {
            int[] pos = decode(bounds, idx, height, dirCount);
            path.add(pos);
            idx = (int) parent[idx];
        }
        Collections.reverse(path);
        return path;
    }

    private static double heuristic(int x, int z, int tx, int tz) {
        int dx = tx - x;
        int dz = tz - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int index(CityBounds bounds, int x, int z, int dir, int height, int dirCount) {
        int ix = x - bounds.minX;
        int iz = z - bounds.minZ;
        return (ix * height + iz) * dirCount + dir;
    }

    private static int[] decode(CityBounds bounds, int index, int height, int dirCount) {
        int cell = index / dirCount;
        int ix = cell / height;
        int iz = cell % height;
        return new int[]{bounds.minX + ix, bounds.minZ + iz};
    }

    private static int heightAt(CityStage1BinaryIO.HeightData data, int worldX, int worldZ) {
        if (data == null) return 0;
        int ix = worldX - data.originX;
        int iz = worldZ - data.originZ;
        if (ix < 0 || iz < 0 || ix >= data.width || iz >= data.height) return 0;
        return data.heightMap[ix][iz];
    }

    private static Integer heightAtOrNull(CityStage1BinaryIO.HeightData data, int worldX, int worldZ) {
        if (data == null) return null;
        int ix = worldX - data.originX;
        int iz = worldZ - data.originZ;
        if (ix < 0 || iz < 0 || ix >= data.width || iz >= data.height) return null;
        return data.heightMap[ix][iz];
    }

    private static List<double[]> chaikinSmooth(List<int[]> path, int iterations) {
        List<double[]> points = new ArrayList<>();
        for (int[] p : path) {
            points.add(new double[]{p[0], p[1]});
        }
        for (int i = 0; i < iterations; i++) {
            if (points.size() < 3) break;
            List<double[]> next = new ArrayList<>();
            next.add(points.get(0));
            for (int j = 0; j < points.size() - 1; j++) {
                double[] p0 = points.get(j);
                double[] p1 = points.get(j + 1);
                double[] q = new double[]{
                        0.75 * p0[0] + 0.25 * p1[0],
                        0.75 * p0[1] + 0.25 * p1[1]
                };
                double[] r = new double[]{
                        0.25 * p0[0] + 0.75 * p1[0],
                        0.25 * p0[1] + 0.75 * p1[1]
                };
                next.add(q);
                next.add(r);
            }
            next.add(points.get(points.size() - 1));
            points = next;
        }
        return points;
    }

    private static class Node {
        final int x;
        final int z;
        final int dir;
        final double g;
        final double f;

        Node(int x, int z, int dir, double g, double f) {
            this.x = x;
            this.z = z;
            this.dir = dir;
            this.g = g;
            this.f = f;
        }
    }

    private static class CityBounds {
        int minX;
        int minZ;
        int maxX;
        int maxZ;
    }

    public static class RoadPlan {
        public final Set<Long> blocks = new HashSet<>();
        public final Map<Long, Integer> heights = new HashMap<>();
        public final Set<Long> slabBlocks = new HashSet<>();
    }
}

