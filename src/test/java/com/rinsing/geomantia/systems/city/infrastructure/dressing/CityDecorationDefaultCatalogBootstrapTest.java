package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationDefaultCatalogBootstrapTest {
    @Test
    void installsFunctionalSettlementCatalogIntoMissingConfigRoot(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("config/geomantia/city_decoration");

        Path installed = CityDecorationDefaultCatalogBootstrap.ensureInstalled(root);
        CityDecorationContentCatalog catalog = testLoader().load(installed);
        CityDecorationStyleProfileCatalog styles = new CityDecorationStyleProfileCatalogLoader().load(installed, catalog);
        CityDecorationStyleProfileCatalog.StyleProfile profile = styles.requireProfile("medieval_coastal");

        assertEquals(root.toAbsolutePath().normalize(), installed);
        assertTrue(Files.isRegularFile(root.resolve("content_index.json")));
        assertTrue(Files.isRegularFile(root.resolve("bootstrap_manifest.json")));
        for (String semanticRef : List.of("crop_tile", "farmland_tile", "water_channel_tile",
                "water_channel_lined_endcap", "water_channel_lined_straight",
                "field_border", "gravel_path_tile")) {
            String contentRef = profile.requireMapping(semanticRef).variants().get(0).contentRef();
            assertTrue(catalog.contents().containsKey(contentRef), semanticRef);
            assertTrue(Files.isRegularFile(catalog.requireContent(contentRef).nbtPath()), semanticRef);
        }
        CityDecorationContentCatalog.Content wheat = catalog.requireContent(
                profile.requireMapping("wheat_seed").variants().get(0).contentRef());
        assertTrue(wheat.plant());
        assertEquals("minecraft:wheat", wheat.plantBlockState().getString("Name"));
        assertEquals("0", wheat.plantBlockState().getCompound("Properties").getString("age"));
        assertEquals("crop_support", wheat.supportMode());
        assertEquals(1, wheat.size().widthBlocks());

        CityDecorationContentCatalog.Content farmland = catalog.requireContent(
                "geomantia:decoration/farmland_tile");
        assertEquals(1, farmland.template().getList("blocks", 10).size());
        assertEquals("minecraft:farmland",
                farmland.template().getList("palette", 10).getCompound(0).getString("Name"));
        CityDecorationContentCatalog.Content crop = catalog.requireContent("geomantia:decoration/crop_tile");
        assertEquals("replace_surface", crop.placementMode());
        assertEquals("surface_replaceable", crop.replacePolicy());
        assertEquals(0, crop.comfortMarginBlocks());
        CompoundTag cropTemplate = crop.template();
        assertEquals(2, cropTemplate.getList("blocks", 10).size());
        ListTag cropPalette = cropTemplate.getList("palette", 10);
        assertEquals("minecraft:farmland", cropPalette.getCompound(0).getString("Name"));
        assertEquals("minecraft:wheat", cropPalette.getCompound(1).getString("Name"));

        CityDecorationContentCatalog.Content water = catalog.requireContent("geomantia:decoration/water_channel_tile");
        assertEquals("replace_surface", water.placementMode());
        assertEquals("surface_replaceable", water.replacePolicy());
        assertEquals(0, water.comfortMarginBlocks());
        assertEquals("geomantia:decoration/crop_tile", water.terrainDropFallbackContentRef());
        assertLinedWaterChannels(catalog, profile);
        assertEquals(0, catalog.requireContent("geomantia:decoration/field_border").comfortMarginBlocks());
        assertEquals(0, catalog.requireContent("geomantia:decoration/gravel_path_tile").comfortMarginBlocks());

        for (String semanticRef : List.of("scarecrow", "haystack", "farm_tool_rack",
                "market_stall_small", "crate_cluster", "barrel_cluster", "shop_sign",
                "street_bench", "lantern_post", "notice_board", "banner_post", "fountain")) {
            String contentRef = profile.requireMapping(semanticRef).variants().get(0).contentRef();
            CityDecorationContentCatalog.Content content = catalog.requireContent(contentRef);
            assertFalse(content.plant(), semanticRef);
            assertTrue(Files.isRegularFile(content.nbtPath()), semanticRef);
            assertTrue(content.template().getList("blocks", 10).size() > 0, semanticRef);
            assertEquals(0, content.template().getList("entities", 10).size(), semanticRef);
        }
        CityDecorationContentCatalog.Content fountain = catalog.requireContent(
                "geomantia:decoration/fountain_01");
        assertEquals(new CityDecorationContentCatalog.Size(5, 5, 5), fountain.size());
        assertEquals(53, fountain.template().getList("blocks", 10).size());
        assertEquals(1, fountain.comfortMarginBlocks());
        assertTrue(fountain.tags().containsAll(List.of("civic", "plaza", "fountain", "water_feature")));
        assertMappedTags(profile, catalog, "agriculture_field_detail", "agriculture");
        assertMappedTags(profile, catalog, "commercial_market", "commercial", "market");
        assertMappedTags(profile, catalog, "commercial_goods", "commercial", "goods");
        assertMappedTags(profile, catalog, "commercial_service", "commercial", "service");
        assertMappedTags(profile, catalog, "civic_public_facility", "civic", "public_facility");
        assertMappedTags(profile, catalog, "civic_notice", "civic", "notice");
        assertMappedTags(profile, catalog, "civic_plaza", "civic", "plaza");
        assertTrue(Files.readString(root.resolve("bootstrap_manifest.json"))
                .contains("functional_settlement_lined_channels.v0.5"));
    }

    @Test
    void neverOverwritesAnExistingConfigRoot(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("config/geomantia/city_decoration");
        Files.createDirectories(root);
        Path sentinel = root.resolve("player_owned.txt");
        Files.writeString(sentinel, "keep");

        CityDecorationDefaultCatalogBootstrap.ensureInstalled(root);

        assertEquals("keep", Files.readString(sentinel));
        assertFalse(Files.exists(root.resolve("content_index.json")));
    }

    private static CityDecorationContentCatalogLoader testLoader() {
        return new CityDecorationContentCatalogLoader(blockState -> {
            // Registry resolution belongs to the Forge runtime test; this suite verifies packaged catalog shape.
        });
    }

    private static void assertLinedWaterChannels(CityDecorationContentCatalog catalog,
                                                  CityDecorationStyleProfileCatalog.StyleProfile profile) {
        String straightRef = profile.requireMapping("water_channel_lined_straight").variants().get(0).contentRef();
        String endcapRef = profile.requireMapping("water_channel_lined_endcap").variants().get(0).contentRef();
        assertEquals("geomantia:decoration/water_channel_lined_straight_01", straightRef);
        assertEquals("geomantia:decoration/water_channel_lined_endcap_01", endcapRef);

        CityDecorationContentCatalog.Content straight = catalog.requireContent(straightRef);
        assertEquals(new CityDecorationContentCatalog.Size(3, 2, 1), straight.size());
        assertEquals(endcapRef, straight.terrainDropFallbackContentRef());
        assertLinedChannelMetadata(straight);
        assertEquals(3465, straight.template().getInt("DataVersion"));
        assertEquals(6, straight.template().getList("blocks", 10).size());
        assertState(straight.template(), 0, 0, 0, "minecraft:dirt");
        assertState(straight.template(), 1, 0, 0, "minecraft:water", "level", "0");
        assertState(straight.template(), 2, 0, 0, "minecraft:dirt");
        assertState(straight.template(), 0, 1, 0, "minecraft:oak_slab",
                "type", "bottom", "waterlogged", "false");
        assertState(straight.template(), 1, 1, 0, "minecraft:air");
        assertState(straight.template(), 2, 1, 0, "minecraft:oak_slab",
                "type", "bottom", "waterlogged", "false");
        assertEquals(0, straight.template().getList("entities", 10).size());

        CityDecorationContentCatalog.Content endcap = catalog.requireContent(endcapRef);
        assertEquals(new CityDecorationContentCatalog.Size(3, 2, 2), endcap.size());
        assertLinedChannelMetadata(endcap);
        assertEquals(3465, endcap.template().getInt("DataVersion"));
        assertEquals(11, endcap.template().getList("blocks", 10).size());
        assertState(endcap.template(), 0, 0, 0, "minecraft:dirt");
        assertState(endcap.template(), 1, 0, 0, "minecraft:water", "level", "0");
        assertState(endcap.template(), 2, 0, 0, "minecraft:dirt");
        for (int x = 0; x < 3; x++) {
            assertState(endcap.template(), x, 0, 1, "minecraft:dirt");
            assertState(endcap.template(), x, 1, 1, "minecraft:dirt");
        }
        assertState(endcap.template(), 0, 1, 0, "minecraft:oak_slab",
                "type", "bottom", "waterlogged", "false");
        assertState(endcap.template(), 2, 1, 0, "minecraft:oak_slab",
                "type", "bottom", "waterlogged", "false");
        assertFalse(hasBlock(endcap.template(), 1, 1, 0));
        assertEquals(0, endcap.template().getList("entities", 10).size());
    }

    private static void assertLinedChannelMetadata(CityDecorationContentCatalog.Content content) {
        assertEquals("replace_surface", content.placementMode());
        assertEquals("surface_replaceable", content.replacePolicy());
        assertEquals(0, content.groundPlaneLocalY());
        assertEquals(0, content.embedDepthBlocks());
        assertEquals("preserve", content.clearanceMode());
        assertEquals(0, content.comfortMarginBlocks());
        assertTrue(content.tags().containsAll(List.of("agriculture", "water_channel", "waterfront")));
    }

    private static void assertState(CompoundTag template, int x, int y, int z,
                                    String blockId, String... propertyPairs) {
        CompoundTag state = stateAt(template, x, y, z);
        assertEquals(blockId, state.getString("Name"), x + "," + y + "," + z);
        CompoundTag properties = state.getCompound("Properties");
        assertEquals(propertyPairs.length / 2, properties.getAllKeys().size(), blockId);
        for (int index = 0; index < propertyPairs.length; index += 2) {
            assertEquals(propertyPairs[index + 1], properties.getString(propertyPairs[index]),
                    blockId + "." + propertyPairs[index]);
        }
    }

    private static CompoundTag stateAt(CompoundTag template, int x, int y, int z) {
        ListTag blocks = template.getList("blocks", 10);
        ListTag palette = template.getList("palette", 10);
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag block = blocks.getCompound(index);
            ListTag pos = block.getList("pos", 3);
            if (pos.getInt(0) == x && pos.getInt(1) == y && pos.getInt(2) == z) {
                return palette.getCompound(block.getInt("state"));
            }
        }
        throw new AssertionError("Missing template block at " + x + "," + y + "," + z);
    }

    private static boolean hasBlock(CompoundTag template, int x, int y, int z) {
        ListTag blocks = template.getList("blocks", 10);
        for (int index = 0; index < blocks.size(); index++) {
            ListTag pos = blocks.getCompound(index).getList("pos", 3);
            if (pos.getInt(0) == x && pos.getInt(1) == y && pos.getInt(2) == z) return true;
        }
        return false;
    }

    private static void assertMappedTags(CityDecorationStyleProfileCatalog.StyleProfile profile,
                                         CityDecorationContentCatalog catalog,
                                         String semanticRef,
                                         String... requiredTags) {
        for (CityDecorationStyleProfileCatalog.Variant variant : profile.requireMapping(semanticRef).variants()) {
            CityDecorationContentCatalog.Content content = catalog.requireContent(variant.contentRef());
            for (String requiredTag : requiredTags) {
                assertTrue(content.tags().contains(requiredTag), semanticRef + " -> " + variant.contentRef());
            }
        }
    }
}
