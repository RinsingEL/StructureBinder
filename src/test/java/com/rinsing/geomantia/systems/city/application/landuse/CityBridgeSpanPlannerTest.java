package com.rinsing.geomantia.systems.city.application.landuse;

import com.rinsing.geomantia.systems.city.domain.landuse.LandUseTerrainField;
import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

class CityBridgeSpanPlannerTest {
    @Test void explicitDryCanyonBridgeFreezesOneDeckHeightWithoutFillingValley() {
        List<FeatureCell> features=new ArrayList<>();List<LandUseTerrainField.Cell> terrain=new ArrayList<>();
        for(int x=0;x<=40;x++)for(int z=-2;z<=2;z++) {
            features.add(new FeatureCell("bridge",x,z,"minecraft:stone_bricks",0,FeatureKind.BRIDGE_DECK,HorizontalFacing.NONE));
            terrain.add(new LandUseTerrainField.Cell(x,z,x,z,1,x>3&&x<37?40:64,0,0,0,false,0,100,"plain","plain","p",true));
        }
        var field=new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",new BlockBounds(0,-2,40,2),1,terrain);
        var band=new LandUseSourceResolver.RoadBand("bridge","network","CITY_BRIDGE",new BlockPoint(0,0),new BlockPoint(40,0),field.planningBounds(),5,"BRIDGE_DECK_RAIL");
        var result=CityBridgeSpanPlanner.plan(features,List.of(band),field);
        assertEquals(205,result.stream().filter(c->c.kind()==FeatureKind.BRIDGE_DECK&&c.targetSurfaceY()==64).count());
        assertTrue(result.stream().allMatch(c->c.kind()==FeatureKind.BRIDGE_DECK||c.kind()==FeatureKind.BRIDGE_RAIL));
        assertEquals(result,CityBridgeSpanPlanner.plan(features,List.of(band),field));
    }

    @Test void waterRoadBecomesOneLevelBridgeWithBanksAndOpenCrossing() {
        List<FeatureCell> cells=new ArrayList<>();
        for(int x=-8;x<=24;x++)for(int z=-2;z<=2;z++)cells.add(new FeatureCell("road",x,z,
                "minecraft:stone_brick_slab",0,FeatureKind.ROAD_SLAB,HorizontalFacing.NONE));
        // Another road joins the side of the bridge: do not seal its entrance with a railing.
        cells.add(new FeatureCell("side",4,3,"minecraft:stone_brick_slab",0,FeatureKind.ROAD_SLAB,HorizontalFacing.NONE));
        var band=new LandUseSourceResolver.RoadBand("road","network","CITY_MAIN_ROAD",new BlockPoint(-8,0),
                new BlockPoint(24,0),new BlockBounds(-8,-2,24,2),5,"STAIR_SLAB_STAIR");
        List<LandUseTerrainField.Cell> terrain=new ArrayList<>();
        for(int x=-8;x<=24;x++)for(int z=-3;z<=3;z++)terrain.add(new LandUseTerrainField.Cell(x,z,x,z,1,
                x>=0&&x<=15?63:68,0,0,0,x>=0&&x<=15,4,0,"river","plain","",true));
        var field=new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",new BlockBounds(-8,-3,24,3),1,terrain);
        var result=CityBridgeSpanPlanner.plan(cells,List.of(band),field);
        var decks=result.stream().filter(c->c.kind()==FeatureKind.BRIDGE_DECK).toList();
        assertEquals(18*5,decks.size());assertTrue(decks.stream().allMatch(c->c.targetSurfaceY()==68));
        assertTrue(result.stream().anyMatch(c->c.x()==-8&&c.kind()==FeatureKind.ROAD_SLAB));
        assertTrue(result.stream().noneMatch(c->c.x()==4&&c.z()==2&&c.kind()==FeatureKind.BRIDGE_RAIL));
        assertTrue(result.stream().anyMatch(c->c.x()==5&&c.z()==2&&c.kind()==FeatureKind.BRIDGE_RAIL));
        assertEquals(result,CityBridgeSpanPlanner.plan(cells,List.of(band),field));
    }
}
