package com.rinsing.geomantia.systems.city.infrastructure.world.landuse;

import com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityStructuralAppearanceTest {
    @Test void retainingFacadeHasCornerColumnsCapsAndBandsWithoutChangingHeight() {
        assertEquals("wallColumn",CityRetainingFacadePlanner.slot("area",1,1,65,78,64,true,true));
        int x=1;while(CityRetainingFacadePlanner.slot("area",x,0,65,78,64,false,true).equals("wallColumn"))x++;
        assertEquals("wallCap",CityRetainingFacadePlanner.slot("area",x,0,77,78,64,false,true));
        assertEquals("wallBand",CityRetainingFacadePlanner.slot("area",x,0,71,78,64,false,true));
        assertEquals("retainingWall",CityRetainingFacadePlanner.slot("area",x,0,70,78,64,false,true));
        assertEquals("wallCap",CityRetainingFacadePlanner.slot("area",1,1,65,66,64,true,true));
    }
    @Test void bridgePostsArePairedAndDoNotRestartAtChunkBoundary() {
        List<CityLandUseChunkCompiler.FeatureOperation> all=new ArrayList<>();
        for(int x=-8;x<=38;x++)for(int z:List.of(0,4))all.add(new CityLandUseChunkCompiler.FeatureOperation("bridge",x,z,
                "minecraft:oak_fence",1,CityLandUseSurfacePrintPlan.FeatureKind.BRIDGE_RAIL,
                CityLandUseSurfacePrintPlan.HorizontalFacing.NONE));
        var whole=CityBridgeStructurePlanner.posts(fragment(all,all));
        assertTrue(whole.stream().anyMatch(p->p.x()==-8));assertTrue(whole.stream().anyMatch(p->p.x()==38));
        for(var p:whole)assertTrue(whole.stream().anyMatch(q->p.x()==q.x()&&p.z()!=q.z()));
        for(int chunk=-1;chunk<=2;chunk++) {
            int min=chunk*16,max=min+15;
            var output=all.stream().filter(p->p.x()>=min&&p.x()<=max).toList();
            var context=all.stream().filter(p->p.x()>=min-8&&p.x()<=max+8).toList();
            var split=CityBridgeStructurePlanner.posts(fragment(output,context));
            for(var p:output)assertEquals(whole.contains(p),split.contains(p));
        }
    }
    private static CityLandUseChunkCompiler.ChunkFragment fragment(List<CityLandUseChunkCompiler.FeatureOperation> output,
            List<CityLandUseChunkCompiler.FeatureOperation> context) {
        return new CityLandUseChunkCompiler.ChunkFragment(CityLandUseChunkCompiler.RESULT_SCHEMA,"city","hash","palette",
                0,0,output.size(),0,0,0,"minecraft:dirt",List.of(),List.of(),List.of(),output,context);
    }
}
