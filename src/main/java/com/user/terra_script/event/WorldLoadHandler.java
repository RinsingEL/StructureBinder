//package com.user.terra_script.event;
//
//import com.user.terra_script.client.data.ScanResultHolder;
//import com.user.terra_script.scan.SatelliteScanner;
//import net.minecraft.server.level.ServerLevel;
//import net.minecraftforge.event.server.ServerStartedEvent;
//import net.minecraftforge.eventbus.api.SubscribeEvent;
//import net.minecraftforge.fml.common.Mod;
//
//@Mod.EventBusSubscriber(modid = "terra_script") // 替换为你的 modid
//public class WorldLoadHandler {
//
//    @SubscribeEvent
//    public static void onServerStarted(ServerStartedEvent event) {
//        // 获取主世界 (Overworld)
//        ServerLevel level = event.getServer().overworld();
//
//        if (level == null) return;
//
//        System.out.println("[TerraScript] World loaded, triggering auto-scan...");
//
//        // 设定扫描参数：半径 500 Chunk, 分辨率 100x100
//        int radiusChunks = 500;
//        int targetRes = 100;
//
//        // 异步执行扫描，不卡顿服务器启动
//        SatelliteScanner.scanAsync(level, radiusChunks, targetRes)
//                .thenAccept(result -> {
//                    System.out.println("[TerraScript] Auto-scan complete. Data cached.");
//
//                    // 计算步长
//                    int worldRadiusBlocks = radiusChunks * 16;
//                    int totalWidth = worldRadiusBlocks * 2;
//                    int actualStep = Math.max(1, totalWidth / targetRes);
//
//                    // 存入缓存
//                    var holder = ScanResultHolder.get();
//                    holder.lastScanData = result;
//                    holder.lastClusterMap = null; // 清空旧聚类
//                    holder.seedUsed = level.getSeed();
//                    holder.scanRadiusChunks = radiusChunks;
//                    holder.scanStep = actualStep;
//
//                    // 可选：扫描完成后自动进行一次聚类（为了 F7 打开时直接能看）
//                    // List<ScanRegion> regions = ClusterAnalyzer.analyze(result, TargetType.CONTINENT, 5);
//                    // holder.lastClusters = regions;
//                    // ... generate mask ...
//                });
//    }
//}