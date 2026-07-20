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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installs the versioned starter catalog into Forge config exactly once; runtime always reads that config copy. */
public final class CityDecorationDefaultCatalogBootstrap {
    private static final String RESOURCE_ROOT = "/geomantia/default_config/city_decoration/";
    private static final String CONTENT_INDEX = "content_index.json";
    private static final String STYLE_PROFILE = "styles/medieval_coastal.json";
    private static final String MANIFEST = "bootstrap_manifest.json";
    private static final String MANAGED_SOURCE = "geomantia:default_config/city_decoration";
    private static final String MANIFEST_SCHEMA = "city_decoration_default_bootstrap.v0.4";
    private static final String DEFAULT_CATALOG_REVISION = "functional_settlement_fountain.v0.4";

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
        writeTemplate(staging.resolve("templates/farmland_tile.nbt"), "minecraft:farmland");
        writeTemplate(staging.resolve("templates/water_channel_tile.nbt"), "minecraft:water");
        writeTemplate(staging.resolve("templates/field_border.nbt"), "minecraft:oak_fence");
        writeTemplate(staging.resolve("templates/gravel_path_tile.nbt"), "minecraft:gravel");
        writeTemplate(staging.resolve("templates/scarecrow_02.nbt"), scarecrow());
        writeTemplate(staging.resolve("templates/haystack_01.nbt"), haystack());
        writeTemplate(staging.resolve("templates/farm_tool_rack_01.nbt"), farmToolRack());
        writeTemplate(staging.resolve("templates/market_stall_small_01.nbt"), marketStall());
        writeTemplate(staging.resolve("templates/crate_cluster_01.nbt"), crateCluster());
        writeTemplate(staging.resolve("templates/barrel_cluster_01.nbt"), barrelCluster());
        writeTemplate(staging.resolve("templates/shop_sign_01.nbt"), shopSign());
        writeTemplate(staging.resolve("templates/street_bench_01.nbt"), streetBench());
        writeTemplate(staging.resolve("templates/lantern_post_01.nbt"), lanternPost());
        writeTemplate(staging.resolve("templates/notice_board_01.nbt"), noticeBoard());
        writeTemplate(staging.resolve("templates/banner_post_01.nbt"), bannerPost());
        writeTemplate(staging.resolve("templates/fountain_01.nbt"), fountain());
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
        TemplateBuilder builder = new TemplateBuilder(1, blockIds.length, 1);
        for (int y = 0; y < blockIds.length; y++) {
            builder.block(0, y, 0, blockIds[y]);
        }
        writeTemplate(target, builder.build());
    }

    private static void writeTemplate(Path target, TemplateSpec template) throws IOException {
        CompoundTag root = new CompoundTag();
        root.put("size", ints(template.width(), template.height(), template.depth()));
        ListTag palette = new ListTag();
        Map<String, Integer> paletteIndexes = new LinkedHashMap<>();
        ListTag blocks = new ListTag();
        for (TemplateBlock templateBlock : template.blocks()) {
            int stateIndex = paletteIndexes.computeIfAbsent(templateBlock.blockId(), blockId -> {
                CompoundTag state = new CompoundTag();
                state.putString("Name", blockId);
                palette.add(state);
                return palette.size() - 1;
            });
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(templateBlock.x(), templateBlock.y(), templateBlock.z()));
            block.putInt("state", stateIndex);
            blocks.add(block);
        }
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        NbtIo.writeCompressed(root, target.toFile());
    }

    private static TemplateSpec scarecrow() {
        return new TemplateBuilder(3, 3, 1)
                .block(1, 0, 0, "minecraft:oak_fence")
                .block(1, 1, 0, "minecraft:oak_fence")
                .block(0, 1, 0, "minecraft:oak_fence")
                .block(2, 1, 0, "minecraft:oak_fence")
                .block(1, 2, 0, "minecraft:carved_pumpkin")
                .build();
    }

    private static TemplateSpec haystack() {
        return new TemplateBuilder(3, 2, 2)
                .fill(0, 0, 0, 2, 0, 1, "minecraft:hay_block")
                .fill(1, 1, 0, 1, 1, 1, "minecraft:hay_block")
                .build();
    }

    private static TemplateSpec farmToolRack() {
        return new TemplateBuilder(3, 2, 1)
                .block(0, 0, 0, "minecraft:oak_fence")
                .block(2, 0, 0, "minecraft:oak_fence")
                .block(1, 0, 0, "minecraft:iron_bars")
                .fill(0, 1, 0, 2, 1, 0, "minecraft:stripped_oak_log")
                .build();
    }

    private static TemplateSpec marketStall() {
        return new TemplateBuilder(3, 3, 3)
                .fill(0, 0, 0, 0, 1, 0, "minecraft:oak_fence")
                .fill(2, 0, 0, 2, 1, 0, "minecraft:oak_fence")
                .fill(0, 0, 2, 0, 1, 2, "minecraft:oak_fence")
                .fill(2, 0, 2, 2, 1, 2, "minecraft:oak_fence")
                .block(1, 0, 0, "minecraft:oak_planks")
                .fill(0, 2, 0, 2, 2, 2, "minecraft:red_wool")
                .build();
    }

    private static TemplateSpec crateCluster() {
        return new TemplateBuilder(2, 2, 2)
                .fill(0, 0, 0, 1, 0, 1, "minecraft:oak_planks")
                .block(0, 1, 0, "minecraft:stripped_oak_log")
                .build();
    }

    private static TemplateSpec barrelCluster() {
        return new TemplateBuilder(2, 2, 2)
                .fill(0, 0, 0, 1, 0, 1, "minecraft:barrel")
                .block(1, 1, 1, "minecraft:barrel")
                .build();
    }

    private static TemplateSpec shopSign() {
        return new TemplateBuilder(1, 3, 1)
                .fill(0, 0, 0, 0, 1, 0, "minecraft:oak_fence")
                .block(0, 2, 0, "minecraft:oak_planks")
                .build();
    }

    private static TemplateSpec streetBench() {
        return new TemplateBuilder(3, 1, 1)
                .fill(0, 0, 0, 2, 0, 0, "minecraft:oak_stairs")
                .build();
    }

    private static TemplateSpec lanternPost() {
        return new TemplateBuilder(1, 4, 1)
                .fill(0, 0, 0, 0, 2, 0, "minecraft:oak_fence")
                .block(0, 3, 0, "minecraft:lantern")
                .build();
    }

    private static TemplateSpec noticeBoard() {
        return new TemplateBuilder(3, 3, 1)
                .fill(0, 0, 0, 0, 1, 0, "minecraft:oak_fence")
                .fill(2, 0, 0, 2, 1, 0, "minecraft:oak_fence")
                .fill(0, 2, 0, 2, 2, 0, "minecraft:oak_planks")
                .build();
    }

    private static TemplateSpec bannerPost() {
        return new TemplateBuilder(2, 4, 1)
                .fill(0, 0, 0, 0, 3, 0, "minecraft:oak_fence")
                .fill(1, 2, 0, 1, 3, 0, "minecraft:blue_wool")
                .build();
    }

    private static TemplateSpec fountain() {
        return new TemplateBuilder(5, 5, 5)
                .fill(0, 0, 0, 4, 0, 4, "minecraft:stone_bricks")
                .fill(0, 1, 0, 4, 1, 0, "minecraft:polished_andesite")
                .fill(0, 1, 4, 4, 1, 4, "minecraft:polished_andesite")
                .fill(0, 1, 1, 0, 1, 3, "minecraft:polished_andesite")
                .fill(4, 1, 1, 4, 1, 3, "minecraft:polished_andesite")
                .fill(1, 1, 1, 3, 1, 1, "minecraft:water")
                .block(1, 1, 2, "minecraft:water")
                .block(3, 1, 2, "minecraft:water")
                .fill(1, 1, 3, 3, 1, 3, "minecraft:water")
                .fill(2, 1, 2, 2, 3, 2, "minecraft:chiseled_stone_bricks")
                .block(2, 4, 2, "minecraft:water")
                .build();
    }

    private static ListTag ints(int first, int second, int third) {
        ListTag values = new ListTag();
        for (int value : List.of(first, second, third)) {
            values.add(IntTag.valueOf(value));
        }
        return values;
    }

    private record TemplateSpec(int width, int height, int depth, List<TemplateBlock> blocks) {
        private TemplateSpec {
            blocks = List.copyOf(blocks);
        }
    }

    private record TemplateBlock(int x, int y, int z, String blockId) {
    }

    private static final class TemplateBuilder {
        private final int width;
        private final int height;
        private final int depth;
        private final List<TemplateBlock> blocks = new ArrayList<>();

        private TemplateBuilder(int width, int height, int depth) {
            this.width = width;
            this.height = height;
            this.depth = depth;
        }

        private TemplateBuilder block(int x, int y, int z, String blockId) {
            blocks.add(new TemplateBlock(x, y, z, blockId));
            return this;
        }

        private TemplateBuilder fill(int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ,
                                     String blockId) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        block(x, y, z, blockId);
                    }
                }
            }
            return this;
        }

        private TemplateSpec build() {
            return new TemplateSpec(width, height, depth, blocks);
        }
    }
}
