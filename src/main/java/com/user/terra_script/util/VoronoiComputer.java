package com.user.terra_script.util;

import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.district.District;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

public class VoronoiComputer {

    public static List<District> computeDistricts(CityInstance city) {
        List<District> districts = new ArrayList<>();
        if (city.claimedChunks.isEmpty()) return districts;

        Random rand = new Random(city.id.hashCode());

        // 1. 确定种子数量
        // 假设平均每个小区包含 6 个 Chunk (比较合适的大小，能放几个建筑)
        int totalChunks = city.claimedChunks.size();
        int targetDistricts = Math.max(3, totalChunks / 6);

        // 2. 收集所有 Chunk 坐标作为候选池
        List<Long> candidates = new ArrayList<>(city.claimedChunks.keySet());
        Collections.shuffle(candidates, rand); // 打乱以随机选取

        // 3. 选种子 (Seeding)
        // 我们可以简单地取前 N 个 Chunk 作为种子中心
        // 但为了更好的分布，可以分层选取

        for (int i = 0; i < targetDistricts; i++) {
            if (i >= candidates.size()) break;
            long seedChunk = candidates.get(i);

            // 将中心定在 Chunk 中心
            double cx = (ChunkPos.getX(seedChunk) * 16) + 8;
            double cz = (ChunkPos.getZ(seedChunk) * 16) + 8;

            District d = new District(i + 1, cx, cz);
            d.cityId = city.id;

            // 继承该 Chunk 的 Zone 类型 (CORE/URBAN/BUFFER)
            CityInstance.CityZoneType type = city.claimedChunks.get(seedChunk);
            d.zoneType = type.name();

            districts.add(d);
        }

        // 4. 分配归属 (Voronoi Partitioning on Chunks)
        // 这里我们需要把归属信息存回 city，或者存进 district 对象
        // 建议：存进 District 对象里，方便导出
        // 修改 District 类，增加 public List<Long> chunks = new ArrayList<>();

        for (long chunkKey : city.claimedChunks.keySet()) {
            int cx = ChunkPos.getX(chunkKey) * 16 + 8;
            int cz = ChunkPos.getZ(chunkKey) * 16 + 8;

            District nearest = null;
            double minDst = Double.MAX_VALUE;

            for (District d : districts) {
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

        // 5. 重新计算中心点 (Lloyd Relaxation step 1) - 可选，让种子移动到几何中心

        return districts;
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
                    // 平坦度 (简单用高度近似，或者取周围点方差)
                    // d.avgSlope = ...

                    // 水源距离
                    // 需要 BFS 搜索最近的水，太慢了。
                    // 快速法：直接看 p.isLand()，如果是 false 则距离为 0。
                    // 否则看 holder.distanceField (如果我们在 expandOceans 里存了的话)
                    // 或者是简单的：如果本来就是 BUFFER 且靠近海边（biom is beach），设为近水
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

}