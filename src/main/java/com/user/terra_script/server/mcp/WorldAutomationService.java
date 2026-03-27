package com.user.terra_script.server.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.data.ScanResultHolder.RegionCache;
import com.user.terra_script.core.artifact.ArtifactStore;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.core.workflow.FileStageStatusStore;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.domain.world.scan.ScanRegion;
import com.user.terra_script.domain.world.scan.service.ClusterAnalyzer;
import com.user.terra_script.domain.world.scan.service.SatelliteScanner;
import com.user.terra_script.domain.world.stage.W3Stage;
import com.user.terra_script.domain.world.stage.W4PreviewExporter;
import com.user.terra_script.util.ScanDataIO;
import com.user.terra_script.util.TerrainFeatureComputer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class WorldAutomationService {
    private WorldAutomationService() {}

    static JsonObject startWorldScan(MinecraftServer server, int chunkRadius, int targetResolution) throws Exception {
        if (server == null || server.overworld() == null) {
            throw new IllegalStateException("Minecraft server/overworld unavailable");
        }
        ServerLevel level = server.overworld();
        long startedAt = System.currentTimeMillis();
        ScanPixel[][] scan = SatelliteScanner.scanAsync(level, chunkRadius, targetResolution).get();
        if (scan == null) {
            throw new IllegalStateException("World scan aborted or returned null");
        }

        ScanResultHolder holder = ScanResultHolder.get();
        holder.lastScanData = scan;
        holder.scanRadiusChunks = chunkRadius;
        holder.seedUsed = level.getSeed();
        int totalWidth = chunkRadius * 16 * 2;
        holder.scanStep = Math.max(1, totalWidth / Math.max(1, targetResolution));
        holder.lastClusters = null;
        holder.lastOceanRegions = null;
        holder.lastClusterMap = null;
        ScanDataIO.saveAll();

        JsonObject res = new JsonObject();
        res.addProperty("step", "W3_SCAN");
        res.addProperty("ok", true);
        res.addProperty("seed", level.getSeed());
        res.addProperty("chunk_radius", chunkRadius);
        res.addProperty("target_resolution", targetResolution);
        res.addProperty("grid_width", scan.length);
        res.addProperty("grid_height", scan.length > 0 && scan[0] != null ? scan[0].length : 0);
        res.addProperty("scan_step", holder.scanStep);
        res.addProperty("duration_ms", System.currentTimeMillis() - startedAt);
        res.addProperty("cache_written", true);
        return res;
    }

    static JsonObject clusterWorld(MinecraftServer server, int continentMinSize, int oceanMinSizeMultiplier, int mergeDistance) throws Exception {
        ScanResultHolder holder = ScanResultHolder.get();
        if (holder.lastScanData == null) {
            throw new IllegalStateException("W3 cluster requires lastScanData. Run world scan first.");
        }

        long startedAt = System.currentTimeMillis();
        List<ScanRegion> landRegions = ClusterAnalyzer.analyze(
                holder.lastScanData,
                ClusterAnalyzer.TargetType.CONTINENT,
                Math.max(1, continentMinSize),
                Math.max(0, mergeDistance)
        );
        List<ScanRegion> oceanRegions = ClusterAnalyzer.analyze(
                holder.lastScanData,
                ClusterAnalyzer.TargetType.OCEAN,
                Math.max(1, continentMinSize) * Math.max(1, oceanMinSizeMultiplier),
                Math.max(0, mergeDistance)
        );
        holder.lastClusters = landRegions;
        holder.lastOceanRegions = oceanRegions;
        holder.lastClusterMap = ClusterAnalyzer.expandOceans(holder.lastScanData, landRegions);
        ScanDataIO.saveAll();

        StageContext ctx = stageContext(server);
        new W3Stage().run(ctx);

        JsonObject res = new JsonObject();
        res.addProperty("step", "W3");
        res.addProperty("ok", true);
        res.addProperty("duration_ms", System.currentTimeMillis() - startedAt);
        res.addProperty("continent_count", landRegions.size());
        res.addProperty("ocean_count", oceanRegions.size());
        res.add("continents", summarizeRegions(holder.lastClusters, holder));
        return res;
    }

    static JsonObject getRegionStatus(int regionId) {
        ScanResultHolder holder = ScanResultHolder.get();
        RegionCache cache = holder.regionCacheMap.get(regionId);
        JsonObject res = new JsonObject();
        res.addProperty("step", "W4_REGION_STATUS");
        res.addProperty("region_id", regionId);
        res.addProperty("has_cache", cache != null);
        if (cache != null) {
            res.addProperty("has_detail_data", cache.detailData != null);
            res.addProperty("has_slope_data", cache.slopeData != null);
            res.addProperty("has_roughness_data", cache.roughnessData != null);
            res.addProperty("has_tpi_data", cache.tpiData != null);
            res.addProperty("min_x", cache.minX);
            res.addProperty("min_z", cache.minZ);
            res.addProperty("width_blocks", cache.w);
            res.addProperty("height_blocks", cache.h);
            res.addProperty("scan_step", cache.step);
        }
        return res;
    }

    static JsonObject scanRegion(MinecraftServer server, int regionId, int paddingBlocks, int scanStep) throws Exception {
        if (server == null || server.overworld() == null) {
            throw new IllegalStateException("Minecraft server/overworld unavailable");
        }
        ScanResultHolder holder = ScanResultHolder.get();
        ScanRegion region = findRegion(holder, regionId);
        if (region == null) {
            throw new IllegalArgumentException("Unknown region_id: " + regionId);
        }

        int padding = Math.max(0, paddingBlocks);
        int minX = region.minX - padding;
        int minZ = region.minZ - padding;
        int width = Math.max(16, (region.maxX - region.minX) + padding * 2 + 1);
        int height = Math.max(16, (region.maxZ - region.minZ) + padding * 2 + 1);
        int step = Math.max(1, scanStep);

        long startedAt = System.currentTimeMillis();
        ScanPixel[][] detail = SatelliteScanner.scanRegionAsync(server.overworld(), minX, minZ, width, height, step).get();
        if (detail == null) {
            throw new IllegalStateException("Region scan aborted or returned null");
        }

        RegionCache cache = new RegionCache(region, minX, minZ, width, height, step);
        cache.detailData = detail;
        cache.slopeData = TerrainFeatureComputer.computeSlope(detail, step, true);
        cache.roughnessData = TerrainFeatureComputer.computeRoughness(detail, step, 2, true);
        cache.tpiData = TerrainFeatureComputer.computeTPI(detail, 5, true);
        holder.regionCacheMap.put(regionId, cache);
        holder.lastEditedRegionId = regionId;
        ScanDataIO.saveAll();

        JsonObject res = new JsonObject();
        res.addProperty("step", "W4_REGION_SCAN");
        res.addProperty("ok", true);
        res.addProperty("region_id", regionId);
        res.addProperty("min_x", minX);
        res.addProperty("min_z", minZ);
        res.addProperty("width_blocks", width);
        res.addProperty("height_blocks", height);
        res.addProperty("scan_step", step);
        res.addProperty("grid_width", detail.length);
        res.addProperty("grid_height", detail.length > 0 && detail[0] != null ? detail[0].length : 0);
        res.addProperty("duration_ms", System.currentTimeMillis() - startedAt);
        res.addProperty("cache_written", true);
        return res;
    }

    static JsonObject exportW4(MinecraftServer server, Integer regionId, boolean allCachedRegions) throws Exception {
        ScanResultHolder holder = ScanResultHolder.get();
        if (holder.regionCacheMap == null || holder.regionCacheMap.isEmpty()) {
            throw new IllegalStateException("W4 export requires region cache. Run w4_region_scan first.");
        }
        if (!allCachedRegions) {
            if (regionId == null || regionId <= 0) throw new IllegalArgumentException("region_id is required unless all_cached_regions=true");
            RegionCache cache = holder.regionCacheMap.get(regionId);
            if (cache == null || cache.detailData == null || cache.slopeData == null || cache.roughnessData == null || cache.tpiData == null) {
                throw new IllegalStateException("Region cache incomplete for region_id=" + regionId + ". Run w4_region_scan first.");
            }
        }

        long startedAt = System.currentTimeMillis();
        StageContext ctx = stageContext(server);
        JsonObject summary = new W4StageExportService().export(ctx, holder);
        JsonObject res = new JsonObject();
        res.addProperty("step", "W4");
        res.addProperty("ok", true);
        if (regionId != null) res.addProperty("region_id", regionId);
        res.addProperty("all_cached_regions", allCachedRegions);
        res.addProperty("cached_region_count", holder.regionCacheMap.size());
        res.addProperty("duration_ms", System.currentTimeMillis() - startedAt);
        res.add("summary", summary);
        return res;
    }

    private static JsonArray summarizeRegions(List<ScanRegion> regions, ScanResultHolder holder) {
        JsonArray arr = new JsonArray();
        if (regions == null) return arr;
        List<ScanRegion> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparingInt(r -> r.id));
        for (ScanRegion r : sorted) {
            JsonObject item = new JsonObject();
            item.addProperty("id", r.id);
            item.addProperty("area", r.area);
            item.addProperty("center_x", r.centerX);
            item.addProperty("center_z", r.centerZ);
            item.addProperty("has_detail", holder.regionCacheMap.containsKey(r.id));
            arr.add(item);
        }
        return arr;
    }

    private static ScanRegion findRegion(ScanResultHolder holder, int regionId) {
        if (holder.lastClusters != null) {
            for (ScanRegion region : holder.lastClusters) {
                if (region != null && region.id == regionId) return region;
            }
        }
        if (holder.lastOceanRegions != null) {
            for (ScanRegion region : holder.lastOceanRegions) {
                if (region != null && region.id == regionId) return region;
            }
        }
        RegionCache cache = holder.regionCacheMap.get(regionId);
        return cache != null ? cache.regionInfo : null;
    }

    private static StageContext stageContext(MinecraftServer server) {
        ArtifactStore artifacts = new ArtifactStore();
        FileStageStatusStore statusStore = new FileStageStatusStore(artifacts);
        return StageContext.forServer(server, artifacts, statusStore);
    }

    private static final class W4StageExportService extends com.user.terra_script.domain.world.stage.W4Stage {
        JsonObject export(StageContext ctx, ScanResultHolder holder) throws Exception {
            execute(ctx);
            return W4PreviewExporter.export(ctx, holder);
        }
    }
}
