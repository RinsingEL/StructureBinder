package com.rinsing.geomantia.systems.realm_planning.testsupport;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.GisSampleConfig;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.systems.gis.application.refresh.GisRefreshService;
import com.rinsing.geomantia.systems.gis.application.refresh.RefreshPriority;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.domain.region.AtlasRegionStore;
import com.rinsing.geomantia.systems.realm_planning.RealmPlanningService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;

import java.nio.file.Path;

@GameTestHolder("geomantia")
public final class RealmPlanningGameTests {
    private RealmPlanningGameTests() {
    }

    @GameTest(template = "empty")
    public static void realmPlanningPriorAcceptance(GameTestHelper helper) {
        try {
            ServerLevel level = helper.getLevel();
            BlockPos center = helper.absolutePos(BlockPos.ZERO);
            GisSampleConfig sampleConfig = GisSampleConfig.defaults().withCellStepBlocks(128);
            GisRefreshService gisService = new GisRefreshService(sampleConfig, GisClassifierConfig.defaults(),
                    new AtlasRegionStore(sampleConfig), new MinecraftPriorAtlasSampler(level));
            var refresh = gisService.refresh(level.dimension().location().toString(), center.getX(), center.getZ(),
                    8, SampleMode.PRIOR, RefreshPriority.DEBUG, Path.of("realm_debug").resolve("gametest_gis"));
            JsonObject response = new RealmPlanningService(Path.of("realm_debug"))
                    .runAcceptance(refresh, "realm_gametest_prior", 3, null, true);
            if (!response.get("passed").getAsBoolean()) {
                helper.fail("Realm planning acceptance report failed: " + response);
                return;
            }
            helper.succeed();
        } catch (Exception ex) {
            helper.fail("Realm planning GameTest failed: " + ex.getMessage());
        }
    }
}
