package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.gis.domain.cell.LandformType;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CityPatchBoundaryGuideTest {
    private LandformPatchSummary patch(String id, int[][] points) {
        var cells = Arrays.stream(points).map(p -> new PatchMemberCell(p[0]/16,p[1]/16,p[0],p[1])).toList();
        return new LandformPatchSummary(id,id,id,new BlockPoint(0,0),new BlockBounds(-128,-128,128,128),
                "patch_member_cells",cells,cells.size()*256,cells.size(),LandformType.PLAIN,
                List.of(),List.of(),AreaClass.SMALL,new MetricsSummary(64,64,64,0,0),List.of(),List.of());
    }
    @Test void followsTurningSharedEdgesOnTheFirstSideAndDoesNotInventAnEdge() throws Exception {
        try(var in=getClass().getResourceAsStream("/fixtures/city_blueprint/valid_city_blueprint.json")) {
            var json=JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            json.getAsJsonArray("groups").get(0).getAsJsonObject().add("placementRelation",JsonParser.parseString("""
                {"kind":"ALONG_PATCH_BOUNDARY","patchRefs":["land","water"],"groupRefs":[]}
                """));
            var group=new CityBlueprintCodec().read(json).groups().get(0);
            var land=patch("land",new int[][]{{0,0},{0,16},{16,16}});
            var water=patch("water",new int[][]{{16,0},{32,16}});
            var origins=CityPatchBoundaryGuide.origins(group,Map.of("land",land,"water",water),16,16,new BlockPoint(0,0));
            assertEquals(Set.of(new BlockPoint(6,8),new BlockPoint(24,26),new BlockPoint(22,24)),new HashSet<>(origins));
            var distant=patch("water",new int[][]{{96,96}});
            assertTrue(CityPatchBoundaryGuide.origins(group,Map.of("land",land,"water",distant),16,16,new BlockPoint(0,0)).isEmpty());
            assertEquals(origins,CityPatchBoundaryGuide.origins(group,Map.of("land",land,"water",water),16,16,new BlockPoint(0,0)));
        }
    }
}
