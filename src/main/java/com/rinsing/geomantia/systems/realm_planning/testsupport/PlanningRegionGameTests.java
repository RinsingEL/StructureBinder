package com.rinsing.geomantia.systems.realm_planning.testsupport;

import com.google.gson.*;
import com.rinsing.geomantia.platform.PlanningAreaAccessRuntime;
import com.rinsing.geomantia.platform.WorldScopedPlanningPaths;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.gametest.GameTestHolder;
import java.nio.file.*;

/** Dedicated namespace: run in an isolated GameTest directory with normal access protection enabled. */
@GameTestHolder("geomantia_regions")
public final class PlanningRegionGameTests {
    @GameTest(template="empty", timeoutTicks=400)
    public static void deniedChunkCompletesAndRetriesAfterRegionRelease(GameTestHelper helper) {
        var level=helper.getLevel(); var server=level.getServer(); int chunkX=600,chunkZ=0;
        Path run=WorldScopedPlanningPaths.realmDebugRoot(server).resolve("region_gate_test");
        try {
            helper.assertTrue(!PlanningAreaAccessRuntime.permitsChunk(level,chunkX,chunkZ),"Unknown region should be locked");
            var denied=level.getChunkSource().getChunkFuture(chunkX,chunkZ,ChunkStatus.FULL,true);
            helper.assertTrue(denied.isDone() && denied.join().right().isPresent(),"Denied chunk must finish unavailable without hanging");
            helper.assertTrue(level.getChunkSource().chunkMap.read(new net.minecraft.world.level.ChunkPos(chunkX,chunkZ)).join().isEmpty(),"Denied chunk must not be persisted");
            Files.createDirectories(run);
            JsonObject grid=new JsonObject();grid.addProperty("cellStepBlocks",128);JsonArray cells=new JsonArray();
            for(int z=-16;z<=16;z++)for(int x=60;x<=90;x++){JsonObject c=new JsonObject();c.addProperty("gridX",x);c.addProperty("gridZ",z);c.addProperty("waterFrac",0);cells.add(c);}
            grid.add("cells",cells);write(run.resolve("world_feature_grid.json"),grid);
            JsonObject manifest=new JsonObject();JsonObject config=new JsonObject();config.addProperty("dimensionId",level.dimension().location().toString());manifest.addProperty("status","sealed");manifest.add("config",config);write(run.resolve("world_survey_manifest.json"),manifest);
            JsonObject territory=new JsonObject();territory.addProperty("territoryMapId","test_territory");JsonArray owned=new JsonArray();
            JsonObject cell=new JsonObject();cell.addProperty("gridX",75);cell.addProperty("gridZ",0);cell.addProperty("status","owned");cell.addProperty("realmId","test_realm");owned.add(cell);territory.add("territoryCells",owned);write(run.resolve("realm_territory_map.json"),territory);
            JsonObject registry=new JsonObject();registry.addProperty("territoryMapId","test_territory");registry.addProperty("finalizedTerritoryIdentity","sha256:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(run.resolve("realm_territory_map.json")))));JsonArray closed=new JsonArray();closed.add("test_realm");registry.add("finalizedRealmIds",closed);registry.add("citySeeds",new JsonArray());write(run.resolve("city_seed_registry.json"),registry);
            PlanningAreaAccessRuntime.clear(server);
            helper.assertTrue(PlanningAreaAccessRuntime.permitsChunk(level,chunkX,chunkZ),"Completed geographic region should permit retry");
            var retry=level.getChunkSource().getChunkFuture(chunkX,chunkZ,ChunkStatus.FULL,true);
            helper.succeedWhen(()->helper.assertTrue(retry.isDone() && retry.join().left().isPresent(),"Previously denied holder must retry after release"));
        }catch(Exception exception){helper.fail(exception.toString());}
    }
    private static void write(Path path,JsonObject value)throws Exception{Files.writeString(path,value.toString());}
}
