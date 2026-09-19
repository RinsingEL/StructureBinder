package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** Author-owned wall modules, entirely separate from the ordinary building catalog. */
public final class CityWallModuleConfig {
    private CityWallModuleConfig() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve("geomantia/city_walls");
    }

    public static void ensureDefaults(Path directory) throws IOException {
        Files.createDirectories(directory.resolve("modules"));
        copyMissing(directory.resolve("modules/guard_tower.nbt"), "/data/geomantia/structures/city_walls/guard_tower.nbt");
        copyMissing(directory.resolve("modules/wall_straight.nbt"), "/data/geomantia/structures/city_walls/wall_straight.nbt");
        copyMissing(directory.resolve("modules.json"), "/geomantia/city_walls/modules.json");
    }

    private static void copyMissing(Path target, String resource) throws IOException {
        if (Files.exists(target)) return;
        try (var input = CityWallModuleConfig.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("WALL_DEFAULT_RESOURCE_MISSING:" + resource);
            Files.copy(input, target);
        }
    }

    public static Loaded loadCurrent() throws IOException {
        ensureDefaults(directory());
        return load(directory());
    }

    public static Loaded load(Path directory) throws IOException {
        try {
            byte[] settings = Files.readAllBytes(directory.resolve("modules.json"));
            JsonObject config = JsonParser.parseString(new String(settings, StandardCharsets.UTF_8)).getAsJsonObject();
            fields(config, Set.of("schema", "moduleSet", "connections", "foundationBlock", "modules"));
            if (!"city_wall_modules".equals(config.get("schema").getAsString())
                    || !"guard_tower".equals(config.get("moduleSet").getAsString()))
                throw new IllegalArgumentException("WALL_MODULE_CONFIGURATION_UNSUPPORTED");
            var connections = config.getAsJsonObject("connections");
            fields(connections, Set.of("walkwayFloorY", "passageHeadroom"));
            // D5 geometry currently supports this authored connector layout only.
            if (connections.get("walkwayFloorY").getAsInt() != 9 || connections.get("passageHeadroom").getAsInt() != 2)
                throw new IllegalArgumentException("WALL_CONNECTOR_GEOMETRY_UNSUPPORTED");
            var modules = config.getAsJsonObject("modules");
            fields(modules, Set.of("guardTower", "straightWall"));
            String foundation = config.get("foundationBlock").getAsString();
            if (!foundation.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("WALL_FOUNDATION_BLOCK_INVALID");
            byte[] towerBytes = readModule(directory, modules.get("guardTower").getAsString());
            byte[] wallBytes = readModule(directory, modules.get("straightWall").getAsString());
            CompoundTag tower = readTemplate(towerBytes, 7, 15, 10);
            CompoundTag wall = readTemplate(wallBytes, 16, 12, 5);
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("schema", "city_wall_module_snapshot");
            snapshot.addProperty("settingsSha256", hash(settings));
            snapshot.addProperty("guardTowerSha256", hash(towerBytes));
            snapshot.addProperty("straightWallSha256", hash(wallBytes));
            snapshot.add("configuration", config.deepCopy());
            return new Loaded(config, snapshot, tower, wall, towerBytes, wallBytes, foundation);
        } catch (RuntimeException ex) {
            throw new IOException("WALL_CONFIGURATION_INVALID:" + ex.getMessage(), ex);
        }
    }

    private static void fields(JsonObject object, Set<String> fields) {
        if (object == null || !object.keySet().equals(fields))
            throw new IllegalArgumentException("WALL_CONFIGURATION_FIELDS_INVALID");
    }

    private static byte[] readModule(Path directory, String relative) throws IOException {
        Path root = directory.toRealPath();
        Path requested = Path.of(relative);
        if (requested.isAbsolute()) throw new IOException("WALL_MODULE_PATH_OUTSIDE_CONFIG");
        Path path = root.resolve(requested).normalize();
        if (!path.startsWith(root) || !path.toRealPath().startsWith(root))
            throw new IOException("WALL_MODULE_PATH_OUTSIDE_CONFIG");
        if (!Files.isRegularFile(path) || Files.size(path) > 16 * 1024 * 1024)
            throw new IOException("WALL_MODULE_FILE_INVALID:" + relative);
        return Files.readAllBytes(path);
    }

    private static CompoundTag readTemplate(byte[] bytes, int width, int height, int depth) throws IOException {
        CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes));
        ListTag size = tag.getList("size", Tag.TAG_INT);
        if (size.size() != 3 || size.getInt(0) != width || size.getInt(1) != height || size.getInt(2) != depth)
            throw new IOException("WALL_MODULE_DIMENSIONS_UNSUPPORTED");
        ListTag palette = tag.getList("palette", Tag.TAG_COMPOUND);
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        if (palette.isEmpty() || blocks.size() != width * height * depth || !tag.getList("entities", Tag.TAG_COMPOUND).isEmpty())
            throw new IOException("WALL_MODULE_COMPLETE_BLOCK_GRID_REQUIRED");
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            if (pos.size() != 3 || block.contains("nbt")) throw new IOException("WALL_MODULE_BLOCK_INVALID");
            int x = pos.getInt(0), y = pos.getInt(1), z = pos.getInt(2), state = block.getInt("state");
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth
                    || state < 0 || state >= palette.size() || !seen.add((y * depth + z) * width + x))
                throw new IOException("WALL_MODULE_BLOCK_INVALID");
        }
        return tag;
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public record Loaded(JsonObject configuration, JsonObject snapshot, CompoundTag guardTower,
                         CompoundTag straightWall, byte[] towerBytes, byte[] wallBytes, String foundationBlock) {
        public void freeze(JsonObject plan) {
            plan.add("wallModuleSnapshot", snapshot.deepCopy());
            JsonObject library = com.rinsing.geomantia.systems.city.application.CityWallTemplateCatalog.libraryJson();
            library.addProperty("templateSource", "config/geomantia/city_walls/modules.json");
            library.add("configuration", configuration.deepCopy());
            plan.add("templateLibrary", library);
        }
        public void requireMatches(JsonObject plan) throws IOException {
            if (!plan.has("wallModuleSnapshot") || !snapshot.equals(plan.get("wallModuleSnapshot")))
                throw new IOException("WALL_MODULE_CONFIGURATION_CHANGED_REPLAN_REQUIRED");
        }
    }
}
