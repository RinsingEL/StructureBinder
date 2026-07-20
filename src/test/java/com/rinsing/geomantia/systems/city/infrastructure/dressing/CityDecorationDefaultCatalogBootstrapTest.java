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
        assertEquals(0, catalog.requireContent("geomantia:decoration/field_border").comfortMarginBlocks());
        assertEquals(0, catalog.requireContent("geomantia:decoration/gravel_path_tile").comfortMarginBlocks());

        for (String semanticRef : List.of("scarecrow", "haystack", "farm_tool_rack",
                "market_stall_small", "crate_cluster", "barrel_cluster", "shop_sign",
                "street_bench", "lantern_post", "notice_board", "banner_post")) {
            String contentRef = profile.requireMapping(semanticRef).variants().get(0).contentRef();
            CityDecorationContentCatalog.Content content = catalog.requireContent(contentRef);
            assertFalse(content.plant(), semanticRef);
            assertTrue(Files.isRegularFile(content.nbtPath()), semanticRef);
            assertTrue(content.template().getList("blocks", 10).size() > 0, semanticRef);
            assertEquals(0, content.template().getList("entities", 10).size(), semanticRef);
        }
        assertMappedTags(profile, catalog, "agriculture_field_detail", "agriculture");
        assertMappedTags(profile, catalog, "commercial_market", "commercial", "market");
        assertMappedTags(profile, catalog, "commercial_goods", "commercial", "goods");
        assertMappedTags(profile, catalog, "commercial_service", "commercial", "service");
        assertMappedTags(profile, catalog, "civic_public_facility", "civic", "public_facility");
        assertMappedTags(profile, catalog, "civic_notice", "civic", "notice");
        assertMappedTags(profile, catalog, "civic_plaza", "civic", "plaza");
        assertTrue(Files.readString(root.resolve("bootstrap_manifest.json"))
                .contains("functional_settlement.v0.4"));
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
