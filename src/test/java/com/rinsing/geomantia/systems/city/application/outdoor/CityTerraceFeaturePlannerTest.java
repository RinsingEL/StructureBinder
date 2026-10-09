package com.rinsing.geomantia.systems.city.application.outdoor;

import com.rinsing.geomantia.systems.city.domain.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityTerraceFeaturePlannerTest {
    private final CityTerraceFeaturePlanner planner=new CityTerraceFeaturePlanner();
    private static Map<BlockPoint,Integer> rectangle(int x0,int z0,int x1,int z1,int y) {
        Map<BlockPoint,Integer> mask=new LinkedHashMap<>();for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++)mask.put(new BlockPoint(x,z),y);return mask;
    }
    private static CityTerraceFeaturePlanner.Settings settings(String asset,String rail) {
        return new CityTerraceFeaturePlanner.Settings(rail,"minecraft:stone_bricks","minecraft:stone_brick_slab",8,0,4,16,
                new CityTerraceFeaturePlanner.Fixture(asset,7,7,16,2),new CityTerraceFeaturePlanner.Fixture(asset+"_court",9,9,8,2));
    }
    @Test void entrancesRemainOpenAndAllFixturesHaveCompleteSupportedFootprints() {
        var mask=rectangle(0,0,60,50,72);var road=new BlockBounds(0,-5,8,18);var building=new BlockBounds(20,20,40,40);
        var result=planner.plan("group",mask,mask,Map.of(),List.of(building),List.of(road),settings("kit:tower","minecraft:stone_brick_wall"));
        assertFalse(result.fixtures().isEmpty());assertFalse(result.openings().isEmpty());
        for(var block:result.blocks())assertFalse(block.x()<=10&&block.z()<=20);
        for(var fixture:result.fixtures())for(int z=fixture.originBlock().z();z<fixture.originBlock().z()+fixture.depth();z++)for(int x=fixture.originBlock().x();x<fixture.originBlock().x()+fixture.width();x++) {
            assertEquals(72,mask.get(new BlockPoint(x,z)));assertFalse(x<=10&&z<=20);
            assertFalse(x>=19&&x<=41&&z>=19&&z<=41);
        }
    }
    @Test void inputOrderAndThemeNamesDoNotDetermineGeometry() {
        var mask=rectangle(-30,-20,30,20,64);var reverse=new LinkedHashMap<BlockPoint,Integer>();
        var keys=new ArrayList<>(mask.keySet());Collections.reverse(keys);keys.forEach(p->reverse.put(p,mask.get(p)));
        var a=planner.plan("parcel",mask,mask,Map.of(),List.of(),List.of(),settings("first:corner","minecraft:stone_brick_wall"));
        var b=planner.plan("parcel",reverse,reverse,Map.of(),List.of(),List.of(),settings("first:corner","minecraft:stone_brick_wall"));
        assertEquals(a,b);
        var c=planner.plan("parcel",mask,mask,Map.of(),List.of(),List.of(),settings("second:post","minecraft:spruce_fence"));
        assertEquals(a.fixtures().stream().map(p->List.of(p.originBlock(),p.rotation(),p.surfaceY())).toList(),
                c.fixtures().stream().map(p->List.of(p.originBlock(),p.rotation(),p.surfaceY())).toList());
        assertEquals(a.blocks().stream().map(p->List.of(p.x(),p.y(),p.z(),p.kind())).toList(),c.blocks().stream().map(p->List.of(p.x(),p.y(),p.z(),p.kind())).toList());
    }
    @Test void turningOffAllFacilitiesProducesNoBlocksOrTemplates() {
        var mask=rectangle(0,0,20,20,72);
        var empty=new CityTerraceFeaturePlanner.Settings("","","",8,0,0,16,null,null);
        var result=planner.plan("plain",mask,mask,Map.of(),List.of(),List.of(),empty);
        assertTrue(result.blocks().isEmpty());assertTrue(result.fixtures().isEmpty());
    }
    @Test void neighbouringSameHeightPlatformDoesNotGetAnInternalFence() {
        var own=rectangle(0,0,30,30,72);var all=new HashMap<>(own);all.putAll(rectangle(31,0,60,30,72));
        var settings=new CityTerraceFeaturePlanner.Settings("minecraft:stone_brick_wall","","",8,0,0,16,null,null);
        var result=planner.plan("left",own,all,Map.of(),List.of(),List.of(),settings);
        assertTrue(result.blocks().stream().noneMatch(b->b.x()==30&&b.z()>0&&b.z()<30));
    }
    @Test void concavePlatformsNeverGrowToFitARequestedFixture() {
        var own=rectangle(0,0,40,40,72);own.keySet().removeIf(p->p.x()>20&&p.z()<20);int size=own.size();
        var result=planner.plan("irregular",own,own,Map.of(),List.of(),List.of(),settings("kit:corner","minecraft:stone_brick_wall"));
        assertEquals(size,own.size());
        for(var f:result.fixtures())for(int z=f.originBlock().z();z<f.originBlock().z()+f.depth();z++)for(int x=f.originBlock().x();x<f.originBlock().x()+f.width();x++)assertTrue(own.containsKey(new BlockPoint(x,z)));
    }
}
