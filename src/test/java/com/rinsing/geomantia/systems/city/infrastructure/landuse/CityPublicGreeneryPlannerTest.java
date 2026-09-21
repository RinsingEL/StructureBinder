package com.rinsing.geomantia.systems.city.infrastructure.landuse;

import com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictPlanner;
import com.rinsing.geomantia.systems.city.application.landuse.*;
import com.rinsing.geomantia.systems.city.application.outdoor.*;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityPublicGreeneryPlannerTest {
    @Test void noPlatformCityStillPrintsSharedGreeneryAndPreservesRoadsWaterAndTemplates() {
        BlockBounds bounds = new BlockBounds(0,0,39,23);
        List<LandUseTerrainField.Cell> cells = new ArrayList<>();
        for(int z=0;z<=23;z++) for(int x=0;x<=39;x++) cells.add(new LandUseTerrainField.Cell(
                x,z,x,z,1,65,0,0,0,x==19,0,1,"minecraft:plains","plain","p",true));
        var terrain = new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",bounds,1,cells);
        var buildings = Map.of("homes",List.of(new BlockBounds(2,2,8,8),new BlockBounds(25,2,31,8),
                new BlockBounds(2,16,8,22),new BlockBounds(25,16,31,22)));
        var district = new CityDistrictPlanner().plan(bounds,terrain,buildings,Map.of(),List.of(),
                Set.of(new BlockPoint(14,10)),new LandUseSeedGroup.FoundationSettings(2,4,16));
        var road = new LandUseSourceResolver.RoadBand("street","network","ENTRANCE_SHORT_ALLEY",
                new BlockPoint(0,12),new BlockPoint(38,12),new BlockBounds(0,12,38,12),1,"SURFACE_ONLY");
        var sources = new LandUseSourceResolver.Resolution(List.of(),List.of(),List.of(),"1",Map.of(),Map.of(),
                List.of(road),List.of(),List.of(),CityMaterialField.empty(),district);
        var reservations = com.google.gson.JsonParser.parseString("{wallCorridorMask:[{blockBounds:{minX:15,maxX:15,minZ:0,maxZ:23}}]}").getAsJsonObject();
        var first = new LandUsePlanningService().plan("city",sources,reservations,terrain,CityUrbanResidualResolver.Config.disabled());
        var second = new LandUsePlanningService().plan("city",sources,reservations,terrain,CityUrbanResidualResolver.Config.disabled());
        assertEquals(first.surfacePrintPlan(),second.surfacePrintPlan());
        assertTrue(first.plan().areas().isEmpty());
        var greens = first.surfacePrintPlan().featureCells().stream().filter(f -> f.sourceId().startsWith(CityPublicGreeneryPlanner.SOURCE)).toList();
        assertFalse(greens.isEmpty());
        assertTrue(greens.stream().noneMatch(f -> f.x()==15 || f.x()==19 || f.z()==12 || f.x()==14 && f.z()==10));
        assertTrue(greens.stream().noneMatch(f -> district.structures().contains(new BlockPoint(f.x(),f.z()))));
        assertTrue(greens.stream().allMatch(f -> f.targetSurfaceY()==null),"greenery follows actual ground");
        assertTrue(first.urbanSpacePlan().residualRegions().stream().anyMatch(r -> r.disposition()==CityUrbanSpacePlan.ResidualDisposition.COMMON_GREEN));
    }

    @Test void presetRejectsInvalidDensityAndCanBeLoadedWithoutMinecraft() {
        assertThrows(IllegalArgumentException.class, () -> new CityPublicGreeneryPlanner.Preset("minecraft:grass_block",List.of("minecraft:poppy"),Double.NaN,1));
        assertNotNull(CityPublicGreeneryPlanner.Preset.load());
    }
}
