package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationContentCatalogLoaderTest {
    @Test
    void loadsBundledFunctionalSettlementPrefabsWithCategoryTags(@TempDir Path temp) throws Exception {
        Path root = CityDecorationDefaultCatalogBootstrap.ensureInstalled(temp.resolve("city_decoration"));

        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader(state -> {
        }).load(root);

        CityDecorationContentCatalog.Content market =
                catalog.requireContent("geomantia:decoration/market_stall_small_01");
        assertEquals(new CityDecorationContentCatalog.Size(3, 3, 3), market.size());
        assertTrue(market.tags().containsAll(java.util.List.of("commercial", "market", "plaza")));
        CityDecorationContentCatalog.Content goods =
                catalog.requireContent("geomantia:decoration/crate_cluster_01");
        assertEquals(new CityDecorationContentCatalog.Size(2, 2, 2), goods.size());
        assertTrue(goods.tags().containsAll(java.util.List.of("commercial", "goods", "storage")));
        CityDecorationContentCatalog.Content notice =
                catalog.requireContent("geomantia:decoration/notice_board_01");
        assertEquals(new CityDecorationContentCatalog.Size(3, 3, 1), notice.size());
        assertTrue(notice.tags().containsAll(java.util.List.of("civic", "notice", "governance", "plaza")));
        assertEquals(0, market.template().getList("entities", 10).size());
        assertEquals(0, goods.template().getList("entities", 10).size());
        assertEquals(0, notice.template().getList("entities", 10).size());
    }

    @Test
    void v4LoadsResolvedCropPlantStateWithoutNbtTemplate(@TempDir Path root) throws Exception {
        writeIndex(root, """
                {
                  "schemaVersion": "city_decoration_content_index.v0.4",
                  "contents": [{
                    "contentId": "city:plant/wheat",
                    "contentKind": "plant",
                    "blockState": {
                      "Name": "minecraft:wheat",
                      "Properties": {"age": "0"}
                    },
                    "allowedRotations": [0, 90, 180, 270]
                  }]
                }
                """);
        AtomicReference<CompoundTag> validated = new AtomicReference<>();

        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader(validated::set).load(root);
        CityDecorationContentCatalog.Content plant = catalog.requireContent("city:plant/wheat");

        assertTrue(plant.plant());
        assertEquals("minecraft:wheat", validated.get().getString("Name"));
        assertEquals("0", plant.plantBlockState().getCompound("Properties").getString("age"));
        assertEquals(new CityDecorationContentCatalog.Size(1, 1, 1), plant.size());
        assertEquals("crop_support", plant.supportMode());
        assertEquals("above_surface", plant.placementMode());
        assertEquals("replaceable_only", plant.replacePolicy());
        assertTrue(plant.contentHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void rejectsLegacyCatalogSchema(@TempDir Path root) throws Exception {
        writeIndex(root, """
                {
                  "schemaVersion": "city_decoration_content_index.v0.3",
                  "contents": [{
                    "contentId": "city:plant/wheat",
                    "contentKind": "plant",
                    "blockState": {"Name": "minecraft:wheat"}
                  }]
                }
                """);

        CityDecorationContentCatalog.CatalogException error = assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationContentCatalogLoader(state -> {
                }).load(root));

        assertEquals("CITY_DECORATION_CONTENT_INDEX_SCHEMA_UNSUPPORTED", error.reasonCode());
    }

    @Test
    void loadsValidIndexAndDerivesDimensionsAndDefaultsFromNbt(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/stall.nbt"), 3, 2, 4, "minecraft:oak_planks", false);
        writeIndex(root, content("city:prefab/stall", "templates/stall.nbt", ""));

        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader().load(root);
        CityDecorationContentCatalog.Content content = catalog.requireContent("city:prefab/stall");

        assertEquals(new CityDecorationContentCatalog.Size(3, 2, 4), content.size());
        assertEquals(new CityDecorationContentCatalog.Envelope(0, 0, 0, 2, 1, 3), content.bodyEnvelope());
        assertEquals(new CityDecorationContentCatalog.Envelope(-1, 0, -1, 3, 1, 4), content.comfortEnvelope());
        assertEquals(java.util.List.of(0), content.allowedRotations());
        assertEquals("full_footprint", content.supportMode());
        assertEquals("above_surface", content.placementMode());
        assertEquals("replaceable_only", content.replacePolicy());
        assertEquals(java.util.List.of("lava", "water"), content.blockedSurfaceTags());
        assertTrue(content.contentHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(catalog.catalogHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void rejectsNbtPathThatEscapesTemplatesDirectory(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("outside.nbt"), 1, 1, 1, "minecraft:stone", false);
        writeIndex(root, content("city:prefab/escape", "templates/../outside.nbt", ""));

        CityDecorationContentCatalog.CatalogException ex = assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationContentCatalogLoader().load(root));
        assertEquals("CITY_DECORATION_NBT_PATH_ESCAPE", ex.reasonCode());
    }

    @Test
    void rejectsMissingNbt(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("templates"));
        writeIndex(root, content("city:prefab/missing", "templates/missing.nbt", ""));

        CityDecorationContentCatalog.CatalogException ex = assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationContentCatalogLoader().load(root));
        assertEquals("CITY_DECORATION_NBT_MISSING", ex.reasonCode());
    }

    @Test
    void rejectsPrefabEntityNbt(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/entity.nbt"), 1, 1, 1, "minecraft:stone", true);
        writeIndex(root, content("city:prefab/entity", "templates/entity.nbt", ""));

        CityDecorationContentCatalog.CatalogException ex = assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationContentCatalogLoader().load(root));
        assertEquals("CITY_DECORATION_PREFAB_ENTITY_NBT_FORBIDDEN", ex.reasonCode());
    }

    @Test
    void changesContentAndCatalogHashesWhenIndexOrNbtChanges(@TempDir Path root) throws Exception {
        Path template = root.resolve("templates/hash.nbt");
        writeTemplate(template, 1, 1, 1, "minecraft:stone", false);
        writeIndex(root, content("city:prefab/hash", "templates/hash.nbt", ""));
        CityDecorationContentCatalog first = new CityDecorationContentCatalogLoader().load(root);

        writeTemplate(template, 1, 1, 1, "minecraft:cobblestone", false);
        CityDecorationContentCatalog nbtChanged = new CityDecorationContentCatalogLoader().load(root);
        assertNotEquals(first.requireContent("city:prefab/hash").contentHash(),
                nbtChanged.requireContent("city:prefab/hash").contentHash());
        assertNotEquals(first.catalogHash(), nbtChanged.catalogHash());

        writeIndex(root, content("city:prefab/hash", "templates/hash.nbt", ",\n      \"tags\": [\"market\"]"));
        CityDecorationContentCatalog indexChanged = new CityDecorationContentCatalogLoader().load(root);
        assertNotEquals(nbtChanged.requireContent("city:prefab/hash").contentHash(),
                indexChanged.requireContent("city:prefab/hash").contentHash());
        assertNotEquals(nbtChanged.catalogHash(), indexChanged.catalogHash());
    }

    @Test
    void rejectsDuplicateAndUnknownContentIds(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/one.nbt"), 1, 1, 1, "minecraft:stone", false);
        String entry = contentEntry("city:prefab/one", "templates/one.nbt", "");
        writeIndex(root, "{\n  \"schemaVersion\": \"city_decoration_content_index.v0.4\",\n"
                + "  \"contents\": [" + entry + "," + entry + "]\n}");

        CityDecorationContentCatalog.CatalogException duplicate = assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationContentCatalogLoader().load(root));
        assertEquals("CITY_DECORATION_CONTENT_ID_DUPLICATE", duplicate.reasonCode());

        writeIndex(root, content("city:prefab/one", "templates/one.nbt", ""));
        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader().load(root);
        CityDecorationContentCatalog.CatalogException unknown = assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> catalog.requireContent("city:prefab/unknown"));
        assertEquals("CITY_DECORATION_CONTENT_UNKNOWN", unknown.reasonCode());
    }

    @Test
    void placementModeMustMatchReplacePolicyAndChangesHash(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/mode.nbt"), 1, 1, 1, "minecraft:stone", false);
        writeIndex(root, content("city:prefab/mode", "templates/mode.nbt", ""));
        CityDecorationContentCatalog above = new CityDecorationContentCatalogLoader().load(root);

        writeIndex(root, content("city:prefab/mode", "templates/mode.nbt",
                ",\n      \"placementMode\": \"replace_surface\",\n"
                        + "      \"replacePolicy\": \"surface_replaceable\""));
        CityDecorationContentCatalog surface = new CityDecorationContentCatalogLoader().load(root);
        assertEquals("replace_surface", surface.requireContent("city:prefab/mode").placementMode());
        assertNotEquals(above.requireContent("city:prefab/mode").contentHash(),
                surface.requireContent("city:prefab/mode").contentHash());
        assertNotEquals(above.catalogHash(), surface.catalogHash());

        writeIndex(root, content("city:prefab/mode", "templates/mode.nbt",
                ",\n      \"placementMode\": \"replace_surface\""));
        CityDecorationContentCatalog.CatalogException mismatch = assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationContentCatalogLoader().load(root));
        assertEquals("CITY_DECORATION_PLACEMENT_REPLACE_POLICY_MISMATCH", mismatch.reasonCode());
    }

    @Test
    void v4HashesExplicitPlacementPose(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/channel.nbt"), 1, 3, 1, "minecraft:water", false);
        writeIndex(root, content("city:prefab/channel", "templates/channel.nbt", """
                ,
                      "placementMode": "embed_surface",
                      "replacePolicy": "surface_replaceable",
                      "groundPlaneLocalY": 1,
                      "embedDepthBlocks": 2,
                      "clearanceMode": "clear_template_air"
                """));

        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader().load(root);
        CityDecorationContentCatalog.Content content = catalog.requireContent("city:prefab/channel");
        assertEquals(CityDecorationContentCatalog.SCHEMA, catalog.schemaVersion());
        assertEquals(1, content.groundPlaneLocalY());
        assertEquals(2, content.embedDepthBlocks());
        assertEquals("clear_template_air", content.clearanceMode());

        String firstHash = content.contentHash();
        writeIndex(root, content("city:prefab/channel", "templates/channel.nbt", """
                ,
                      "placementMode": "embed_surface",
                      "replacePolicy": "surface_replaceable",
                      "groundPlaneLocalY": 1,
                      "embedDepthBlocks": 1,
                      "clearanceMode": "clear_template_air"
                """));
        assertNotEquals(firstHash, new CityDecorationContentCatalogLoader().load(root)
                .requireContent("city:prefab/channel").contentHash());
    }

    @Test
    void v4RejectsInvalidPoseFields(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/channel.nbt"), 1, 2, 1, "minecraft:water", false);
        writeIndex(root, """
                {
                  "schemaVersion": "city_decoration_content_index.v0.4",
                  "contents": [{
                    "contentId": "city:prefab/channel",
                    "contentKind": "prefab",
                    "nbtFile": "templates/channel.nbt"
                  }]
                }
                """);
        writeIndex(root, content("city:prefab/channel", "templates/channel.nbt", """
                ,
                      "placementMode": "embed_surface",
                      "replacePolicy": "surface_replaceable",
                      "groundPlaneLocalY": 2,
                      "embedDepthBlocks": 0,
                      "clearanceMode": "unknown"
                """));
        assertEquals("CITY_DECORATION_GROUND_PLANE_INVALID", assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationContentCatalogLoader().load(root)).reasonCode());
    }

    @Test
    void v2CatalogIsRejected(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/legacy.nbt"), 1, 1, 1, "minecraft:stone", false);
        writeIndex(root, """
                {
                  "schemaVersion": "city_decoration_content_index.v0.2",
                  "contents": []
                }
                """);

        CityDecorationContentCatalog.CatalogException error = assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationContentCatalogLoader().load(root));
        assertEquals("CITY_DECORATION_CONTENT_INDEX_SCHEMA_UNSUPPORTED", error.reasonCode());
    }

    @Test
    void styleProfileMapsSemanticRefsToCatalogContentAndRejectsUnknownContent(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/stall.nbt"), 1, 1, 1, "minecraft:oak_planks", false);
        writeIndex(root, content("city:prefab/stall", "templates/stall.nbt", ""));
        CityDecorationContentCatalog contentCatalog = new CityDecorationContentCatalogLoader().load(root);
        Path styles = root.resolve("styles");
        Files.createDirectories(styles);
        Files.writeString(styles.resolve("forest_village.json"), """
                {
                  "schemaVersion": "city_decoration_style_profile.v0.1",
                  "styleProfileId": "forest_village",
                  "mappings": [
                    {
                      "semanticRef": "market_stall",
                      "variants": [
                        {"contentRef": "city:prefab/stall", "weight": 2.0}
                      ]
                    }
                  ]
                }
                """);

        CityDecorationStyleProfileCatalog.StyleProfile profile = new CityDecorationStyleProfileCatalogLoader()
                .load(root, contentCatalog).requireProfile("forest_village");
        assertTrue(profile.styleProfileHash().matches("sha256:[0-9a-f]{64}"));
        assertEquals("city:prefab/stall", profile.requireMapping("market_stall").variants().get(0).contentRef());

        Files.writeString(styles.resolve("forest_village.json"), """
                {
                  "schemaVersion": "city_decoration_style_profile.v0.1",
                  "styleProfileId": "forest_village",
                  "mappings": [
                    {
                      "semanticRef": "market_stall",
                      "variants": [
                        {"contentRef": "city:prefab/missing", "weight": 1.0}
                      ]
                    }
                  ]
                }
                """);
        CityDecorationContentCatalog.CatalogException unknown = assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationStyleProfileCatalogLoader().load(root, contentCatalog));
        assertEquals("CITY_DECORATION_CONTENT_UNKNOWN", unknown.reasonCode());
    }

    @Test
    void validatesTerrainDropFallbackReferenceAndPlacementCompatibility(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/crop.nbt"), 1, 1, 1, "minecraft:wheat", false);
        writeTemplate(root.resolve("templates/channel.nbt"), 1, 1, 1, "minecraft:water", false);
        writeIndex(root, """
                {
                  "schemaVersion": "city_decoration_content_index.v0.4",
                  "contents": [
                    {
                      "contentId": "city:prefab/crop",
                      "contentKind": "prefab",
                      "nbtFile": "templates/crop.nbt",
                      "groundPlaneLocalY": 0,
                      "embedDepthBlocks": 0,
                      "clearanceMode": "preserve",
                      "placementMode": "replace_surface",
                      "replacePolicy": "surface_replaceable"
                    },
                    {
                      "contentId": "city:prefab/channel",
                      "contentKind": "prefab",
                      "nbtFile": "templates/channel.nbt",
                      "groundPlaneLocalY": 0,
                      "embedDepthBlocks": 0,
                      "clearanceMode": "preserve",
                      "placementMode": "replace_surface",
                      "replacePolicy": "surface_replaceable",
                      "terrainDropFallbackContentRef": "city:prefab/crop"
                    }
                  ]
                }
                """);

        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader().load(root);
        assertEquals("city:prefab/crop",
                catalog.requireContent("city:prefab/channel").terrainDropFallbackContentRef());

        writeTemplate(root.resolve("templates/lined_straight.nbt"), 3, 2, 1,
                "minecraft:water", false);
        writeTemplate(root.resolve("templates/lined_endcap.nbt"), 3, 2, 2,
                "minecraft:oak_slab", false);
        writeIndex(root, """
                {
                  "schemaVersion": "city_decoration_content_index.v0.4",
                  "contents": [
                    {
                      "contentId": "city:prefab/lined_straight",
                      "contentKind": "prefab",
                      "nbtFile": "templates/lined_straight.nbt",
                      "groundPlaneLocalY": 0,
                      "embedDepthBlocks": 0,
                      "clearanceMode": "preserve",
                      "placementMode": "replace_surface",
                      "replacePolicy": "surface_replaceable",
                      "terrainDropFallbackContentRef": "city:prefab/lined_endcap"
                    },
                    {
                      "contentId": "city:prefab/lined_endcap",
                      "contentKind": "prefab",
                      "nbtFile": "templates/lined_endcap.nbt",
                      "groundPlaneLocalY": 0,
                      "embedDepthBlocks": 0,
                      "clearanceMode": "preserve",
                      "placementMode": "replace_surface",
                      "replacePolicy": "surface_replaceable"
                    }
                  ]
                }
                """);
        CityDecorationContentCatalog lined = new CityDecorationContentCatalogLoader().load(root);
        assertEquals("city:prefab/lined_endcap",
                lined.requireContent("city:prefab/lined_straight").terrainDropFallbackContentRef());

        writeIndex(root, """
                {
                  "schemaVersion": "city_decoration_content_index.v0.4",
                  "contents": [
                    {
                      "contentId": "city:prefab/channel",
                      "contentKind": "prefab",
                      "nbtFile": "templates/channel.nbt",
                      "groundPlaneLocalY": 0,
                      "embedDepthBlocks": 0,
                      "clearanceMode": "preserve",
                      "terrainDropFallbackContentRef": "city:prefab/missing"
                    }
                  ]
                }
                """);
        CityDecorationContentCatalog.CatalogException unknown = assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationContentCatalogLoader().load(root));
        assertEquals("CITY_DECORATION_TERRAIN_FALLBACK_UNKNOWN", unknown.reasonCode());

        writeIndex(root, """
                {
                  "schemaVersion": "city_decoration_content_index.v0.4",
                  "contents": [
                    {
                      "contentId": "city:prefab/crop",
                      "contentKind": "prefab",
                      "nbtFile": "templates/crop.nbt"
                      ,"groundPlaneLocalY": 0,
                      "embedDepthBlocks": 0,
                      "clearanceMode": "preserve"
                    },
                    {
                      "contentId": "city:prefab/channel",
                      "contentKind": "prefab",
                      "nbtFile": "templates/channel.nbt",
                      "groundPlaneLocalY": 0,
                      "embedDepthBlocks": 0,
                      "clearanceMode": "preserve",
                      "placementMode": "replace_surface",
                      "replacePolicy": "surface_replaceable",
                      "terrainDropFallbackContentRef": "city:prefab/crop"
                    }
                  ]
                }
                """);
        CityDecorationContentCatalog.CatalogException incompatible = assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationContentCatalogLoader().load(root));
        assertEquals("CITY_DECORATION_TERRAIN_FALLBACK_PLACEMENT_MISMATCH", incompatible.reasonCode());
    }

    private static String content(String contentId, String nbtFile, String extraFields) {
        return "{\n  \"schemaVersion\": \"city_decoration_content_index.v0.4\",\n"
                + "  \"contents\": [" + contentEntry(contentId, nbtFile, extraFields) + "]\n}";
    }

    private static String contentEntry(String contentId, String nbtFile, String extraFields) {
        String requiredPose = extraFields.contains("\"groundPlaneLocalY\"") ? "" : """
                ,
                  "groundPlaneLocalY": 0,
                  "embedDepthBlocks": 0,
                  "clearanceMode": "preserve"
                """;
        return """
                {
                  "contentId": "%s",
                  "contentKind": "prefab",
                  "nbtFile": "%s"%s%s
                }
                """.formatted(contentId, nbtFile, requiredPose, extraFields);
    }

    private static void writeIndex(Path root, String json) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("content_index.json"), json);
    }

    private static void writeTemplate(Path path, int width, int height, int depth,
                                      String blockName, boolean withEntity) throws Exception {
        Files.createDirectories(path.getParent());
        CompoundTag root = new CompoundTag();
        root.put("size", ints(width, height, depth));

        CompoundTag state = new CompoundTag();
        state.putString("Name", blockName);
        ListTag palette = new ListTag();
        palette.add(state);
        root.put("palette", palette);

        CompoundTag block = new CompoundTag();
        block.put("pos", ints(0, 0, 0));
        block.putInt("state", 0);
        ListTag blocks = new ListTag();
        blocks.add(block);
        root.put("blocks", blocks);

        ListTag entities = new ListTag();
        if (withEntity) {
            entities.add(new CompoundTag());
        }
        root.put("entities", entities);
        NbtIo.writeCompressed(root, path.toFile());
    }

    private static ListTag ints(int first, int second, int third) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(first));
        list.add(IntTag.valueOf(second));
        list.add(IntTag.valueOf(third));
        return list;
    }
}
