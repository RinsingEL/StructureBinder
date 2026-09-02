package com.rinsing.geomantia.systems.provider.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRunDiscoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void selectsNewestActionableQueueAndIgnoresCompletedRuns() throws Exception {
        writeQueue("old", "waiting_for_agent", "city_a", "city_plan_d3", 1000);
        writeQueue("done", "completed", "", "", 1500);
        writeQueue("new", "waiting_for_patch_review", "city_b",
                "patch_explorer_show_candidates", 2000);

        var active = new ProviderRunDiscovery(temporaryDirectory).newestActionableRun();

        assertTrue(active.isPresent());
        assertEquals("new", active.get().runId());
        assertEquals("city_b", active.get().citySeedId());
    }

    @Test
    void doesNotFallBackToAnOlderUnfinishedRunWhenLatestRunIsComplete() throws Exception {
        writeQueue("old", "waiting_for_agent", "city_a", "city_plan_d3", 1000);
        writeQueue("done", "completed", "", "", 2000);

        assertTrue(new ProviderRunDiscovery(temporaryDirectory).newestActionableRun().isEmpty());
    }

    @Test
    void filtersQueuesByTheCurrentWorldSeed() throws Exception {
        writeQueue("other_world", "waiting_for_agent", "city_a", "city_plan_d3", 3000, "22");
        writeQueue("current_world", "waiting_for_agent", "city_b", "city_plan_d3", 2000, "11");

        var active = new ProviderRunDiscovery(temporaryDirectory, 11L).newestActionableRun();

        assertTrue(active.isPresent());
        assertEquals("current_world", active.get().runId());
    }

    private void writeQueue(String runId, String status, String city, String nextAction, long modified)
            throws Exception {
        writeQueue(runId, status, city, nextAction, modified, "11");
    }

    private void writeQueue(String runId, String status, String city, String nextAction, long modified,
                            String worldSeed) throws Exception {
        Path path = temporaryDirectory.resolve(runId).resolve("automation").resolve("city_design_queue.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                {"schema":"city_design_queue.v0.1","runId":"%s","enabled":true,"status":"%s",
                 "currentCitySeedId":"%s","nextAction":"%s"}
                """.formatted(runId, status, city, nextAction));
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified));
        Files.writeString(path.getParent().getParent().resolve("world_survey_context.json"),
                "{\"worldSeed\":\"" + worldSeed + "\"}");
    }
}
