package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.domain.cell.SampleSource;
import com.rinsing.geomantia.systems.gis.domain.cell.SurfaceType;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandUseTerrainFieldCompilerTest {
    @Test
    void compileAndCodecPreservePerCellTerrainFacts() {
        GisSampleConfig config = GisSampleConfig.defaults().withCellStepBlocks(4);
        AtlasRegion region = new AtlasRegion("minecraft:overworld", 0, 0, config);
        region.cell(0, 0).setSample(SampleSource.PRIOR, 72, SurfaceType.GRASS,
                "minecraft:forest", true, 2);
        region.cell(0, 0).setSmallMetrics(3.5, 4.5, 1.25, 0);
        region.cell(0, 0).setLargeMetrics(0, 0);

        LandUseTerrainField field = new LandUseTerrainFieldCompiler().compile("city_test",
                new BlockBounds(0, 0, 7, 7), List.of(region));
        LandUseTerrainField restored = new LandUseTerrainFieldCodec().fromJson(
                new LandUseTerrainFieldCodec().toJson(field));

        assertEquals(4, restored.cellStepBlocks());
        LandUseTerrainField.Cell cell = restored.cellAt(1, 1).orElseThrow();
        assertTrue(cell.sampled());
        assertTrue(cell.water());
        assertEquals(72, cell.elevation());
        assertEquals(3.5, cell.slope());
        assertEquals(4.5, cell.localRelief());
        assertEquals("minecraft:forest", cell.biomeId());
    }
}
