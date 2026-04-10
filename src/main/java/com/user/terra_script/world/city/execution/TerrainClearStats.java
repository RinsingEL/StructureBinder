package com.user.terra_script.world.city.execution;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TerrainClearStats {
    private static final int SAMPLE_LIMIT_PER_REASON = 6;
    private static final int TOP_BLOCK_LIMIT = 8;

    private final String stage;
    private final Map<String, Integer> reasonCounts = new LinkedHashMap<>();
    private final Map<String, Integer> blockCounts = new LinkedHashMap<>();
    private final Map<String, List<ClearSample>> samplesByReason = new LinkedHashMap<>();
    private int totalClearedBlocks;

    public TerrainClearStats(String stage) {
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }

    public int totalClearedBlocks() {
        return totalClearedBlocks;
    }

    public void mergeFrom(TerrainClearStats other) {
        if (other == null || other == this) return;
        totalClearedBlocks += other.totalClearedBlocks;
        other.reasonCounts.forEach((reason, count) -> reasonCounts.merge(reason, count, Integer::sum));
        other.blockCounts.forEach((blockId, count) -> blockCounts.merge(blockId, count, Integer::sum));
        other.samplesByReason.forEach((reason, samples) -> {
            if (samples == null || samples.isEmpty()) return;
            List<ClearSample> merged = samplesByReason.computeIfAbsent(reason, ignored -> new ArrayList<>());
            for (ClearSample sample : samples) {
                if (sample == null || merged.size() >= SAMPLE_LIMIT_PER_REASON) continue;
                merged.add(sample);
            }
        });
    }

    public void record(BlockPos pos, BlockState state, String reason) {
        record(pos, blockId(state != null ? state.getBlock() : null), reason);
    }

    public void record(BlockPos pos, String blockId, String reason) {
        String normalizedReason = normalize(reason, "unknown");
        String normalizedBlockId = normalize(blockId, "minecraft:unknown");
        totalClearedBlocks++;
        reasonCounts.merge(normalizedReason, 1, Integer::sum);
        blockCounts.merge(normalizedBlockId, 1, Integer::sum);
        List<ClearSample> samples = samplesByReason.computeIfAbsent(normalizedReason, ignored -> new ArrayList<>());
        if (samples.size() < SAMPLE_LIMIT_PER_REASON && pos != null) {
            samples.add(new ClearSample(pos.getX(), pos.getY(), pos.getZ(), normalizedBlockId));
        }
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("stage", stage);
        out.addProperty("total_cleared_blocks", totalClearedBlocks);
        out.add("reason_counts", toCountObject(reasonCounts));
        out.add("top_block_counts", toTopBlockArray());
        out.add("sample_positions_by_reason", toSampleObject());
        return out;
    }

    private JsonObject toCountObject(Map<String, Integer> counts) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            out.addProperty(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private JsonArray toTopBlockArray() {
        JsonArray out = new JsonArray();
        blockCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_BLOCK_LIMIT)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("block_id", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    out.add(item);
                });
        return out;
    }

    private JsonObject toSampleObject() {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, List<ClearSample>> entry : samplesByReason.entrySet()) {
            JsonArray samples = new JsonArray();
            for (ClearSample sample : entry.getValue()) {
                JsonObject item = new JsonObject();
                item.addProperty("x", sample.x());
                item.addProperty("y", sample.y());
                item.addProperty("z", sample.z());
                item.addProperty("block_id", sample.blockId());
                samples.add(item);
            }
            out.add(entry.getKey(), samples);
        }
        return out;
    }

    private static String blockId(Block block) {
        if (block == null) return "minecraft:unknown";
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key != null ? key.toString() : "minecraft:unknown";
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.toLowerCase(Locale.ROOT);
    }

    private record ClearSample(int x, int y, int z, String blockId) {
    }
}
