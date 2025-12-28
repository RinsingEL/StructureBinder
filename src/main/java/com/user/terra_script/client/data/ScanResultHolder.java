package com.user.terra_script.client.data;

import com.user.terra_script.scan.ScanPixel;
import com.user.terra_script.scan.ScanRegion;
import com.user.terra_script.util.ScanDataIO;
import java.util.List;

public class ScanResultHolder {
    // 1. 先声明 INSTANCE
    private static final ScanResultHolder INSTANCE = new ScanResultHolder();

    public static ScanResultHolder get() { return INSTANCE; }

    // ... (中间的所有字段变量保持不变: lastScanData, lastClusters 等) ...
    public ScanPixel[][] lastScanData = null;
    public List<ScanRegion> lastClusters = null;
    public int[][] lastClusterMap = null;
    public long seedUsed = 0;
    public int scanRadiusChunks = 500;
    public int scanStep = 1;
    public ScanRegion lastEditedRegion = null;
    public ScanPixel[][] lastRegionDetailData = null;
    public double[][] lastRegionSlopeData = null;
    public double[][] lastRegionRoughnessData = null;
    public double[][] lastRegionTpiData = null;
    public int lastRegionMinX = 0;
    public int lastRegionMinZ = 0;
    public int lastRegionW = 0;
    public int lastRegionH = 0;
    public int lastRegionStep = 1;

    // 2. 修改构造函数
    private ScanResultHolder() {
        ScanDataIO.loadInto(this);
    }

    public void clearAll() {
        // ... (保持不变) ...
        lastScanData = null;
        lastClusters = null;
        lastClusterMap = null;
        seedUsed = 0;
        scanRadiusChunks = 500;
        scanStep = 1;
        lastEditedRegion = null;
        lastRegionDetailData = null;
        lastRegionSlopeData = null;
        lastRegionRoughnessData = null;
        lastRegionTpiData = null;
        lastRegionMinX = 0;
        lastRegionMinZ = 0;
        lastRegionW = 0;
        lastRegionH = 0;
        lastRegionStep = 1;
        System.out.println("[ScanResultHolder] All cache cleared.");
    }
}