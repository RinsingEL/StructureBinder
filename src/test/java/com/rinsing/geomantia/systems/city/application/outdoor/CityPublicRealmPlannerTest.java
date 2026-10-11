package com.rinsing.geomantia.systems.city.application.outdoor;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.algorithm.landuse.CityDistrictPlanner;
import com.rinsing.geomantia.systems.city.application.landuse.*;
import com.rinsing.geomantia.systems.city.domain.blueprint.CityPublicRealmSpec;
import com.rinsing.geomantia.systems.city.domain.landuse.*;
import com.rinsing.geomantia.systems.city.domain.model.*;
import com.rinsing.geomantia.systems.city.infrastructure.world.landuse.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.rinsing.geomantia.systems.city.application.landuse.CityLandUseSurfacePrintPlan.*;

class CityPublicRealmPlannerTest {
    private static final int X=-112,Z=-96;
    static JsonArray examples(){return JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(CityPublicRealmPlannerTest.class.getResourceAsStream("/data/geomantia/city/public_realm/examples.json")),StandardCharsets.UTF_8)).getAsJsonArray();}
    static LandUseTerrainField terrain(boolean water){List<LandUseTerrainField.Cell> cells=new ArrayList<>();for(int z=-128;z<32;z+=8)for(int x=-144;x<16;x+=8)cells.add(new LandUseTerrainField.Cell(x/8,z/8,x,z,8,64,0,0,0,water,0,20,"minecraft:plains","plain","",true));return new LandUseTerrainField(LandUseTerrainField.SCHEMA,"city",new BlockBounds(-144,-128,15,31),8,cells);}
    static Set<BlockPoint> ground(){Set<BlockPoint> s=new HashSet<>();for(int z=Z;z<Z+80;z++)for(int x=X;x<X+96;x++)s.add(new BlockPoint(x,z));return s;}
    static CityDistrictPlanner.Result district(){return new CityDistrictPlanner.Result(ground(),ground(),ground(),Set.of(),Map.of(),0,0,Map.of("campus",new BlockBounds(X,Z,X+95,Z+79)),List.of(new CityDistrictPlanner.Result.PublicSpace("campus",new BlockBounds(X,Z,X+95,Z+79),"garden")));}
    static CityLandUseSurfacePrintPlan source(){List<FeatureCell> f=new ArrayList<>();for(var p:ground())f.add(new FeatureCell("terrace",p.x(),p.z(),"minecraft:grass_block",0,FeatureKind.TERRACE_GROUND,HorizontalFacing.NONE,64));return new CityLandUseSurfacePrintPlan(SCHEMA,"city","source","",List.of(),List.of(),f);}
    static JsonObject spec(JsonObject plan){JsonObject root=new JsonObject();root.addProperty("schema","city_public_realm.v1");root.addProperty("catalog","academy");root.addProperty("seed",42);JsonArray spaces=new JsonArray();if(plan!=null){JsonObject space=new JsonObject();space.addProperty("id","trial");space.addProperty("owner","campus");space.add("plan",plan);spaces.add(space);}root.add("spaces",spaces);return root;}
    static CityPublicRealmPlanner.Result run(JsonObject spec){return new CityPublicRealmPlanner().plan(source(),district(),terrain(false),List.of(),new CityPublicRealmSpec(spec));}
    @Test void allVerifiedRecipesCompileOnNegativeCoordinatesAndRoundTrip(){
        for(JsonElement e:examples()) {
            var s=spec(e.getAsJsonObject().getAsJsonObject("plan"));var a=run(s);var b=run(s);
            assertEquals(a.surface(),b.surface());assertEquals(a.report(),b.report());
            assertTrue(a.report().getAsJsonArray("placements").size()>=13,e.getAsJsonObject().get("id").getAsString());
            var codec=new CityLandUseSurfacePrintPlanCodec();assertEquals(a.surface(),codec.fromJson(codec.toJson(a.surface())));
            assertTrue(a.surface().featureCells().stream().anyMatch(f->f.blockId().contains("[")));
            assertTrue(a.surface().featureCells().stream().filter(f->f.kind()==FeatureKind.AUTHORED_BLOCK).allMatch(f->f.targetSurfaceY()==64));
        }
    }
    @Test void dryFringeDoesNotCreateTerracesAndKeepsRoadReserveClear(){
        JsonObject s=spec(null);s.add("fringe",JsonParser.parseString("{\"width\":20,\"layeredPlanting\":true,\"pools\":{\"trees\":\"outer_trees\",\"flowers\":\"flower_patches\"},\"maxPlacements\":80,\"slotLimits\":{\"trees\":20,\"flowers\":60}}").getAsJsonObject());
        var result=run(s);assertTrue(result.report().getAsJsonArray("placements").size()>20);
        assertEquals(7680,result.surface().featureCells().stream().filter(f->f.kind()==FeatureKind.TERRACE_GROUND).count());
        assertTrue(result.report().getAsJsonArray("placements").asList().stream().allMatch(e->{var p=e.getAsJsonObject();return !ground().contains(new BlockPoint(p.get("x").getAsInt(),p.get("z").getAsInt()));}));
    }
    @Test void compositionRejectsWaterBadOwnerAndBadWeights(){
        var s=spec(examples().get(0).getAsJsonObject().getAsJsonObject("plan"));
        assertThrows(IllegalArgumentException.class,()->new CityPublicRealmPlanner().plan(source(),district(),terrain(true),List.of(),new CityPublicRealmSpec(s)));
        s.getAsJsonArray("spaces").get(0).getAsJsonObject().addProperty("owner","missing");assertThrows(IllegalArgumentException.class,()->run(s));
        var a=CityPublicRealmAssets.load("academy");assertThrows(IllegalArgumentException.class,()->a.choices(JsonParser.parseString("[{\"assetId\":\"studio:mg-50-v01\",\"weight\":0}]").getAsJsonArray()));
    }
    @Test void rotationPreservesPropertiesAndRotatesConnections(){
        assertEquals("minecraft:oak_log[axis=z]",CityPublicRealmAssets.rotateState("minecraft:oak_log[axis=x]",90));
        assertEquals("minecraft:stone_brick_stairs[facing=east,half=top,waterlogged=false]",CityPublicRealmAssets.rotateState("minecraft:stone_brick_stairs[facing=north,half=top,waterlogged=false]",90));
        assertEquals("minecraft:oak_fence[east=true,north=false]",CityPublicRealmAssets.rotateState("minecraft:oak_fence[north=true,west=false]",90));
    }
    @Test void aiExitFreezesHalfSlabsAndRejectsUnconnectedOrReservedCorridor(){
        JsonObject s=spec(null);s.add("externalAccess",JsonParser.parseString("{id:'port',width:5,waypoints:[{x:-110,z:-20,surfaceY:65},{x:-110,z:0,surfaceY:64.5}],landing:{minX:-114,minZ:-4,maxX:-106,maxZ:4},harborReservation:{minX:-114,minZ:5,maxX:-100,maxZ:12}}").getAsJsonObject());
        List<FeatureCell> cells=new ArrayList<>(source().featureCells());cells.removeIf(f->f.x()==-110&&f.z()==-20);
        cells.add(new FeatureCell("street",-110,-20,"minecraft:stone_slab",0,FeatureKind.ROAD_SLAB,HorizontalFacing.NONE,64));
        var src=new CityLandUseSurfacePrintPlan(SCHEMA,"city","source","",List.of(),List.of(),cells);
        var out=new CityPublicRealmPlanner().plan(src,district(),terrain(false),List.of(),new CityPublicRealmSpec(s));
        assertTrue(out.surface().featureCells().stream().anyMatch(f->f.sourceId().startsWith("public_realm::access/")&&f.blockId().contains("type=bottom")));
        assertTrue(out.surface().featureCells().stream().anyMatch(f->f.sourceId().equals("street")));
        assertThrows(IllegalArgumentException.class,()->run(s));
        var reservation=new LandUseAreaPlan.CorridorExclusion("protected",new BlockBounds(-112,-8,-108,-6),"wall");
        assertThrows(IllegalArgumentException.class,()->new CityPublicRealmPlanner().plan(src,district(),terrain(false),List.of(reservation),new CityPublicRealmSpec(s)));
    }
    @Test void frozenGardenExecutesThroughAllNegativeOwnerChunksWithoutLosingAuthoredStates(){
        var compiled=run(spec(examples().get(1).getAsJsonObject().getAsJsonObject("plan")));
        var area=new LandUseAreaPlanCodec().withComputedHash(new LandUseAreaPlan(LandUseAreaPlan.SCHEMA,
                com.rinsing.geomantia.systems.city.domain.landuse.rules.LandUseRuleCatalog.RULE_VERSION,"city","",terrain(false).planningBounds(),List.of(),List.of(),List.of(),List.of(),List.of()));
        var s=compiled.surface();var frozen=new CityLandUseSurfacePrintPlanCodec().withComputedHash(new CityLandUseSurfacePrintPlan(s.schema(),s.cityId(),area.planHash(),"",s.areas(),s.sharedBoundarySpans(),s.featureCells()));
        var compiler=new CityLandUseChunkCompiler();var prepared=compiler.prepare(area,frozen);assertTrue(prepared.indexedOwnerCount()>1);
        Map<String,String> actual=new HashMap<>();int[] owner=new int[2];
        CityLandUseChunkExecutor.ExecutionWorld world=new CityLandUseChunkExecutor.ExecutionWorld(){
            public CityLandUseChunkExecutor.ColumnSample sampleColumn(int x,int z){return new CityLandUseChunkExecutor.ColumnSample(64,"minecraft:dirt",true);}
            public boolean isKnownBlock(String b){return CityPublicRealmAssets.validState(b);}
            public boolean ensureCanWrite(int x,int y,int z){return Math.floorDiv(x,16)==owner[0]&&Math.floorDiv(z,16)==owner[1];}
            public CityLandUseChunkExecutor.TargetState inspect(int x,int y,int z){return new CityLandUseChunkExecutor.TargetState(actual.getOrDefault(x+":"+y+":"+z,"original"),y>64&&!actual.containsKey(x+":"+y+":"+z));}
            public boolean setBlock(int x,int y,int z,String b){actual.put(x+":"+y+":"+z,b);return true;}
            public boolean restoreBlock(int x,int y,int z,Object old){actual.put(x+":"+y+":"+z,old.toString());return true;}
        };
        for(int z=-6;z<=-2;z++)for(int x=-7;x<=-2;x++) {
            owner[0]=x;owner[1]=z;var fragment=compiler.compilePrepared(prepared,x,z);
            assertEquals(CityLandUseChunkExecutor.Status.APPLIED,new CityLandUseChunkExecutor().execute(fragment,world,CityLandUseChunkExecutor.GenerationEligibility.FIRST_WORLDGEN_FEATURES).status(),"chunk "+x+","+z);
        }
        for(var f:frozen.featureCells())if(f.kind()==FeatureKind.AUTHORED_BLOCK)assertEquals(f.blockId(),actual.get(f.x()+":"+(f.targetSurfaceY()+f.surfaceOffset())+":"+f.z()),f.sourceId());
    }
}
