package com.rinsing.geomantia.systems.realm_planning.application.access;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanningAreaAccessPolicyTest {
    @Test void stableSurveyIsReusedWhilePermissionsStillRevoke() throws Exception {
        fixture(true,true);
        var config=new PlanningAreaAccessConfig(true,256,512,Set.of("minecraft:overworld"),256,1024);
        var first=GeographicAreaAccess.load(root(),config,256);
        var firstPolicy=policy();
        assertTrue(firstPolicy.sameAccessAs(policy()));
        assertFalse(first.readyCities.isEmpty());
        Files.delete(temp.resolve("geomantia_city_masks/active_planned_structure_registry.json"));
        var second=GeographicAreaAccess.load(root(),config,256);
        assertSame(first.geography,second.geography);
        assertTrue(second.readyCities.isEmpty());
        assertFalse(firstPolicy.sameAccessAs(policy()));
        Path grid=run().resolve("world_feature_grid.json");
        Files.setLastModifiedTime(grid,java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(grid).toMillis()+2000));
        var third=GeographicAreaAccess.load(root(),config,256);
        assertNotSame(second.geography,third.geography);
        assertEquals(second.geography.asJson(),third.geography.asJson());
    }
    @Test void sourceStampIgnoresArtifactsButTracksFallbackCityReports() throws Exception {
        fixture(true,true);
        long before=PlanningAreaAccessPolicy.sourceStamp(root());
        write(run().resolve("exports/nested/city_blueprint.json"),new JsonObject());
        assertEquals(before,PlanningAreaAccessPolicy.sourceStamp(root()));
        Path fallback=run().resolve("city_test_runs/city_0/test_run_manifest.json");
        write(fallback,JsonParser.parseString("{\"status\":\"running\"}").getAsJsonObject());
        long changed=PlanningAreaAccessPolicy.sourceStamp(root());
        assertNotEquals(before,changed);
        Files.delete(fallback);
        assertEquals(before,PlanningAreaAccessPolicy.sourceStamp(root()));
    }
    @Test void preserveOutdoorNeedsStructuresButNotLandUseAndGenerateStillNeedsBoth() throws Exception {
        fixture(true,true);
        Files.delete(temp.resolve("geomantia_city_masks/active_city_land_use_area_plans.json"));
        assertTrue(policy().connectedCityIds().isEmpty());
        long before = PlanningAreaAccessPolicy.sourceStamp(root());
        for (int i=0;i<3;i++) write(run().resolve("city_test_runs/city_"+i+"/steps/blueprint/city_blueprint.json"),
                JsonParser.parseString("{\"outdoorPlan\":{\"mode\":\"PRESERVE\"}}").getAsJsonObject());
        assertNotEquals(before, PlanningAreaAccessPolicy.sourceStamp(root()));
        assertEquals(Set.of("city_0","city_1","city_2"),policy().connectedCityIds());
        assertTrue(policy().revealed("minecraft:overworld",4096,0));
        write(run().resolve("city_test_runs/city_1/steps/blueprint/city_blueprint.json"),
                JsonParser.parseString("{\"outdoorPlan\":{\"mode\":\"GENERATE\"}}").getAsJsonObject());
        assertFalse(policy().revealed("minecraft:overworld",4096,0),"One unprepared city keeps its continent closed");
        Files.delete(temp.resolve("geomantia_city_masks/active_planned_structure_registry.json"));
        assertTrue(policy().connectedCityIds().isEmpty(),"PRESERVE alone must never release a city");
    }
    @Test void viewDemandRequiresLegalGenerationNeighborsAndNewSnapshotRestoresIt() throws Exception {
        fixture(false,false);
        var before=policy();
        assertFalse(before.permitsPlayerTicket("minecraft:overworld",5632/16,0));
        assertFalse(before.permitsPlayerTicket("minecraft:overworld",100000/16,0));
        assertTrue(before.permitsPlayerTicket("minecraft:the_nether",100000/16,0));
        fixture(true,true);
        var after=policy();
        assertTrue(after.permitsPlayerTicket("minecraft:overworld",4096/16,0));
        assertFalse(after.permitsPlayerTicket("minecraft:overworld",3072/16,0),"A legal centre beside a closed neighbor cannot start FULL demand");
    }
    @Test void addonReadinessGatesItsWholeContinentAndInvalidatesAccessStamp() throws Exception {
        fixture(true,true);
        Files.writeString(run().resolve("world_survey_context.json"), "{}");
        var region = new com.rinsing.geomantia.api.regions.ReservedRegion("boss:keep", List.of(
                new com.rinsing.geomantia.api.regions.RegionBounds(3800, 512, 3900, 700)));
        com.rinsing.geomantia.systems.realm_planning.application.reservation.RegionReservationStore.create(run(), "minecraft:overworld", List.of(region));
        long stamp = PlanningAreaAccessPolicy.sourceStamp(root());
        assertFalse(policy().revealed("minecraft:overworld",4096,0));
        assertFalse(policy().permitsChunk("minecraft:overworld",4096/16,0));
        assertTrue(policy().permitsChunk("minecraft:overworld",10240/16,0));
        com.rinsing.geomantia.systems.realm_planning.application.reservation.RegionReservationStore.markReady(run(), "boss:keep");
        assertNotEquals(stamp, PlanningAreaAccessPolicy.sourceStamp(root()));
        assertTrue(policy().revealed("minecraft:overworld",4096,0));
        assertTrue(policy().permitsChunk("minecraft:overworld",4096/16,0));
    }

    @Test void addonOnlyContinentDoesNotRequireAFakeRealmOrCity() throws Exception {
        write(run().resolve("world_feature_grid.json"), GeographicRegionsTest.grid(24, 50, -16, 16, (x,z)->true));
        write(run().resolve("world_survey_manifest.json"), JsonParser.parseString("{\"status\":\"sealed\"}").getAsJsonObject());
        Files.writeString(run().resolve("world_survey_context.json"), "{}");
        var region = new com.rinsing.geomantia.api.regions.ReservedRegion("boss:island", List.of(
                new com.rinsing.geomantia.api.regions.RegionBounds(4096, 0, 4223, 127)));
        com.rinsing.geomantia.systems.realm_planning.application.reservation.RegionReservationStore.create(run(), "minecraft:overworld", List.of(region));
        assertFalse(policy().permitsChunk("minecraft:overworld",4096/16,0));
        com.rinsing.geomantia.systems.realm_planning.application.reservation.RegionReservationStore.markReady(run(), "boss:island");
        assertFalse(policy().permitsChunk("minecraft:overworld",4096/16,0), "T3 must establish that this continent has no pending ordinary realm");
        write(run().resolve("realm_territory_map.json"), JsonParser.parseString("{\"territoryMapId\":\"allocated\",\"territoryCells\":[]}").getAsJsonObject());
        assertTrue(policy().evaluate("minecraft:overworld",4096,0).allowed());
        assertTrue(policy().permitsChunk("minecraft:overworld",4096/16,0));
    }

    @Test void persistedStarterContinentOpensWithoutTAndRetainsPendingCityProtection() throws Exception {
        fixture(false,false);
        var grid=GeographicRegionsTest.grid(-8,95,-16,16,(x,z)->x<52||x>68);
        write(run().resolve("world_feature_grid.json"),grid);
        var area=InitialExplorationArea.continent(GeographicRegions.build(grid,256,1024),0,0);
        write(temp.resolve("geomantia_starter_realm.json"),area.description());
        var access=policy();
        assertTrue(access.evaluate("minecraft:overworld",3000,0).allowed());
        assertTrue(access.revealed("minecraft:overworld",3000,0));
        assertTrue(access.permitsChunk("minecraft:overworld",3000/16,0));
        assertFalse(access.evaluate("minecraft:overworld",5632,0).allowed(),"Existing pending city remains protected");
        assertFalse(access.permitsChunk("minecraft:overworld",5632/16,0));
        assertFalse(access.revealed("minecraft:overworld",60*128,0),"Distant ocean stays fogged");
    }

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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"queued", "running", "post_d4_running", "blocked_by_program", "completed_with_errors"})
    void activatedCityKeepsChunksAvailableDuringConstructionAndRetry(String status) throws Exception {
        fixture(true,true);
        Path queuePath=run().resolve("automation/city_design_queue.json");
        JsonObject queue=read(queuePath);
        queue.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("status",status);
        write(queuePath,queue);
        assertTrue(policy().permitsChunk("minecraft:overworld",4096/16,0));
        Files.delete(temp.resolve("geomantia_city_masks/active_planned_structure_registry.json"));
        assertFalse(policy().permitsChunk("minecraft:overworld",4096/16,0));
    }

    @Test void starterRealmDoesNotOpenOriginContinentWithPendingCities() throws Exception {
        fixture(false,false);
        write(run().resolve("world_feature_grid.json"), GeographicRegionsTest.grid(-8,95,-16,16,(x,z)->x<52||x>68));
        var access = policy();
        assertTrue(access.revealed("minecraft:overworld",128,0));
        assertTrue(access.evaluate("minecraft:overworld",128,0).allowed());
        assertFalse(access.evaluate("minecraft:overworld",4096,0).allowed());
        assertFalse(access.permitsChunk("minecraft:overworld",4096/16,0));
        assertFalse(access.evaluate("minecraft:overworld",5632,0).allowed());
        assertFalse(access.revealed("minecraft:overworld",5632,0));
        assertFalse(access.permitsChunk("minecraft:overworld",5632/16,0));
        assertFalse(access.revealed("minecraft:overworld",60*128,0)); // No automatic distant-ocean release.
    }

    @Test void sealedOriginRegionStaysClosedOutsideStarterRealm() throws Exception {
        write(run().resolve("world_feature_grid.json"), GeographicRegionsTest.grid(-8,40,-16,16,(x,z)->x<30));
        write(run().resolve("world_survey_manifest.json"), JsonParser.parseString("{\"status\":\"sealed\"}").getAsJsonObject());
        assertFalse(policy().evaluate("minecraft:overworld",3000,0).allowed());
        assertFalse(policy().revealed("minecraft:overworld",3900,0)); // Near sea.
        assertFalse(policy().revealed("minecraft:overworld",4600,0));
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
