package com.user.terra_script.domain.world.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.client.data.ScanResultHolder.RegionCache;
import com.user.terra_script.core.artifact.ArtifactKey;
import com.user.terra_script.core.stage.StageBase;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.core.workflow.FileStageStatusStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

public class W4Stage extends StageBase {
    @Override
    public String id() { return "W4"; }

    @Override
    public java.util.List<String> dependsOn() { return java.util.List.of("W3"); }

    @Override
    protected void execute(StageContext ctx) throws Exception {
        ScanResultHolder holder = ScanResultHolder.get();
        if (ctx.statusStore instanceof FileStageStatusStore store) {
            store.updateProgress("W4", ctx, "W4 preparing terrain summary and preview images.", 0L, 3L, "w4_prepare");
        }
        if (holder.regionCacheMap == null || holder.regionCacheMap.isEmpty()) {
            throw new IllegalStateException("W4 requires region cache (regionCacheMap)");
        }

        // Summary JSON
        JsonObject summary = new JsonObject();
        JsonArray regions = new JsonArray();
        for (RegionCache cache : holder.regionCacheMap.values()) {
            JsonObject r = new JsonObject();
            r.addProperty("id", cache.regionInfo.id);
            r.addProperty("minX", cache.minX);
            r.addProperty("minZ", cache.minZ);
            r.addProperty("w", cache.w);
            r.addProperty("h", cache.h);
            r.addProperty("step", cache.step);
            regions.add(r);
        }
        summary.add("regions", regions);
        JsonObject previews = W4PreviewExporter.export(ctx, holder);
        if (ctx.statusStore instanceof FileStageStatusStore store) {
            store.updateProgress("W4", ctx, "W4 preview images exported.", 1L, 3L, "w4_previews");
        }
        summary.add("preview_images", previews);

        Path summaryPath = ctx.artifacts.resolve(ctx.server, ctx.worldId, ArtifactKey.W4_TERRAIN_SUMMARY_JSON);
        ctx.artifacts.writeJsonAtomic(summaryPath, summary);
        if (ctx.statusStore instanceof FileStageStatusStore store) {
            store.updateProgress("W4", ctx, "W4 terrain summary saved.", 2L, 3L, "w4_summary");
        }

        // TerrainFacts.dat (NBT)
        CompoundTag root = new CompoundTag();
        ListTag regionsList = new ListTag();
        for (RegionCache cache : holder.regionCacheMap.values()) {
            CompoundTag rTag = new CompoundTag();
            rTag.putInt("id", cache.regionInfo.id);
            rTag.putInt("minX", cache.minX);
            rTag.putInt("minZ", cache.minZ);
            rTag.putInt("w", cache.w);
            rTag.putInt("h", cache.h);
            rTag.putInt("step", cache.step);

            if (cache.slopeData != null) rTag.putLongArray("slope", compressDoubleMatrix(cache.slopeData));
            if (cache.roughnessData != null) rTag.putLongArray("roughness", compressDoubleMatrix(cache.roughnessData));
            if (cache.tpiData != null) rTag.putLongArray("tpi", compressDoubleMatrix(cache.tpiData));

            regionsList.add(rTag);
        }
        root.put("regions", regionsList);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, baos);
        Path datPath = ctx.artifacts.resolve(ctx.server, ctx.worldId, ArtifactKey.W4_TERRAIN_FACTS_DAT);
        ctx.artifacts.writeDatAtomic(datPath, baos.toByteArray());
        if (ctx.statusStore instanceof FileStageStatusStore store) {
            store.updateProgress("W4", ctx, "W4 terrain facts saved.", 3L, 3L, "w4_dat");
        }
    }

    private static long[] compressDoubleMatrix(double[][] matrix) {
        int w = matrix.length;
        int h = matrix[0].length;
        long[] result = new long[w * h];
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                result[i * h + j] = Double.doubleToLongBits(matrix[i][j]);
            }
        }
        return result;
    }
}

