package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

/** Installs the versioned starter catalog into Forge config exactly once; runtime always reads that config copy. */
public final class CityDecorationDefaultCatalogBootstrap {
    private static final String RESOURCE_ROOT = "/geomantia/default_config/city_decoration/";
    private static final String CONTENT_INDEX = "content_index.json";
    private static final String STYLE_PROFILE = "styles/medieval_coastal.json";
    private static final String MANIFEST = "bootstrap_manifest.json";
    private static final String MANAGED_SOURCE = "geomantia:default_config/city_decoration";
    private static final String MANIFEST_SCHEMA = "city_decoration_default_bootstrap.v0.2";
    private static final String DEFAULT_CATALOG_REVISION = "terrain_drop_fallback.v0.1";
    private static final String WATER_CHANNEL_ID = "geomantia:decoration/water_channel_tile";
    private static final String TERRAIN_DROP_FALLBACK = "terrainDropFallbackContentRef";
    private static final String UPGRADE_BACKUP = "upgrades/content_index.before-terrain-drop-fallback.json";

    private CityDecorationDefaultCatalogBootstrap() {
    }

    public static synchronized Path ensureInstalled(Path catalogRoot) throws IOException {
        if (catalogRoot == null) {
            throw new IOException("City decoration catalog root is required.");
        }
        Path root = catalogRoot.toAbsolutePath().normalize();
        if (Files.exists(root)) {
            return root;
        }
        Path parent = root.getParent();
        if (parent == null) {
            throw new IOException("City decoration catalog root has no parent: " + root);
        }
        Files.createDirectories(parent);
        Path staging = parent.resolve("." + root.getFileName() + ".bootstrap");
        if (Files.exists(staging)) {
            throw new IOException("City decoration bootstrap staging directory already exists: " + staging);
        }
        Files.createDirectories(staging.resolve("templates"));
        Files.createDirectories(staging.resolve("styles"));
        copyResource(CONTENT_INDEX, staging.resolve(CONTENT_INDEX));
        copyResource(STYLE_PROFILE, staging.resolve(STYLE_PROFILE));
        writeTemplate(staging.resolve("templates/crop_tile.nbt"), "minecraft:farmland", "minecraft:wheat");
        writeTemplate(staging.resolve("templates/water_channel_tile.nbt"), "minecraft:water");
        writeTemplate(staging.resolve("templates/field_border.nbt"), "minecraft:oak_fence");
        writeTemplate(staging.resolve("templates/gravel_path_tile.nbt"), "minecraft:gravel");
        Files.writeString(staging.resolve(MANIFEST), """
                {
                  "schemaVersion": "%s",
                  "source": "%s",
                  "defaultCatalogRevision": "%s",
                  "installedAt": "%s"
                }
                """.formatted(MANIFEST_SCHEMA, MANAGED_SOURCE, DEFAULT_CATALOG_REVISION, Instant.now()),
                StandardCharsets.UTF_8);
        return moveIntoPlace(staging, root);
    }

    /**
     * Explicitly upgrades an old, managed default catalog without treating arbitrary user config as managed data.
     * A legacy water-channel entry is changed only when it otherwise exactly matches the packaged default.
     */
    public static synchronized UpgradeResult upgradeManagedDefault(Path catalogRoot) throws IOException {
        Path root = existingRoot(catalogRoot);
        Path manifestPath = root.resolve(MANIFEST);
        JsonObject manifest = readObject(manifestPath, "CITY_DECORATION_DEFAULT_CATALOG_UPGRADE_MANIFEST_REQUIRED");
        if (!MANAGED_SOURCE.equals(string(manifest, "source"))) {
            throw new IllegalArgumentException("CITY_DECORATION_DEFAULT_CATALOG_UPGRADE_UNSAFE: "
                    + "catalog is not a managed Geomantia default.");
        }

        Path indexPath = root.resolve(CONTENT_INDEX);
        JsonObject index = readObject(indexPath, "CITY_DECORATION_DEFAULT_CATALOG_UPGRADE_INDEX_REQUIRED");
        JsonObject waterChannel = content(index, WATER_CHANNEL_ID,
                "CITY_DECORATION_DEFAULT_CATALOG_UPGRADE_WATER_CHANNEL_REQUIRED");
        JsonObject expectedWaterChannel = content(packagedIndex(), WATER_CHANNEL_ID,
                "CITY_DECORATION_DEFAULT_CATALOG_PACKAGED_WATER_CHANNEL_REQUIRED");
        String expectedFallback = string(expectedWaterChannel, TERRAIN_DROP_FALLBACK);

        boolean indexChanged = false;
        if (!waterChannel.has(TERRAIN_DROP_FALLBACK)) {
            JsonObject legacyWaterChannel = waterChannel.deepCopy();
            JsonObject expectedLegacyWaterChannel = expectedWaterChannel.deepCopy();
            expectedLegacyWaterChannel.remove(TERRAIN_DROP_FALLBACK);
            if (!legacyWaterChannel.equals(expectedLegacyWaterChannel)) {
                throw new IllegalArgumentException("CITY_DECORATION_DEFAULT_CATALOG_UPGRADE_UNSAFE: "
                        + "water_channel_tile differs from the legacy packaged default.");
            }
            Path backup = root.resolve(UPGRADE_BACKUP);
            if (!Files.exists(backup)) {
                Files.createDirectories(backup.getParent());
                Files.copy(indexPath, backup);
            }
            waterChannel.addProperty(TERRAIN_DROP_FALLBACK, expectedFallback);
            writeObject(indexPath, index);
            indexChanged = true;
        } else if (!expectedFallback.equals(string(waterChannel, TERRAIN_DROP_FALLBACK))) {
            throw new IllegalArgumentException("CITY_DECORATION_DEFAULT_CATALOG_UPGRADE_UNSAFE: "
                    + "water_channel_tile already declares a different terrain drop fallback.");
        }

        boolean manifestChanged = !MANIFEST_SCHEMA.equals(string(manifest, "schemaVersion"))
                || !DEFAULT_CATALOG_REVISION.equals(string(manifest, "defaultCatalogRevision"));
        if (manifestChanged) {
            manifest.addProperty("schemaVersion", MANIFEST_SCHEMA);
            manifest.addProperty("defaultCatalogRevision", DEFAULT_CATALOG_REVISION);
            manifest.addProperty("upgradedAt", Instant.now().toString());
            writeObject(manifestPath, manifest);
        }
        return new UpgradeResult(root, indexChanged, manifestChanged,
                indexChanged ? root.resolve(UPGRADE_BACKUP) : null);
    }

