package com.user.terra_script.client.data;

import com.user.terra_script.scan.ScanPixel;
import com.user.terra_script.scan.ScanRegion;
import java.util.List;

public class ScanResultHolder {
    private static final ScanResultHolder INSTANCE = new ScanResultHolder();

    public static ScanResultHolder get() { return INSTANCE; }

    // 扫描数据
    public ScanPixel[][] lastScanData = null;

    // 聚类列表 (给 LLM 用)
    public List<ScanRegion> lastClusters = null;

    // 聚类渲染掩码 (给 GUI 渲染用，解决报错: lastClusterMap)
    public int[][] lastClusterMap = null;

    // 元数据
    public long seedUsed = 0;

    // 【新增】存储扫描时的参数，用于反算玩家位置
    public int scanRadiusChunks = 500;
    public int scanStep = 1;
}