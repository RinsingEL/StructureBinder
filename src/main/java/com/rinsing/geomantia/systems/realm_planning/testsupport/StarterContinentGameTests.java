package com.rinsing.geomantia.systems.realm_planning.testsupport;

import com.google.gson.*;
import com.rinsing.geomantia.platform.*;
import com.rinsing.geomantia.systems.realm_planning.application.access.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.gametest.GameTestHolder;
import java.nio.file.*;

/** Real chunk scheduling against a persisted starter coastline, in an isolated test world. */
@GameTestHolder("geomantia_starter")
public final class StarterContinentGameTests {
    @GameTest(template="empty",timeoutTicks=400)
    public static void starterCoastlineLoadsBeyondOldRadiusAndKeepsOtherContinentsClosed(GameTestHelper helper) {
        var level=helper.getLevel(); var server=level.getServer();
        Path root=WorldScopedPlanningPaths.realmDebugRoot(server),run=root.resolve("starter_mask_test");
        try {
            Files.createDirectories(run);
            JsonObject grid=new JsonObject(); grid.addProperty("cellStepBlocks",128); grid.addProperty("configHash","fixture");
            JsonArray cells=new JsonArray();
            for(int z=-24;z<=24;z++) for(int x=-24;x<=110;x++) {
                JsonObject cell=new JsonObject(); cell.addProperty("gridX",x); cell.addProperty("gridZ",z);
                cell.addProperty("waterFrac",x<48||x>88?0:1); cells.add(cell);
            }
            grid.add("cells",cells); WorldEntrySurvey.writeAtomic(run.resolve("world_feature_grid.json"),grid);
            JsonObject manifest=JsonParser.parseString("{\"status\":\"sealed\",\"configHash\":\"fixture\",\"config\":{\"dimensionId\":\"minecraft:overworld\"}}").getAsJsonObject();
            WorldEntrySurvey.writeAtomic(run.resolve("world_survey_manifest.json"),manifest);
            var area=InitialExplorationArea.continent(GeographicRegions.build(grid,256,4096),0,0);
            WorldEntrySurvey.writeAtomic(root.getParent().resolve("geomantia_starter_realm.json"),area.description());
            PlanningAreaAccessRuntime.clear(server);
            helper.assertTrue(PlanningAreaAccessRuntime.permitsChunk(level,312,0),"Starter land beyond 2048 must be permitted");
            helper.assertTrue(PlanningAreaAccessRuntime.permitsChunk(level,392,0),"Starter near sea must be permitted");
            helper.assertTrue(!PlanningAreaAccessRuntime.permitsChunk(level,736,0),"Other continent must remain closed");
            var denied=level.getChunkSource().getChunkFuture(736,0,ChunkStatus.FULL,true);
            helper.assertTrue(denied.isDone()&&denied.join().right().isPresent(),"Closed continent must not generate");
            var loaded=level.getChunkSource().getChunkFuture(312,0,ChunkStatus.FULL,true);
            helper.succeedWhen(()->helper.assertTrue(loaded.isDone()&&loaded.join().left().isPresent(),"Starter chunks must actually complete FULL"));
        } catch(Exception error) { helper.fail(error.toString()); }
    }
}