    public record UpgradeResult(Path catalogRoot,
                                boolean contentIndexChanged,
                                boolean manifestChanged,
                                Path backupPath) {
    }

    private static Path moveIntoPlace(Path staging, Path root) throws IOException {
        try {
            return Files.move(staging, root, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            try {
                return Files.move(staging, root);
            } catch (FileAlreadyExistsException raced) {
                return root;
            }
        } catch (FileAlreadyExistsException raced) {
            return root;
        }
    }

    private static Path existingRoot(Path catalogRoot) throws IOException {
        if (catalogRoot == null) {
            throw new IOException("City decoration catalog root is required.");
        }
        Path root = catalogRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("CITY_DECORATION_DEFAULT_CATALOG_UPGRADE_ROOT_INVALID: " + root);
        }
        return root;
    }

    private static JsonObject packagedIndex() throws IOException {
        try (InputStream input = CityDecorationDefaultCatalogBootstrap.class
                .getResourceAsStream(RESOURCE_ROOT + CONTENT_INDEX)) {
            if (input == null) {
                throw new IOException("Missing packaged default catalog resource: " + CONTENT_INDEX);
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject content(JsonObject index, String contentId, String failureCode) {
        JsonElement entries = index.get("contents");
        if (entries == null || !entries.isJsonArray()) {
            throw new IllegalArgumentException(failureCode + ": contents array is required.");
        }
        for (JsonElement entry : entries.getAsJsonArray()) {
            if (entry.isJsonObject() && contentId.equals(string(entry.getAsJsonObject(), "contentId"))) {
                return entry.getAsJsonObject();
            }
        }
        throw new IllegalArgumentException(failureCode + ": " + contentId);
    }

    private static JsonObject readObject(Path path, String failureCode) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(failureCode + ": " + path);
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException(failureCode + ": object required.");
            }
            return parsed.getAsJsonObject();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(failureCode + ": " + path, ex);
        }
    }

    private static void writeObject(Path target, JsonObject object) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("City decoration config file has no parent: " + target);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, object.toString() + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static void copyResource(String relativePath, Path target) throws IOException {
        try (InputStream input = CityDecorationDefaultCatalogBootstrap.class
                .getResourceAsStream(RESOURCE_ROOT + relativePath)) {
            if (input == null) {
                throw new IOException("Missing packaged default catalog resource: " + relativePath);
            }
            Files.copy(input, target);
        }
    }

    private static void writeTemplate(Path target, String... blockIds) throws IOException {
        CompoundTag root = new CompoundTag();
        root.put("size", ints(1, blockIds.length, 1));
        ListTag palette = new ListTag();
        for (String blockId : blockIds) {
            CompoundTag state = new CompoundTag();
            state.putString("Name", blockId);
            palette.add(state);
        }
        root.put("palette", palette);
        ListTag blocks = new ListTag();
        for (int y = 0; y < blockIds.length; y++) {
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(0, y, 0));
            block.putInt("state", y);
            blocks.add(block);
        }
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        NbtIo.writeCompressed(root, target.toFile());
    }

    private static ListTag ints(int first, int second, int third) {
        ListTag values = new ListTag();
        for (int value : List.of(first, second, third)) {
            values.add(IntTag.valueOf(value));
        }
        return values;
    }
}
