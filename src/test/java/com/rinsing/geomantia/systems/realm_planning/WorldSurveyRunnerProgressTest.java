package com.rinsing.geomantia.systems.realm_planning;

import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.testsupport.GisTestCase;
import com.rinsing.geomantia.systems.gis.testsupport.SyntheticAtlasSampler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldSurveyRunnerProgressTest {
    @TempDir
    Path tempDir;

    @Test
    void publishesTileMicroSamplingAndCompletionUpdates() throws Exception {
        GisTestCase testCase = GisTestCase.byId("mixed");
        WorldSurveyRunner.Config config = new WorldSurveyRunner.Config(
                "realm_progress_listener_test",
                testCase.dimensionId(),
                "synthetic",
                0.0,
                0,
                0,
                512,
                128,
                32,
                8,
                testCase.sampleMode(),
                WorldSurveyRunner.ResumePolicy.RESCAN
        );
        List<WorldSurveyRunner.ProgressUpdate> updates = new ArrayList<>();

        new WorldSurveyRunner(tempDir.resolve("realm_debug"), GisClassifierConfig.defaults()).run(config,
                new SyntheticAtlasSampler(testCase.profile()), updates::add);

        assertTrue(updates.stream().anyMatch(update -> "tile_scan".equals(update.phase())));
        assertTrue(updates.stream().anyMatch(update -> "micro_sampling".equals(update.phase())));
        WorldSurveyRunner.ProgressUpdate completed = updates.get(updates.size() - 1);
        assertEquals("completed", completed.status());
        assertEquals("complete", completed.phase());
        assertEquals(100.0, completed.phaseProgressPercent());
    }
}
