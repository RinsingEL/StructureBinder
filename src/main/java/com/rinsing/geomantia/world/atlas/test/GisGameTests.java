package com.rinsing.geomantia.world.atlas.test;

import com.rinsing.geomantia.world.atlas.GisClassifierConfig;
import com.rinsing.geomantia.world.atlas.GisSampleConfig;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;

import java.nio.file.Path;

@GameTestHolder("geomantia")
public final class GisGameTests {
    private GisGameTests() {
    }

    @GameTest(template = "empty")
    public static void gisPriorSyntheticBaseline(GameTestHelper helper) {
        try {
            GisTestRunner runner = new GisTestRunner(GisSampleConfig.defaults(), GisClassifierConfig.defaults());
            Path debugRoot = Path.of("gis_debug");
            for (GisTestCase testCase : GisTestCase.baselineCases()) {
                var report = runner.runCase(testCase, debugRoot);
                if (!report.passed()) {
                    helper.fail("GIS baseline case failed: " + testCase.id() + " " + report.failures());
                    return;
                }
            }
            helper.succeed();
        } catch (Exception ex) {
            helper.fail("GIS baseline GameTest failed: " + ex.getMessage());
        }
    }
}
