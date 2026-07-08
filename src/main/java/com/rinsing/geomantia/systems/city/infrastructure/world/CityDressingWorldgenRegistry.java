package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CityDressingWorldgenRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ACTIVE_DIR = "geomantia_city_masks";
    private static final String ACTIVE_DRESSING_FILE = "active_city_dressing_plan.json";
    private static final String DRESSING_LEDGER_FILE = "city_dressing_worldgen_ledger.json";

    private static volatile JsonObject activeDressingPlan = emptyActivePlan();
    private static volatile JsonObject dressingLedger = emptyLedger();
    private static volatile Path activeServerRoot;
    private static final Set<String> APPLIED_CHUNKS = new LinkedHashSet<>();

    private CityDressingWorldgenRegistry() {
    }

    public static synchronized JsonObject activate(JsonObject dressingActivationPlan, Path serverRoot) throws IOException {
        activeServerRoot = serverRoot;
        activeDressingPlan = dressingActivationPlan == null ? emptyActivePlan() : dressingActivationPlan.deepCopy();
        APPLIED_CHUNKS.clear();
        if (serverRoot != null) {
            Path dir = activeDir(serverRoot);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(ACTIVE_DRESSING_FILE), CityJson.GSON.toJson(activeDressingPlan));
            dressingLedger = emptyLedger();
            persistLedger();
        }
        LOGGER.info("Activated City dressing worldgen plan: surfaceOps={}, placements={}",
                array(object(activeDressingPlan, "surfaceOperationPlan"), "surfaceOperations").size(),
                array(object(activeDressingPlan, "decorationPlacementPlan"), "decorationPlacements").size());
        return activeSummary();
    }

    public static synchronized void load(Path serverRoot) {
        if (serverRoot == null) {
            return;
        }
        activeServerRoot = serverRoot;
        Path dir = activeDir(serverRoot);
        Path activePath = dir.resolve(ACTIVE_DRESSING_FILE);
        if (Files.exists(activePath)) {
            try {
                activeDressingPlan = JsonParser.parseString(Files.readString(activePath)).getAsJsonObject();
            } catch (Exception ignored) {
                activeDressingPlan = emptyActivePlan();
            }
        }
        Path ledgerPath = dir.resolve(DRESSING_LEDGER_FILE);
        if (Files.exists(ledgerPath)) {
            try {
                dressingLedger = JsonParser.parseString(Files.readString(ledgerPath)).getAsJsonObject();
            } catch (Exception ignored) {
                dressingLedger = emptyLedger();
            }
        }
    }

    public static void applyForFeatureOrigin(WorldGenLevel level, BlockPos origin) {
        if (level == null || origin == null || !hasActivePlan()) {
            return;
        }
        ChunkPos chunkPos = new ChunkPos(origin);
        applyForChunk(level, chunkPos);
    }

    public static synchronized JsonObject activeSummary() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_active_dressing_worldgen_summary.v0.1");
        obj.addProperty("active", hasActivePlan());
        obj.addProperty("cityId", stringValue(activeDressingPlan, "cityId", ""));
        obj.addProperty("surfaceOperationCount",
                array(object(activeDressingPlan, "surfaceOperationPlan"), "surfaceOperations").size());
        obj.addProperty("decorationPlacementCount",
                array(object(activeDressingPlan, "decorationPlacementPlan"), "decorationPlacements").size());
        obj.addProperty("appliedChunkCount", APPLIED_CHUNKS.size());
        return obj;
    }

    public static Path activeDressingPath(Path serverRoot) {
        return activeDir(serverRoot).resolve(ACTIVE_DRESSING_FILE);
    }

    public static Path dressingLedgerPath(Path serverRoot) {
        return activeDir(serverRoot).resolve(DRESSING_LEDGER_FILE);
    }

    private static void applyForChunk(WorldGenLevel level, ChunkPos chunkPos) {
        String key = chunkPos.x + "," + chunkPos.z;
        synchronized (CityDressingWorldgenRegistry.class) {
            if (APPLIED_CHUNKS.contains(key) || ledgerHasChunk(key)) {
                return;
            }
            APPLIED_CHUNKS.add(key);
        }
        int changed = 0;
        JsonObject plan = activeDressingPlan;
        BlockBounds chunkBounds = new BlockBounds(chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockX(), chunkPos.getMaxBlockZ());
        for (JsonElement elem : array(object(plan, "surfaceOperationPlan"), "surfaceOperations")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject op = elem.getAsJsonObject();
            BlockBounds bounds = bounds(object(op, "blockBounds"));
            if (!bounds.overlaps(chunkBounds)) {
                continue;
            }
            changed += applySurfaceOperation(level, op, chunkBounds);
        }
        for (JsonElement elem : array(object(plan, "decorationPlacementPlan"), "decorationPlacements")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject placement = elem.getAsJsonObject();
            BlockBounds body = bounds(object(placement, "bodyEnvelope"));
            if (!body.overlaps(chunkBounds)) {
                continue;
            }
            changed += applyPlacement(level, placement, chunkBounds);
        }
        recordChunk(chunkPos, changed);
    }

    private static int applySurfaceOperation(WorldGenLevel level, JsonObject op, BlockBounds chunkBounds) {
        BlockBounds bounds = bounds(object(op, "blockBounds"));
        BlockState state = blockState(stringValue(op, "blockState", "minecraft:grass_block"));
        int changed = 0;
        for (int z = Math.max(bounds.minZ(), chunkBounds.minZ()); z <= Math.min(bounds.maxZ(), chunkBounds.maxZ()); z++) {
            for (int x = Math.max(bounds.minX(), chunkBounds.minX()); x <= Math.min(bounds.maxX(), chunkBounds.maxX()); x++) {
                int y = Math.max(level.getMinBuildHeight(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1);
                if (level.setBlock(new BlockPos(x, y, z), state, 2)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static int applyPlacement(WorldGenLevel level, JsonObject placement, BlockBounds chunkBounds) {
        int changed = 0;
        for (JsonElement elem : array(placement, "blockOperations")) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject op = elem.getAsJsonObject();
            int x = intValue(op, "x", 0);
            int z = intValue(op, "z", 0);
            if (!chunkBounds.contains(x, z)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                    + intValue(op, "yOffset", 0);
            if (level.setBlock(new BlockPos(x, y, z),
                    blockState(stringValue(op, "blockState", "minecraft:oak_planks")), 2)) {
                changed++;
            }
        }
        return changed;
    }

    private static BlockState blockState(String blockId) {
        try {
            Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation(blockId));
            if (block == null || block == Blocks.AIR) {
                return Blocks.GRASS_BLOCK.defaultBlockState();
            }
            return block.defaultBlockState();
        } catch (Exception ignored) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }
    }

    private static synchronized void recordChunk(ChunkPos chunkPos, int changedBlocks) {
        JsonArray chunks = ensureArray(dressingLedger, "appliedChunks");
        JsonObject obj = new JsonObject();
        obj.addProperty("chunkKey", chunkPos.x + "," + chunkPos.z);
        obj.addProperty("chunkX", chunkPos.x);
        obj.addProperty("chunkZ", chunkPos.z);
        obj.addProperty("changedBlocks", changedBlocks);
        obj.addProperty("recordedAt", Instant.now().toString());
        chunks.add(obj);
        persistLedger();
    }

    private static boolean ledgerHasChunk(String key) {
        for (JsonElement elem : array(dressingLedger, "appliedChunks")) {
            if (elem.isJsonObject() && key.equals(stringValue(elem.getAsJsonObject(), "chunkKey", ""))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasActivePlan() {
        return array(object(activeDressingPlan, "surfaceOperationPlan"), "surfaceOperations").size() > 0
                || array(object(activeDressingPlan, "decorationPlacementPlan"), "decorationPlacements").size() > 0;
    }

    private static JsonObject emptyActivePlan() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_active_dressing_worldgen_plan.v0.1");
        obj.addProperty("cityId", "");
        obj.add("surfaceOperationPlan", new JsonObject());
        obj.add("decorationPlacementPlan", new JsonObject());
        return obj;
    }

    private static JsonObject emptyLedger() {
        JsonObject obj = new JsonObject();
        obj.addProperty("schemaVersion", "city_dressing_worldgen_ledger.v0.1");
        obj.add("appliedChunks", new JsonArray());
        return obj;
    }

    private static void persistLedger() {
        Path root = activeServerRoot;
        if (root == null) {
            return;
        }
        try {
            Path dir = activeDir(root);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(DRESSING_LEDGER_FILE), CityJson.GSON.toJson(dressingLedger));
        } catch (IOException ignored) {
            // Worldgen should keep running; the active summary still exposes in-memory progress.
        }
    }

    private static Path activeDir(Path serverRoot) {
        return serverRoot.resolve(ACTIVE_DIR);
    }

    private static JsonArray ensureArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            obj.add(key, new JsonArray());
        }
        return obj.getAsJsonArray(key);
    }

    private static JsonArray array(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject object(JsonObject obj, String key) {
        return obj != null && obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : new JsonObject();
    }

    private static BlockBounds bounds(JsonObject obj) {
        return new BlockBounds(intValue(obj, "minX", 0), intValue(obj, "minZ", 0),
                intValue(obj, "maxX", 0), intValue(obj, "maxZ", 0));
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : fallback;
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }
}
