package com.rinsing.geomantia.systems.city.infrastructure.world;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CityTemplateGroundLevelTest {
    @Test void soilBaseAndSunkenBasinUseTheirOwnGroundDepth() {
        assertEquals(1, CityTemplateGroundLevel.offset(template(0, false)));
        assertEquals(4, CityTemplateGroundLevel.offset(template(3, false)));
    }
    @Test void interiorPlanterDoesNotBuryBareFloorBuilding() {
        assertEquals(0, CityTemplateGroundLevel.offset(template(4, true)));
    }
    @Test void bareTemplateKeepsExistingOrigin() {
        var nbt = template(0, false);
        nbt.put("blocks", new ListTag());
        assertEquals(0, CityTemplateGroundLevel.offset(nbt));
    }
    private static CompoundTag template(int soilTop, boolean interiorOnly) {
        var nbt = new CompoundTag(); nbt.put("size", ints(5, 10, 5));
        var palette = new ListTag(); var soil = new CompoundTag();
        soil.putString("Name", "minecraft:dirt"); palette.add(soil); nbt.put("palette", palette);
        var blocks = new ListTag();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) {
            if (interiorOnly && (x != 2 || z != 2)) continue;
            for (int y = 0; y <= soilTop; y++) {
                var block = new CompoundTag(); block.putInt("state", 0);
                block.put("pos", ints(x,y,z)); blocks.add(block);
            }
        }
        nbt.put("blocks", blocks); return nbt;
    }
    private static ListTag ints(int... values) {
        var result = new ListTag(); for (int value : values) result.add(IntTag.valueOf(value)); return result;
    }
}
