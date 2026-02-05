package com.user.terra_script.domain.world.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.core.artifact.ArtifactKey;
import com.user.terra_script.core.stage.StageBase;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.domain.world.scan.ScanPixel;
import com.user.terra_script.domain.world.scan.ScanRegion;
import com.user.terra_script.domain.world.scan.service.ClusterAnalyzer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class W3Stage extends StageBase {
    @Override
    public String id() { return "W3"; }

    @Override
    public List<String> dependsOn() { return List.of(); }

    @Override
    protected void execute(StageContext ctx) throws Exception {
        ScanResultHolder holder = ScanResultHolder.get();
        ScanPixel[][] map = holder.lastScanData;
        if (map == null) throw new IllegalStateException("W3 requires global scan data (lastScanData)");

        int minSize = 5;
        List<ScanRegion> landRegions = ClusterAnalyzer.analyze(map, ClusterAnalyzer.TargetType.CONTINENT, minSize, 0);
        List<ScanRegion> oceanRegions = ClusterAnalyzer.analyze(map, ClusterAnalyzer.TargetType.OCEAN, minSize * 10, 0);
        holder.lastClusters = landRegions;
        holder.lastOceanRegions = oceanRegions;
        holder.lastClusterMap = ClusterAnalyzer.expandOceans(map, landRegions);

        JsonArray landJson = new JsonArray();
        for (ScanRegion r : landRegions) landJson.add(toJson(r, "CONTINENT"));
        JsonArray oceanJson = new JsonArray();
        for (ScanRegion r : oceanRegions) oceanJson.add(toJson(r, "OCEAN"));

        Path landPath = ctx.artifacts.resolve(ctx.server, ctx.worldId, ArtifactKey.W3_CONTINENT_META_JSON);
        Path oceanPath = ctx.artifacts.resolve(ctx.server, ctx.worldId, ArtifactKey.W3_OCEAN_META_JSON);
        ctx.artifacts.writeJsonAtomic(landPath, landJson);
        ctx.artifacts.writeJsonAtomic(oceanPath, oceanJson);
    }

    private JsonObject toJson(ScanRegion r, String type) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", r.id);
        obj.addProperty("type", type);

        JsonObject metrics = new JsonObject();
        metrics.addProperty("area_pixels", r.area);
        metrics.addProperty("avg_height", Math.round(r.avgHeight));
        metrics.addProperty("max_relief", r.maxRelief);
        metrics.addProperty("roughness", String.format("%.2f", r.roughness));
        metrics.addProperty("center_x", r.centerX);
        metrics.addProperty("center_z", r.centerZ);
        obj.add("metrics", metrics);

        JsonObject climate = new JsonObject();
        climate.addProperty("avg_temp", String.format("%.2f", r.avgTemp));
        climate.addProperty("temp_range", String.format("[%.2f, %.2f]", r.minTemp, r.maxTemp));
        JsonArray grid = new JsonArray();
        if (r.climateGrid != null) {
            for (int z = 0; z < 4; z++) {
                JsonArray row = new JsonArray();
                for (int x = 0; x < 4; x++) row.add(Float.parseFloat(String.format("%.1f", r.climateGrid[x][z])));
                grid.add(row);
            }
        }
        climate.add("temp_distribution_4x4", grid);
        obj.add("climate", climate);

        JsonObject ecology = new JsonObject();
        JsonArray dom = new JsonArray();
        if (r.biomePercentages != null) {
            r.biomePercentages.forEach((id, pct) -> {
                JsonObject b = new JsonObject();
                b.addProperty("id", id);
                b.addProperty("pct", pct);
                dom.add(b);
            });
        }
        ecology.add("dominant_biomes", dom);

        if (r.rareBiomes != null && !r.rareBiomes.isEmpty()) {
            JsonArray rare = new JsonArray();
            for (var rb : r.rareBiomes) {
                JsonObject b = new JsonObject();
                b.addProperty("id", rb.id());
                b.addProperty("pos", rb.x() + "," + rb.z());
                rare.add(b);
            }
            ecology.add("rare_finds", rare);
        }
        obj.add("ecology", ecology);

        return obj;
    }
}
