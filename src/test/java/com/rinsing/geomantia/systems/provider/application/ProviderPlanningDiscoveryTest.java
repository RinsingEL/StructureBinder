package com.rinsing.geomantia.systems.provider.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderPlanningDiscoveryTest {
    @TempDir
    Path debugRoot;

    @Test
    void startsTheExistingWEntryWhenCurrentWorldHasNoRun() throws Exception {
        var step = new ProviderPlanningDiscovery(debugRoot, 42L).nextStep();

        assertEquals(ProviderPlanningDiscovery.Stage.W, step.stage());
        assertEquals("provider_2a_r8192", step.runId());
        assertEquals("realm_w_refresh", step.nextAction());
    }

    @Test
    void requiresAllRealmT4BeforeConsumingAnExistingPartialCityQueue() throws Exception {
        Path run = sealedRun("run_a");
        write(run, "realm_profiles.json", """
                [{"realmId":"realm_a"},{"realmId":"realm_b"}]
                """);
        write(run, "realm_coordinate_selections.json", """
                [{"realmId":"realm_a"},{"realmId":"realm_b"}]
                """);
        write(run, "t3_report.json", "{}");
        write(run, "realm_territory_map.json", "{}");
        write(run, "city_seed_registry.json", """
                {"citySeeds":[{"citySeedId":"city_a","realmId":"realm_a","role":"capital"}]}
                """);
        write(run, "automation/city_design_queue.json", """
                {"runId":"run_a","status":"waiting_for_agent","currentCitySeedId":"city_a",
                 "nextAction":"city_plan_d3","items":[{"citySeedId":"city_a","realmId":"realm_a",
                 "status":"waiting_for_agent"}]}
                """);

        var step = new ProviderPlanningDiscovery(debugRoot, 42L).nextStep();

        assertEquals(ProviderPlanningDiscovery.Stage.T4, step.stage());
        assertEquals("realm_b", step.realmId());
    }

    @Test
    void consumesTheExistingCityQueueOnlyAfterEveryRealmCompletedT4() throws Exception {
        Path run = sealedRun("run_b");
        write(run, "realm_profiles.json", """
                [{"realmId":"realm_a"},{"realmId":"realm_b"}]
                """);
        write(run, "realm_coordinate_selections.json", """
                [{"realmId":"realm_a"},{"realmId":"realm_b"}]
                """);
        write(run, "t3_report.json", "{}");
        write(run, "realm_territory_map.json", "{}");
        write(run, "city_seed_registry.json", """
                {"citySeeds":[
                 {"citySeedId":"city_a","realmId":"realm_a","role":"capital"},
                 {"citySeedId":"city_b","realmId":"realm_b","role":"capital"}]}
                """);
        write(run, "automation/city_design_queue.json", """
                {"runId":"run_b","status":"waiting_for_agent","currentCitySeedId":"city_a",
                 "nextAction":"city_plan_d3","items":[
                 {"citySeedId":"city_a","realmId":"realm_a","status":"waiting_for_agent"},
                 {"citySeedId":"city_b","realmId":"realm_b","status":"pending"}]}
                """);

        var step = new ProviderPlanningDiscovery(debugRoot, 42L).nextStep();

        assertEquals(ProviderPlanningDiscovery.Stage.CITY, step.stage());
        assertEquals("city_a", step.citySeedId());
        assertEquals("city_plan_d3", step.nextAction());
    }

    @Test
    void callsUnifiedT3OnlyAfterEveryProfileHasAT2Selection() throws Exception {
        Path run = sealedRun("run_c");
        write(run, "realm_profiles.json", """
                [{"realmId":"realm_a"},{"realmId":"realm_b"}]
                """);
        write(run, "realm_coordinate_selections.json", """
                [{"realmId":"realm_a"},{"realmId":"realm_b"}]
                """);

        var step = new ProviderPlanningDiscovery(debugRoot, 42L).nextStep();

        assertEquals(ProviderPlanningDiscovery.Stage.T3, step.stage());
        assertEquals("realm_t3_expand", step.nextAction());
    }

    private Path sealedRun(String runId) throws Exception {
        Path run = debugRoot.resolve(runId);
        Files.createDirectories(run);
        write(run, "world_survey_context.json", """
                {"worldSeed":"42","sealed":true,
                 "scanBounds":{"centerBlockX":0,"centerBlockZ":0,"planningRadiusBlocks":8192}}
                """);
        write(run, "world_patch_map.json", "{}");
        write(run, "world_survey_manifest.json", """
                {"createdAt":"2026-09-03T00:00:00Z"}
                """);
        return run;
    }

    private static void write(Path run, String relative, String content) throws Exception {
        Path path = run.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
