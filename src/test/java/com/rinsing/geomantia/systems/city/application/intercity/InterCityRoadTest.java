package com.rinsing.geomantia.systems.city.application.intercity;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.thirdparty.roadweaver.BoundedRoadPathfinder;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.rinsing.geomantia.systems.city.application.intercity.CityExitRoadPlanner.*;

class InterCityRoadTest {
    @Test void networkIsStableMinimalAndNeverCrossesRealms() {
        var cities=List.of(city("a","r",0,0),city("b","r",100,0),city("c","r",200,0),city("d","other",101,0));
        var planner=new InterCityNetwork();var network=planner.plan(cities);
        assertEquals(2,network.size());assertTrue(network.stream().allMatch(l->l.a().realm().equals(l.b().realm())));
        var reversed=new ArrayList<>(cities);Collections.reverse(reversed);assertEquals(network,planner.plan(reversed));
        assertTrue(planner.plan(List.of(cities.get(0))).isEmpty());
    }
    @Test void fullWidthPathAvoidsObstaclesAndReachesOffLatticeEndpoint() {
        BlockBounds obstacle=new BlockBounds(40,-6,80,6);
        var result=new BoundedRoadPathfinder().find(new BlockPoint(1,0),new BlockPoint(101,3),new BlockBounds(-32,-80,140,80),16,3,4000,
                new BoundedRoadPathfinder.Terrain(){public double height(int x,int z){return 64;}public boolean blocked(int x,int z){return obstacle.contains(x,z);}});
        assertTrue(result.success(),result.reason());assertEquals(new BlockPoint(101,3),result.path().get(result.path().size()-1));
        for(var p:InterCityRoutePlanner.densify(result.path()))for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)assertFalse(obstacle.contains(p.x()+x,p.z()+z));
    }
    @Test void blockedEndpointAndBudgetNeverProducePartialSuccess() {
        var finder=new BoundedRoadPathfinder();var domain=new BlockBounds(-20,-20,120,120);
        var terrain=new BoundedRoadPathfinder.Terrain(){public double height(int x,int z){return 0;}public boolean blocked(int x,int z){return x==100&&z==100;}};
        assertEquals("ENDPOINT_BLOCKED",finder.find(new BlockPoint(0,0),new BlockPoint(100,100),domain,1,0,10,terrain).reason());
        var exhausted=finder.find(new BlockPoint(0,0),new BlockPoint(99,99),domain,1,0,1,terrain);
        assertEquals("SEARCH_BUDGET_EXHAUSTED",exhausted.reason());assertTrue(exhausted.path().isEmpty());
    }
    @Test void oceanCreatesOnlyTwoCityFacingDryShoreSections() {
        var terrain=waterTerrain(70,180,true);
        var route=new InterCityRoutePlanner().plan(new BlockPoint(0,0),new BlockPoint(256,0),terrain);
        assertEquals("SHORE_TERMINATED",route.status());assertEquals(2,route.sections().size());
        assertEquals(new BlockPoint(0,0),route.sections().get(0).get(0));
        var last=route.sections().get(1);assertEquals(new BlockPoint(256,0),last.get(last.size()-1));
        for(var section:route.sections())for(var p:InterCityRoutePlanner.densify(section))for(int x=-3;x<=3;x++)assertFalse(terrain.sample(p.x()+x,p.z()).water());
    }
    @Test void narrowRiverRemainsConnectedButWideWaterDoesNotBecomeEndlessBridge() {
        var planner=new InterCityRoutePlanner();
        assertEquals("LAND_CONNECTED",planner.plan(new BlockPoint(0,0),new BlockPoint(256,0),waterTerrain(100,115,false)).status());
        assertEquals("SHORE_TERMINATED",planner.plan(new BlockPoint(0,0),new BlockPoint(256,0),waterTerrain(70,180,false)).status());
        assertEquals("DISTANCE_LIMIT",planner.plan(new BlockPoint(0,0),new BlockPoint(40000,0),waterTerrain(70,180,false)).status());
    }
    @Test void derivedExitConnectsStreetCrossesWallAndPreservesAcceptedDesign() {
        JsonObject anchors=JsonParser.parseString("""
                {"cityId":"a","anchors":[{"plannedFootprint":{"minX":30,"minZ":20,"maxX":50,"maxZ":35}}],"streetBands":[]}
                """).getAsJsonObject();
        anchors.getAsJsonArray("streetBands").add(road("main","city",new BlockPoint(10,50),new BlockPoint(60,50)));
        JsonObject original=anchors.deepCopy();JsonObject wall=wall();
        var network=new InterCityNetwork().plan(List.of(city("a","r",50,50),city("b","r",400,50)));
        var exits=new CityExitRoadPlanner().plan("a",anchors,wall,new BlockBounds(-20,-20,140,140),network);
        assertEquals(1,exits.getAsJsonArray("portals").size(),exits.toString());
        var portal=exits.getAsJsonArray("portals").get(0).getAsJsonObject();
        assertTrue(point(portal.getAsJsonObject("point")).x()>104);
        assertTrue(point(portal.getAsJsonObject("gate")).z()>=20);
        assertEquals(original,anchors);assertEquals(original,withoutDerived(append(anchors,exits)));
        assertTrue(exits.getAsJsonArray("streetBands").asList().stream().anyMatch(e->{var b=bounds(e.getAsJsonObject().getAsJsonObject("bounds"));return b.minX()<100&&b.maxX()>100;}));
    }
    @Test void impossibleExitIsReportedWithoutInventingRoadOrChangingInput() {
        JsonObject anchors=JsonParser.parseString("{\"anchors\":[],\"streetBands\":[]}").getAsJsonObject();
        var exits=new CityExitRoadPlanner().plan("a",anchors,wall(),new BlockBounds(-20,-20,140,140),new InterCityNetwork().plan(List.of(city("a","r",50,50),city("b","r",400,50))));
        assertTrue(exits.getAsJsonArray("portals").isEmpty());assertEquals(1,exits.getAsJsonArray("unresolvedLinks").size());
    }
    private static InterCityNetwork.City city(String id,String realm,int x,int z){return new InterCityNetwork.City(id,realm,new BlockPoint(x,z));}
    private static InterCityRoutePlanner.Terrain waterTerrain(int min,int max,boolean ocean){return new InterCityRoutePlanner.Terrain(){
        public InterCityRoutePlanner.Sample sample(int x,int z){boolean wet=x>=min&&x<=max;return new InterCityRoutePlanner.Sample(64,wet,wet&&ocean);}
        public boolean blocked(int x,int z){return false;}};}
    private static JsonObject wall(){
        JsonObject wall=new JsonObject();wall.addProperty("wallCorridorHalfWidthBlocks",4);JsonArray lines=new JsonArray(),masks=new JsonArray();
        String[] sides={"north","east","south","west"};BlockPoint[] corners={new BlockPoint(0,0),new BlockPoint(100,0),new BlockPoint(100,100),new BlockPoint(0,100)};
        for(int i=0;i<4;i++){var a=corners[i];var b=corners[(i+1)%4];JsonObject l=new JsonObject();l.addProperty("sideHint",sides[i]);l.add("from",a.asJson());l.add("to",b.asJson());l.add("blockBounds",json(bandBounds(a,b,4)));lines.add(l);JsonObject m=new JsonObject();m.add("blockBounds",json(bandBounds(a,b,4)));masks.add(m);}
        wall.add("wallLine",lines);wall.add("wallCorridorMask",masks);return wall;
    }
}
