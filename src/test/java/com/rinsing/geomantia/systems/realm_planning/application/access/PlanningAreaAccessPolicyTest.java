package com.rinsing.geomantia.systems.realm_planning.application.access;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanningAreaAccessPolicyTest {
    @TempDir Path temp;
    Path root() { return temp.resolve("realm_debug"); }
    Path run() { return root().resolve("run"); }
    PlanningAreaAccessPolicy policy() { return new PlanningAreaAccessPolicy(root(),new PlanningAreaAccessConfig(true,256,512,Set.of("minecraft:overworld"),256,1024),192); }
    void write(Path path,JsonObject json) throws Exception { Files.createDirectories(path.getParent()); Files.writeString(path,json.toString()); }
    JsonObject read(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    static JsonArray strings(String...v) { JsonArray a=new JsonArray(); for(String s:v)a.add(s); return a; }
    void fixture(boolean secondReady,boolean closed) throws Exception {
        write(run().resolve("world_feature_grid.json"),GeographicRegionsTest.grid(24,95,-16,16,(x,z)->x<52||x>68));
        JsonObject manifest=JsonParser.parseString("{'config':{'dimensionId':'minecraft:overworld'}}").getAsJsonObject(); manifest.addProperty("status","sealed");write(run().resolve("world_survey_manifest.json"),manifest);
        JsonObject territory=new JsonObject(); territory.addProperty("territoryMapId","t"); JsonArray cells=new JsonArray();
        for(int x=24;x<=95;x++) { JsonObject c=new JsonObject(); c.addProperty("gridX",x); c.addProperty("gridZ",0); c.addProperty("realmId",x<60?"a":"b"); c.addProperty("status","owned"); cells.add(c); }
        territory.add("territoryCells",cells); write(run().resolve("realm_territory_map.json"),territory);
        JsonObject registry=new JsonObject(); registry.addProperty("territoryMapId","t");registry.addProperty("finalizedTerritoryIdentity",GeographicAreaAccess.identity(run().resolve("realm_territory_map.json"))); registry.add("finalizedRealmIds",closed?strings("a","b"):strings("b"));
        JsonArray seeds=new JsonArray(),items=new JsonArray(),active=new JsonArray(),land=new JsonArray();
        for(int i=0;i<3;i++) { String id="city_"+i; int x=new int[]{4096,5632,10240}[i];
            JsonObject s=new JsonObject(); s.addProperty("citySeedId",id);s.addProperty("realmId",i==2?"b":"a");s.addProperty("theoreticalScale","outpost");s.addProperty("planningRadiusCells",1);
            JsonObject pos=new JsonObject();pos.addProperty("x",x);pos.addProperty("z",0);s.add("anchorBlock",pos);seeds.add(s);
            JsonObject q=new JsonObject();q.addProperty("citySeedId",id);q.addProperty("status",i==1&&!secondReady?"pending":"waiting_for_generation");items.add(q);
            JsonObject r=new JsonObject();r.addProperty("runId","run");r.addProperty("cityId",id);JsonArray structures=new JsonArray();JsonObject b=new JsonObject();b.add("plannedFootprint",CityPlanningReservation.centered(id,x,0,8).design().asJson());structures.add(b);r.add("plannedStructures",structures);active.add(r);
            JsonObject l=new JsonObject();l.addProperty("cityId",id);l.addProperty("dimensionId","minecraft:overworld");JsonObject area=new JsonObject();JsonArray areas=new JsonArray();areas.add(new JsonObject());area.add("areas",areas);l.add("areaPlan",area);land.add(l);
        }
        registry.add("citySeeds",seeds);write(run().resolve("city_seed_registry.json"),registry);
        JsonObject queue=new JsonObject();queue.add("items",items);write(run().resolve("automation/city_design_queue.json"),queue);
        JsonObject masks=new JsonObject();masks.add("registries",active);write(temp.resolve("geomantia_city_masks/active_planned_structure_registry.json"),masks);
        JsonObject terrain=new JsonObject();terrain.add("plans",land);write(temp.resolve("geomantia_city_masks/active_city_land_use_area_plans.json"),terrain);
    }
    @Test void initialAndUnmanagedAreasRemainAvailableButUnknownTerrainDoesNotGenerate() {
        assertTrue(policy().evaluate("minecraft:overworld",256,0).allowed()); assertFalse(policy().evaluate("minecraft:overworld",257,0).allowed());
        assertTrue(policy().permitsChunk("minecraft:overworld",0,0)); assertFalse(policy().permitsChunk("minecraft:overworld",500,0));
        assertTrue(policy().permitsChunk("minecraft:the_nether",500,0));
    }
    @Test void aSinglePendingCityLocksItsWholeContinentButNotAnotherContinent() throws Exception {
        fixture(false,true);var policy=policy();
        assertFalse(policy.revealed("minecraft:overworld",4096,0)); assertFalse(policy.evaluate("minecraft:overworld",4096,0).allowed());
        assertTrue(policy.revealed("minecraft:overworld",10240,0)); assertTrue(policy.evaluate("minecraft:overworld",10240,0).allowed());
        assertFalse(policy.permitsChunk("minecraft:overworld",5632/16,0)); assertTrue(policy.permitsChunk("minecraft:overworld",10240/16,0));
    }
    @Test void allCitiesReadyOpensFullContinentAndAttachedSeaWithoutCorridors() throws Exception {
        fixture(true,true);var policy=policy();
        assertTrue(policy.evaluate("minecraft:overworld",4096,1024).allowed());
        assertTrue(policy.revealed("minecraft:overworld",52*128,0));
        assertTrue(policy.permitsChunk("minecraft:overworld",5632/16,0));
        // Complete fog boundary is visible, but movement stops before view/dependency loads cross it.
        assertTrue(policy.revealed("minecraft:overworld",24*128,0)); assertFalse(policy.evaluate("minecraft:overworld",24*128,0).allowed());
    }
    @Test void queueSuccessWithoutClosedRosterOrActivatedStructuresCannotRelease() throws Exception {
        fixture(true,false); assertFalse(policy().revealed("minecraft:overworld",4096,0));
        fixture(true,true); Files.delete(temp.resolve("geomantia_city_masks/active_planned_structure_registry.json"));
        assertFalse(policy().revealed("minecraft:overworld",4096,0));
    }
    @Test void reopeningAPlanningSessionRevokesItsRegionAndDeletingItChangesCacheStamp() throws Exception {
        fixture(true,true);long before=PlanningAreaAccessPolicy.sourceStamp(root());
        JsonObject session=JsonParser.parseString("{'status':'open','realmId':'a','territoryMapId':'t'}").getAsJsonObject();
        session.addProperty("territoryIdentity",GeographicAreaAccess.identity(run().resolve("realm_territory_map.json")));
        Path path=run().resolve("realm_t4_patch_planning_new/planning_session.json");write(path,session);
        assertNotEquals(before,PlanningAreaAccessPolicy.sourceStamp(root()));assertFalse(policy().revealed("minecraft:overworld",4096,0));
        long opened=PlanningAreaAccessPolicy.sourceStamp(root());Files.delete(path);assertNotEquals(opened,PlanningAreaAccessPolicy.sourceStamp(root()));
        assertTrue(policy().revealed("minecraft:overworld",4096,0));
    }
    @Test void corruptGridFailsClosedAndOldConfigReceivesGeographicDefaults() throws Exception {
        fixture(true,true);Files.writeString(run().resolve("world_feature_grid.json"),"broken");assertFalse(policy().revealed("minecraft:overworld",4096,0));
        Path path=temp.resolve("config.json");Files.writeString(path,"{'schema':'geomantia_planning_area_access.v0.1','enabled':true}");
        var c=PlanningAreaAccessConfig.loadOrCreate(path);assertEquals(1024,c.nearSeaDistanceBlocks());assertEquals(4096,c.oceanRegionSpanBlocks());
    }
}
