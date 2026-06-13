package com.rinsing.geomantia.systems.realm_planning.testsupport;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.realm_planning.RealmPlanningService;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveyRunner;
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
            WorldSurveyRunner.Config config = new WorldSurveyRunner.Config(
                    "realm_gametest_prior",
                    level.dimension().location().toString(),
                    Long.toString(level.getSeed()),
                    level.getWorldBorder().getSize(),
                    center.getX(),
                    center.getZ(),
                    1024,
                    128,
                    RealmPlanningService.DEFAULT_MICRO_SAMPLE_STRIDE_BLOCKS,
                    WorldSurveyRunner.DEFAULT_LOCAL_SLOPE_RADIUS_BLOCKS,
                    SampleMode.PRIOR,
                    WorldSurveyRunner.ResumePolicy.RESCAN
            );
            var survey = new WorldSurveyRunner(Path.of("realm_debug"), GisClassifierConfig.defaults())
                    .run(config, new MinecraftPriorAtlasSampler(level));
            JsonObject response = new RealmPlanningService(Path.of("realm_debug"))
                    .runAcceptance(survey, 3, null, true, "smoke");
            JsonObject report = response.getAsJsonObject("acceptanceReport");
            if (!report.getAsJsonObject("stageResults").get("T4").getAsBoolean()
                    || !report.getAsJsonObject("artifacts").has("scoreManifest")) {
                helper.fail("Realm planning smoke did not reach T4 score artifacts: " + response);
                return;
            }
            helper.succeed();
        } catch (Exception ex) {
            helper.fail("Realm planning GameTest failed: " + ex.getMessage());
        }
    }
}
