package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.CitySurfaceMaterials;
import com.rinsing.geomantia.systems.city.domain.landuse.CityMaterialField;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.application.landuse.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CitySurfaceMaterialsTest {
    @Test void overridesUseNearestRetainedDistrictAndRoadTypeWithoutLeakingAcrossCities(){
        var m=CitySurfaceMaterials.read(JsonParser.parseString("""
                {"defaults":{"ground":"minecraft:stone","roadSurface":"minecraft:stone_slab"},
                 "groups":{"west":{"ground":"minecraft:bricks","roadSurface":"minecraft:brick_slab"}},
                 "roads":{"CITY_MAIN_ROAD":{"roadSurface":"minecraft:quartz_slab"}}}
                """).getAsJsonObject());
        var field=new CityMaterialField(m,List.of(new CityMaterialField.Owner("west",new BlockBounds(0,0,5,5)),
                new CityMaterialField.Owner("east",new BlockBounds(20,0,25,5))),
                List.of(new CityMaterialField.Road("main","CITY_MAIN_ROAD",new BlockBounds(0,6,25,6))));
        assertEquals("minecraft:bricks",field.at("ground",3,4,"fallback"));
        assertEquals("minecraft:stone",field.at("ground",24,4,"fallback"));
        assertEquals("minecraft:quartz_slab",field.at("roadSurface",3,6,"main","fallback"));
        assertEquals("fallback",CityMaterialField.empty().at("ground",3,4,"fallback"));
        assertEquals(field,CityMaterialField.read(field.toJson()));
    }
    @Test void materialFreezeKeepsRoadCoordinatesFacingAndHeightAndChangesHash(){
        var features=List.of(new CityLandUseSurfacePrintPlan.FeatureCell("main",2,0,"minecraft:stone_stairs",0,
                CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR,CityLandUseSurfacePrintPlan.HorizontalFacing.EAST,70),
                new CityLandUseSurfacePrintPlan.FeatureCell("main",2,1,"minecraft:stone_stairs",0,
                CityLandUseSurfacePrintPlan.FeatureKind.ROAD_STAIR,CityLandUseSurfacePrintPlan.HorizontalFacing.SOUTH,69));
        var original=new CityLandUseSurfacePrintPlan(CityLandUseSurfacePrintPlan.SCHEMA,"city","land","",List.of(),List.of(),features);
        var field=new CityMaterialField(new CitySurfaceMaterials(Map.of("roadStair","minecraft:brick_stairs","roadCurb","minecraft:quartz_stairs"),Map.of(),Map.of(),Map.of()),List.of(),
                List.of(new CityMaterialField.Road("main","CITY_MAIN_ROAD",new BlockBounds(0,0,8,0))));
        var codec=new CityLandUseSurfacePrintPlanCodec();var changed=codec.withComputedHash(original.withMaterials(field));
        assertEquals("minecraft:brick_stairs",changed.featureCells().get(0).blockId());
        assertEquals("minecraft:quartz_stairs",changed.featureCells().get(1).blockId());
        for(int i=0;i<features.size();i++) {
            var a=features.get(i);var c=changed.featureCells().get(i);
            assertEquals(a.x(),c.x());assertEquals(a.z(),c.z());assertEquals(a.facing(),c.facing());assertEquals(a.targetSurfaceY(),c.targetSurfaceY());
        }
        assertNotEquals(codec.computePlanHash(original),changed.planHash());
        assertEquals(changed,codec.fromJson(codec.toJson(changed)));
    }
    @Test void unknownSlotsAndBlockStatesAreActionableErrors(){
        var unknown=assertThrows(IllegalArgumentException.class,()->CitySurfaceMaterials.read(JsonParser.parseString("{\"defaults\":{\"roadWitdh\":\"minecraft:stone\"}}").getAsJsonObject()));
        assertTrue(unknown.getMessage().contains("allowed="));
        assertThrows(IllegalArgumentException.class,()->CitySurfaceMaterials.read(JsonParser.parseString("{\"defaults\":{\"ground\":\"minecraft:stone[foo=true]\"}}").getAsJsonObject()));
    }
}
