package com.rinsing.geomantia.systems.city.infrastructure.dressing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityDecorationDefaultCatalogBootstrapTest {
    @Test
    void installsFormalAgricultureCatalogIntoMissingConfigRoot(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("config/geomantia/city_decoration");

        Path installed = CityDecorationDefaultCatalogBootstrap.ensureInstalled(root);
        CityDecorationContentCatalog catalog = new CityDecorationContentCatalogLoader().load(installed);
        CityDecorationStyleProfileCatalog styles = new CityDecorationStyleProfileCatalogLoader().load(installed, catalog);
        CityDecorationStyleProfileCatalog.StyleProfile profile = styles.requireProfile("medieval_coastal");

        assertEquals(root.toAbsolutePath().normalize(), installed);
        assertTrue(Files.isRegularFile(root.resolve("content_index.json")));
        assertTrue(Files.isRegularFile(root.resolve("bootstrap_manifest.json")));
        for (String semanticRef : List.of("crop_tile", "water_channel_tile", "field_border", "gravel_path_tile")) {
            String contentRef = profile.requireMapping(semanticRef).variants().get(0).contentRef();
            assertTrue(catalog.contents().containsKey(contentRef), semanticRef);
            assertTrue(Files.isRegularFile(catalog.requireContent(contentRef).nbtPath()), semanticRef);
        }
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

    @Test
    void explicitlyUpgradesOnlyTheUnmodifiedLegacyDefaultWaterChannel(@TempDir Path temp) throws Exception {
        Path root = CityDecorationDefaultCatalogBootstrap.ensureInstalled(
                temp.resolve("config/geomantia/city_decoration"));
        Path indexPath = root.resolve("content_index.json");
        JsonObject index = JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject();
        index.getAsJsonArray("contents").forEach(entry -> {
            JsonObject content = entry.getAsJsonObject();
            if ("geomantia:decoration/water_channel_tile".equals(content.get("contentId").getAsString())) {
                content.remove("terrainDropFallbackContentRef");
            }
        });
        Files.writeString(indexPath, index.toString());
        Files.writeString(root.resolve("bootstrap_manifest.json"), """
                {"schemaVersion":"city_decoration_default_bootstrap.v0.1",
                 "source":"geomantia:default_config/city_decoration"}
                """);
        String beforeHash = new CityDecorationContentCatalogLoader().load(root).catalogHash();

        CityDecorationDefaultCatalogBootstrap.UpgradeResult result =
                CityDecorationDefaultCatalogBootstrap.upgradeManagedDefault(root);
        CityDecorationContentCatalog upgraded = new CityDecorationContentCatalogLoader().load(root);

        assertTrue(result.contentIndexChanged());
        assertTrue(result.manifestChanged());
        assertTrue(Files.isRegularFile(result.backupPath()));
        assertEquals("geomantia:decoration/crop_tile",
                upgraded.requireContent("geomantia:decoration/water_channel_tile").terrainDropFallbackContentRef());
        assertFalse(beforeHash.equals(upgraded.catalogHash()));
    }

    @Test
    void explicitlyUpgradesUnmodifiedManagedV2CatalogToStrictV3PoseFields(@TempDir Path temp) throws Exception {
        Path root = CityDecorationDefaultCatalogBootstrap.ensureInstalled(
                temp.resolve("config/geomantia/city_decoration"));
        Path indexPath = root.resolve("content_index.json");
        JsonObject index = JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject();
        index.addProperty("schemaVersion", CityDecorationContentCatalog.LEGACY_SCHEMA);
        index.getAsJsonArray("contents").forEach(entry -> {
            JsonObject content = entry.getAsJsonObject();
            content.remove("groundPlaneLocalY");
            content.remove("embedDepthBlocks");
            content.remove("clearanceMode");
        });
        Files.writeString(indexPath, index.toString());

        CityDecorationDefaultCatalogBootstrap.UpgradeResult result =
                CityDecorationDefaultCatalogBootstrap.upgradeManagedDefault(root);
        JsonObject upgradedIndex = JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject();
        CityDecorationContentCatalog upgraded = new CityDecorationContentCatalogLoader().load(root);

        assertTrue(result.contentIndexChanged());
        assertEquals(CityDecorationContentCatalog.SCHEMA,
                upgradedIndex.get("schemaVersion").getAsString());
        assertEquals(0, upgraded.requireContent("geomantia:decoration/crop_tile").groundPlaneLocalY());
        assertEquals("preserve", upgraded.requireContent("geomantia:decoration/crop_tile").clearanceMode());
        assertTrue(Files.isRegularFile(result.backupPath()));
    }

    @Test
    void refusesToUpgradeCustomizedLegacyWaterChannel(@TempDir Path temp) throws Exception {
        Path root = CityDecorationDefaultCatalogBootstrap.ensureInstalled(
                temp.resolve("config/geomantia/city_decoration"));
        Path indexPath = root.resolve("content_index.json");
        JsonObject index = JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject();
        index.getAsJsonArray("contents").forEach(entry -> {
            JsonObject content = entry.getAsJsonObject();
            if ("geomantia:decoration/water_channel_tile".equals(content.get("contentId").getAsString())) {
                content.remove("terrainDropFallbackContentRef");
                content.addProperty("nbtFile", "templates/custom_channel.nbt");
            }
        });
        Files.writeString(indexPath, index.toString());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CityDecorationDefaultCatalogBootstrap.upgradeManagedDefault(root));

        assertTrue(failure.getMessage().contains("CITY_DECORATION_DEFAULT_CATALOG_UPGRADE_UNSAFE"));
        assertFalse(Files.exists(root.resolve("upgrades/content_index.before-terrain-drop-fallback.json")));
    }
}
