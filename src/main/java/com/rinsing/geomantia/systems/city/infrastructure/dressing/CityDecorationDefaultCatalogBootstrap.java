package com.rinsing.geomantia.systems.city.infrastructure.dressing;

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
                  "schemaVersion": "city_decoration_default_bootstrap.v0.1",
                  "source": "geomantia:default_config/city_decoration",
                  "installedAt": "%s"
                }
                """.formatted(Instant.now()), StandardCharsets.UTF_8);
        return moveIntoPlace(staging, root);
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
