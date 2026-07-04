package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CitySurfaceCache {
    public static final String SCHEMA = "city_surface_cache.v0.1";
    private static final int MAGIC = 0x43534643; // CSFC
    private static final int VERSION = 1;

    private CitySurfaceCache() {
    }

    public static JsonObject writeBackfill(ServerLevel level, BlockBounds bounds, Path outputDirectory,
                                           String cityId) throws IOException {
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", SCHEMA);
        report.addProperty("cityId", cityId == null || cityId.isBlank() ? "unknown_city" : cityId);
        report.add("bounds", boundsJson(bounds));
        report.addProperty("storageFormat", ".dat");
        report.addProperty("sampleGranularityBlocks", 1);
        report.addProperty("fields", "surfaceY/topBlock/fluid/biome/temperature/flags");
        if (level == null) {
            report.addProperty("status", "skipped");
            report.addProperty("reasonCode", "SURFACE_CACHE_LEVEL_UNAVAILABLE");
            return report;
        }
        Files.createDirectories(outputDirectory.resolve("surface_cache"));
        Path datPath = outputDirectory.resolve("surface_cache")
                .resolve(safe(cityId) + "_surface_v5.dat");
        int count = 0;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(datPath)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(report.get("cityId").getAsString());
            out.writeInt(bounds.minX());
            out.writeInt(bounds.minZ());
            out.writeInt(bounds.maxX());
            out.writeInt(bounds.maxZ());
            out.writeInt(bounds.widthBlocks() * bounds.heightBlocks());
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    Sample sample = sample(level, x, z);
                    out.writeInt(x);
                    out.writeInt(z);
                    out.writeInt(sample.surfaceY());
                    out.writeUTF(sample.topBlock());
                    out.writeBoolean(sample.fluid());
                    out.writeUTF(sample.biome());
                    out.writeFloat(sample.temperature());
                    out.writeInt(sample.flags());
                    count++;
                }
            }
        }
        JsonObject chunkRange = new JsonObject();
        chunkRange.addProperty("minChunkX", Math.floorDiv(bounds.minX(), 16));
        chunkRange.addProperty("minChunkZ", Math.floorDiv(bounds.minZ(), 16));
        chunkRange.addProperty("maxChunkX", Math.floorDiv(bounds.maxX(), 16));
        chunkRange.addProperty("maxChunkZ", Math.floorDiv(bounds.maxZ(), 16));
        report.add("chunkRange", chunkRange);
        report.addProperty("status", "written");
        report.addProperty("sampleCount", count);
        report.addProperty("datPath", datPath.toString());
        return report;
    }

    public static Sample sample(ServerLevel level, int x, int z) {
        int surfaceY = surfaceY(level, x, z);
        int topY = Math.max(level.getMinBuildHeight(), surfaceY - 1);
        BlockPos top = new BlockPos(x, topY, z);
        BlockState state = level.getBlockState(top);
        boolean fluid = !level.getFluidState(top).isEmpty()
                || !level.getFluidState(new BlockPos(x, surfaceY, z)).isEmpty();
        var biome = level.getBiome(top);
        String biomeId = biome.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
        int flags = 0;
        if (fluid) {
            flags |= 1;
        }
        if (!state.isAir()) {
            flags |= 2;
        }
        if (state.isAir()) {
            flags |= 4;
        }
        return new Sample(surfaceY,
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                fluid,
                biomeId,
                biome.value().getBaseTemperature(),
                flags);
    }

    private static int surfaceY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private static JsonObject boundsJson(BlockBounds bounds) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minX", bounds.minX());
        obj.addProperty("minZ", bounds.minZ());
        obj.addProperty("maxX", bounds.maxX());
        obj.addProperty("maxZ", bounds.maxZ());
        return obj;
    }

    private static String safe(String raw) {
        return raw == null ? "unknown_city" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record Sample(int surfaceY, String topBlock, boolean fluid, String biome, float temperature, int flags) {
    }
}
