package com.rinsing.geomantia.systems.realm_planning.application.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdventurerMapStatusReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsEmptySnapshotWhenNoPlanningRunExists() throws Exception {
        var snapshot = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"), "");

        assertEquals("", snapshot.runId());
        assertEquals("not_started", snapshot.wStatus());
        assertTrue(snapshot.cityNodes().isEmpty());
    }

    @Test
    void exposesLiveWProgressWithoutInventingTProgress() throws Exception {
        Path run = Files.createDirectories(temporaryDirectory.resolve("realm_debug/run_w"));
        Files.writeString(run.resolve("world_survey_progress.json"), """
                {"status":"running","phase":"micro_sampling","phaseProgressPercent":42.5}
                """);

        var snapshot = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"), "run_w");

        assertEquals("run_w", snapshot.runId());
        assertEquals("running", snapshot.wStatus());
        assertEquals("micro_sampling", snapshot.wPhase());
        assertEquals(42.5D, snapshot.wProgressPercent());
        assertEquals("", snapshot.tStage());
        assertEquals("not_started", snapshot.tStatus());
    }

    @Test
    void readsCurrentCityAndNodesFromPersistedArtifactsWithoutMutatingThem() throws Exception {
        Path run = Files.createDirectories(temporaryDirectory.resolve("realm_debug/run_complete"));
        Files.writeString(run.resolve("world_survey_context.json"), "{}");
        Files.writeString(run.resolve("t4_report.json"), "{}");
        Files.writeString(run.resolve("realm_profiles.json"), """
                [{"realmId":"realm_a","name":"晨曦王国"}]
                """);
        Files.writeString(run.resolve("city_seed_registry.json"), """
                {"citySeeds":[
                  {"citySeedId":"capital_a","realmId":"realm_a","role":"capital","anchorBlock":{"x":3200,"z":0}},
                  {"citySeedId":"city_a_2","realmId":"realm_a","role":"city","anchorBlock":{"x":4600,"z":800}}
                ]}
                """);
        Path automation = Files.createDirectories(run.resolve("automation"));
        Path queuePath = automation.resolve("city_design_queue.json");
        Files.writeString(queuePath, """
                {"status":"waiting_for_agent","currentCitySeedId":"capital_a","completedCount":0,"remainingCount":2,
                 "items":[
                   {"citySeedId":"capital_a","realmId":"realm_a","status":"waiting_for_agent"},
                   {"citySeedId":"city_a_2","realmId":"realm_a","status":"pending"}
                 ]}
                """);
        String before = Files.readString(queuePath);

        var snapshot = AdventurerMapStatusReader.read(temporaryDirectory.resolve("realm_debug"), "run_complete", 1536);

        assertEquals("completed", snapshot.wStatus());
        assertEquals("T4", snapshot.tStage());
        assertEquals("completed", snapshot.tStatus());
        assertEquals("realm_a", snapshot.currentRealmId());
        assertEquals("晨曦王国", snapshot.currentRealmName());
        assertEquals("capital_a", snapshot.currentCityId());
        assertEquals("waiting_for_agent", snapshot.cityStatus());
        assertEquals(2, snapshot.remainingCityCount());
        assertEquals(1536, snapshot.initialActivityRadiusBlocks());
        assertEquals(2, snapshot.cityNodes().size());
        assertTrue(snapshot.cityNodes().get(0).current());
        assertFalse(snapshot.cityNodes().get(1).current());
        assertEquals(before, Files.readString(queuePath));
    }
}
