package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationContentCatalogLoaderTest {
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
        writeIndex(root, "{\n  \"schemaVersion\": \"city_decoration_content_index.v0.2\",\n"
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
    void v3RequiresAndHashesExplicitPlacementPose(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/channel.nbt"), 1, 3, 1, "minecraft:water", false);
        writeIndex(root, v3Content("city:prefab/channel", "templates/channel.nbt", """
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
        assertFalse(catalog.legacySchema());
        assertEquals(1, content.groundPlaneLocalY());
        assertEquals(2, content.embedDepthBlocks());
        assertEquals("clear_template_air", content.clearanceMode());

        String firstHash = content.contentHash();
        writeIndex(root, v3Content("city:prefab/channel", "templates/channel.nbt", """
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
    void v3RejectsMissingOrInvalidPoseFields(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/channel.nbt"), 1, 2, 1, "minecraft:water", false);
        writeIndex(root, """
                {
                  "schemaVersion": "city_decoration_content_index.v0.3",
                  "contents": [{
                    "contentId": "city:prefab/channel",
                    "contentKind": "prefab",
                    "nbtFile": "templates/channel.nbt"
                  }]
                }
                """);
        assertEquals("CITY_DECORATION_CONTENT_FIELD_MISSING", assertThrows(
                CityDecorationContentCatalog.CatalogException.class,
                () -> new CityDecorationContentCatalogLoader().load(root)).reasonCode());

        writeIndex(root, v3Content("city:prefab/channel", "templates/channel.nbt", """
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
    void v2CatalogRemainsReadOnlyCompatibleWithLegacyPoseDefaults(@TempDir Path root) throws Exception {
        writeTemplate(root.resolve("templates/legacy.nbt"), 1, 1, 1, "minecraft:stone", false);
        writeIndex(root, content("city:prefab/legacy", "templates/legacy.nbt", ""));

        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader().load(root);
        CityDecorationContentCatalog.Content content = catalog.requireContent("city:prefab/legacy");
        assertEquals(CityDecorationContentCatalog.LEGACY_SCHEMA, catalog.schemaVersion());
        assertTrue(catalog.legacySchema());
        assertEquals(0, content.groundPlaneLocalY());
        assertEquals(0, content.embedDepthBlocks());
        assertEquals("preserve", content.clearanceMode());
        assertTrue(Files.readString(root.resolve("content_index.json"))
                .contains("city_decoration_content_index.v0.2"));
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
                  "schemaVersion": "city_decoration_content_index.v0.2",
                  "contents": [
                    {
                      "contentId": "city:prefab/crop",
                      "contentKind": "prefab",
                      "nbtFile": "templates/crop.nbt",
                      "placementMode": "replace_surface",
                      "replacePolicy": "surface_replaceable"
                    },
                    {
                      "contentId": "city:prefab/channel",
                      "contentKind": "prefab",
                      "nbtFile": "templates/channel.nbt",
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

        writeIndex(root, """
                {
                  "schemaVersion": "city_decoration_content_index.v0.2",
                  "contents": [
                    {
                      "contentId": "city:prefab/channel",
                      "contentKind": "prefab",
                      "nbtFile": "templates/channel.nbt",
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
                  "schemaVersion": "city_decoration_content_index.v0.2",
                  "contents": [
                    {
                      "contentId": "city:prefab/crop",
                      "contentKind": "prefab",
                      "nbtFile": "templates/crop.nbt"
                    },
                    {
                      "contentId": "city:prefab/channel",
                      "contentKind": "prefab",
                      "nbtFile": "templates/channel.nbt",
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
        return "{\n  \"schemaVersion\": \"city_decoration_content_index.v0.2\",\n"
                + "  \"contents\": [" + contentEntry(contentId, nbtFile, extraFields) + "]\n}";
    }

    private static String v3Content(String contentId, String nbtFile, String extraFields) {
        return "{\n  \"schemaVersion\": \"city_decoration_content_index.v0.3\",\n"
                + "  \"contents\": [" + contentEntry(contentId, nbtFile, extraFields) + "]\n}";
    }

    private static String contentEntry(String contentId, String nbtFile, String extraFields) {
        return """
                {
                  "contentId": "%s",
                  "contentKind": "prefab",
                  "nbtFile": "%s"%s
                }
                """.formatted(contentId, nbtFile, extraFields);
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
