package com.user.terra_script.client.data;

import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.domain.world.scan.ScanRegion;
import com.user.terra_script.util.ScanDataIO;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ScanResultHolder {
    private static final ScanResultHolder INSTANCE = new ScanResultHolder();

    public static ScanResultHolder get() { return INSTANCE; }

    // --- 全局宏观数据 (全图扫描结果) ---
    public ScanPixel[][] lastScanData = null;
    public List<ScanRegion> lastClusters = null; // 陆地列表
    public List<ScanRegion> lastOceanRegions = null; // 【新增】海洋列表
    public int[][] lastClusterMap = null;
    public long seedUsed = 0;
    public int scanRadiusChunks = 500;
    public int scanStep = 1;

    // --- 局部微观数据存储 (核心修改：使用 Map 存储多个区域) ---
    // Key: Region ID, Value: 该区域的详细数据包
    public final Map<Integer, RegionCache> regionCacheMap = new ConcurrentHashMap<>();

    // 记录最后一次在编辑器中打开的区域 ID (用于 UI 恢复状态)
    public int lastEditedRegionId = -1;

    // --- 数据包定义 ---
    public static class RegionCache {
        public ScanRegion regionInfo;    // 区域基础信息
        public ScanPixel[][] detailData; // 高精度扫描数据
        public double[][] slopeData;     // 斜率缓存
        public double[][] roughnessData; // 崎岖度缓存
        public double[][] tpiData;       // 地形位置指数缓存

        // 扫描该区域时使用的参数 (用于坐标还原)
        public int minX, minZ, w, h, step;

        public RegionCache(ScanRegion region, int minX, int minZ, int w, int h, int step) {
            this.regionInfo = region;
            this.minX = minX;
            this.minZ = minZ;
            this.w = w;
            this.h = h;
            this.step = step;
        }
    }

    private ScanResultHolder() {
        // 初始化时尝试从磁盘加载
        ScanDataIO.loadInto(this);
    }

    public void clearAll() {
        lastScanData = null;
        lastClusters = null;
        lastOceanRegions = null;
        lastClusterMap = null;
        seedUsed = 0;
        scanRadiusChunks = 500;
        scanStep = 1;

        // 清空所有局部缓存
        regionCacheMap.clear();
        lastEditedRegionId = -1;

        System.out.println("[ScanResultHolder] All cache cleared.");
    }
}
