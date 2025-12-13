package com.user.terra_script.util;

import com.user.terra_script.config.WorldProjectData;
import net.minecraft.util.Mth;

public class ContinentTool {

    /**
     * 获取某坐标的大陆因子
     * @return 0.0 (深海) ~ 1.0 (核心陆地)
     */
    public static double getLandFactor(int blockX, int blockZ) {
        // 将方块坐标转换为 "区块网格坐标" (浮点数)
        // 例如 x=24 -> gridX=1.5
        double gridX = blockX / 16.0;
        double gridZ = blockZ / 16.0;

        int x0 = Mth.floor(gridX);
        int z0 = Mth.floor(gridZ);

        // 获取周围 4 个区块中心的原始配置 (0=海, 1=陆)
        double v00 = getChunkConfig(x0, z0);
        double v10 = getChunkConfig(x0 + 1, z0);
        double v01 = getChunkConfig(x0, z0 + 1);
        double v11 = getChunkConfig(x0 + 1, z0 + 1);

        // 计算网格内的相对位置 (0.0 ~ 1.0)
        double dx = gridX - x0;
        double dz = gridZ - z0;

        // 使用 SmoothStep 曲线 (3x^2 - 2x^3) 进行插值
        // 这种插值比线性插值更圆润，不会有棱角
        double smoothX = dx * dx * (3 - 2 * dx);
        double smoothZ = dz * dz * (3 - 2 * dz);

        // 双线性混合
        double v0 = Mth.lerp(smoothX, v00, v10);
        double v1 = Mth.lerp(smoothX, v01, v11);

        return Mth.lerp(smoothZ, v0, v1);
    }

    // 查询 WorldProjectData：该区块是否属于任何大陆
    private static double getChunkConfig(int chunkX, int chunkZ) {
        WorldProjectData data = WorldProjectData.get();
        if (data == null) return 0.0; // 默认海

        for (WorldProjectData.Continent c : data.continents) {
            if (c.containsChunk(chunkX, chunkZ)) {
                return 1.0; // 是陆地
            }
        }
        return 0.0; // 是海洋
    }
}