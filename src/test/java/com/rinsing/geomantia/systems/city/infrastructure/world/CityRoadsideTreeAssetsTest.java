package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.google.gson.JsonParser;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class CityRoadsideTreeAssetsTest {
    @Test void nativeAssetsContainOnlyPersistentTreeBlocksAndMatchRootAndSizeManifest() throws Exception {
        try (var stream = getClass().getResourceAsStream("/data/geomantia/structures/roadside/manifest.json")) {
            assertNotNull(stream);
            var manifest = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
            assertEquals(3, manifest.size());
            for (var entry : manifest) {
                var item = entry.getAsJsonObject();
                String resource = "/data/geomantia/structures/" + item.get("templateRef").getAsString().split(":")[1] + ".nbt";
                try (var input = getClass().getResourceAsStream(resource)) {
                    assertNotNull(input);
                    var nbt = NbtIo.readCompressed(input);
                    var size = nbt.getList("size", Tag.TAG_INT);
                    for (int a = 0; a < 3; a++) assertEquals(item.getAsJsonArray("size").get(a).getAsInt(), size.getInt(a));
                    var palette = nbt.getList("palette", Tag.TAG_COMPOUND);
                    for (int i = 0; i < palette.size(); i++) {
                        var state = palette.getCompound(i);
                        String name = state.getString("Name");
                        assertTrue(name.endsWith("_log") || name.endsWith("_wood") || name.endsWith("_leaves"), name);
                        if (name.endsWith("_leaves")) assertEquals("true", state.getCompound("Properties").getString("persistent"));
                    }
                    var blocks = nbt.getList("blocks", Tag.TAG_COMPOUND);
                    assertEquals(item.get("blockCount").getAsInt(), blocks.size());
                    boolean rootFound = false;
                    for (int i = 0; i < blocks.size(); i++) {
                        var block = blocks.getCompound(i);
                        var position = block.getList("pos", Tag.TAG_INT);
                        for (int a = 0; a < 3; a++) assertTrue(position.getInt(a) >= 0 && position.getInt(a) < size.getInt(a));
                        var root = item.getAsJsonArray("root");
                        if (position.getInt(0) == root.get(0).getAsInt() && position.getInt(1) == 0
                                && position.getInt(2) == root.get(2).getAsInt()) {
                            String name = palette.getCompound(block.getInt("state")).getString("Name");
                            rootFound = name.endsWith("_log") || name.endsWith("_wood");
                        }
                    }
                    assertTrue(rootFound);
                }
            }
        }
    }

    @Test void roadsideStructuresHaveNoTerrainBeard() throws Exception {
        try (var stream = getClass().getResourceAsStream("/data/geomantia/worldgen/structure/city_roadside_decoration.json")) {
            assertNotNull(stream);
            var settings = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("none", settings.get("terrain_adaptation").getAsString());
        }
    }
}
