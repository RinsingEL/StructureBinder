package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CityWallModuleConfigTest {
    @TempDir Path directory;

    private CityWallModuleConfig.Loaded defaults() throws IOException {
        CityWallModuleConfig.ensureDefaults(directory);
        return CityWallModuleConfig.load(directory);
    }

    private JsonObject config() throws IOException {
        return JsonParser.parseString(Files.readString(directory.resolve("modules.json"))).getAsJsonObject();
    }

    private void save(JsonObject config) throws IOException {
        Files.writeString(directory.resolve("modules.json"), config.toString());
    }

    @Test void frozenModulePayloadSurvivesAuthorConfigChangesAndRejectsTampering() throws Exception {
        var loaded=defaults();var plan=new JsonObject();loaded.freeze(plan);
        var restored=CityWallModuleConfig.fromFrozen(plan);
        assertEquals(loaded.guardTower(),restored.guardTower());
        JsonObject changed=config();changed.addProperty("foundationBlock","minecraft:bricks");save(changed);
        assertEquals(loaded.foundationBlock(),CityWallModuleConfig.fromFrozen(plan).foundationBlock());
        plan.getAsJsonObject("wallModulePayload").addProperty("guardTower","AA==");
        assertThrows(IOException.class,()->CityWallModuleConfig.fromFrozen(plan));
    }

    @Test void standaloneConfigurationNeedsNoBuildingCatalog() throws Exception {
        var loaded = defaults();
        assertEquals(1050, loaded.guardTower().getList("blocks", Tag.TAG_COMPOUND).size());
        assertEquals(960, loaded.straightWall().getList("blocks", Tag.TAG_COMPOUND).size());
        JsonObject plan = new JsonObject();
        loaded.freeze(plan);
        CityWallModuleConfig.load(directory).requireMatches(plan);
        assertFalse(Files.exists(directory.resolve("template_catalog.json")));
        assertEquals("config/geomantia/city_walls/modules.json",
                plan.getAsJsonObject("templateLibrary").get("templateSource").getAsString());
    }

    @Test void bootstrapPreservesUserConfigurationAndModuleFiles() throws Exception {
        var loaded = defaults();
        var config = config();
        config.addProperty("foundationBlock", "minecraft:bricks");
        save(config);
        var tower = loaded.guardTower();
        tower.putString("author", "custom");
        NbtIo.writeCompressed(tower, directory.resolve("modules/guard_tower.nbt").toFile());
        CityWallModuleConfig.ensureDefaults(directory);
        var current = CityWallModuleConfig.load(directory);
        assertEquals("minecraft:bricks", current.foundationBlock());
        assertEquals("custom", current.guardTower().getString("author"));
    }

    @Test void changingSettingsInvalidatesFrozenPlan() throws Exception {
        var loaded = defaults();
        JsonObject plan = new JsonObject();
        loaded.freeze(plan);
        var config = config();
        config.addProperty("foundationBlock", "minecraft:bricks");
        save(config);
        assertThrows(IOException.class, () -> CityWallModuleConfig.load(directory).requireMatches(plan));
        assertThrows(IOException.class, () -> loaded.requireMatches(new JsonObject()));
    }

    @Test void editedWallMaterialIsReadAndInvalidatesFrozenPlan() throws Exception {
        var loaded = defaults();
        JsonObject plan = new JsonObject();
        loaded.freeze(plan);
        var wall = loaded.straightWall();
        wall.getList("palette", Tag.TAG_COMPOUND).getCompound(0).putString("Name", "minecraft:gold_block");
        NbtIo.writeCompressed(wall, directory.resolve("modules/wall_straight.nbt").toFile());
        var changed = CityWallModuleConfig.load(directory);
        assertEquals("minecraft:gold_block", changed.straightWall().getList("palette", Tag.TAG_COMPOUND).getCompound(0).getString("Name"));
        assertThrows(IOException.class, () -> changed.requireMatches(plan));
    }

    @Test void rejectsPathsOutsideIndependentDirectory() throws Exception {
        defaults();
        var config = config();
        config.getAsJsonObject("modules").addProperty("straightWall", "../outside.nbt");
        save(config);
        assertTrue(assertThrows(IOException.class, () -> CityWallModuleConfig.load(directory)).getMessage().contains("PATH_OUTSIDE_CONFIG"));
    }

    @Test void rejectsUnsupportedConnectionsAndIncompleteGrid() throws Exception {
        var loaded = defaults();
        var config = config();
        config.getAsJsonObject("connections").addProperty("walkwayFloorY", 8);
        save(config);
        assertThrows(IOException.class, () -> CityWallModuleConfig.load(directory));
        save(loaded.configuration());
        var wall = loaded.straightWall();
        wall.getList("blocks", Tag.TAG_COMPOUND).remove(0);
        NbtIo.writeCompressed(wall, directory.resolve("modules/wall_straight.nbt").toFile());
        assertTrue(assertThrows(IOException.class, () -> CityWallModuleConfig.load(directory)).getMessage().contains("COMPLETE_BLOCK_GRID"));
    }

    @Test void missingConfiguredModuleFailsInsteadOfFallingBackToBundledAsset() throws Exception {
        defaults();
        var config = config();
        config.getAsJsonObject("modules").addProperty("guardTower", "modules/my_tower.nbt");
        save(config);
        CityWallModuleConfig.ensureDefaults(directory);
        assertThrows(IOException.class, () -> CityWallModuleConfig.load(directory));
    }
}
