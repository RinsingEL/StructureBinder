package com.rinsing.geomantia.systems.city.application.outdoor;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CityOutdoorBlueprintCompilerWaterShareTest {
    private static LandUseTerrainField field(boolean... water) {
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        int index = 0;
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            boolean wet = index < water.length && water[index];
            cells.add(new LandUseTerrainField.Cell(x, z, x * 8, z * 8, 8, 64, 0, 0, 0, wet, 0, 20,
                    "minecraft:plains", wet ? "water" : "plain", "", true));
            index++;
        }
        return new LandUseTerrainField(LandUseTerrainField.SCHEMA, "city", new BlockBounds(0, 0, 15, 15), 8, cells);
    }

    @Test void mostlyWaterCourtIsDetected() {
        assertEquals(1.0, CityOutdoorBlueprintCompiler.waterShare(field(true, true, true, true),
                new BlockBounds(0, 0, 15, 15)));
        assertEquals(0.5, CityOutdoorBlueprintCompiler.waterShare(field(true, true, false, false),
                new BlockBounds(0, 0, 15, 15)));
        assertEquals(0.0, CityOutdoorBlueprintCompiler.waterShare(field(false, false, false, false),
                new BlockBounds(0, 0, 15, 15)));
    }

    @Test void unsampledOrMissingCellsDoNotCount() {
        assertEquals(0.0, CityOutdoorBlueprintCompiler.waterShare(field(), new BlockBounds(0, 0, 15, 15)));
        assertEquals(0.0, CityOutdoorBlueprintCompiler.waterShare(field(false, false, false, false),
                new BlockBounds(100, 100, 120, 120)));
    }
}
