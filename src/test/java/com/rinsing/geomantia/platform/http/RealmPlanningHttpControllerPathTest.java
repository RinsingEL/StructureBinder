package com.rinsing.geomantia.platform.http;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmPlanningHttpControllerPathTest {

    @Test
    void postD4AutoCompileUsesOnlyTheCurrentBlueprintWorkflowRequest() {
        var request = RealmPlanningHttpController.postD4AutoCompileWorkflowRequest("run_test", "city_test");

        assertFalse(request.has("d4CandidateMode"));
        assertEquals("run_test", request.get("runId").getAsString());
        assertEquals("city_test", request.get("citySeedId").getAsString());
        assertTrue(request.get("skipExisting").getAsBoolean());
        assertTrue(request.get("confirmWorldMutation").getAsBoolean());
        assertTrue(request.get("stopAfterActivation").getAsBoolean());
    }

    @Test
    void relativizesAbsoluteArtifactAgainstRelativeDebugRoot() {
        Path relativeRoot = Path.of("build", "realm_debug_path_test");
        Path absoluteArtifact = relativeRoot.toAbsolutePath().resolve("run_1").resolve("preview.png");

        assertEquals("run_1/preview.png",
                RealmPlanningHttpController.relativeArtifact(relativeRoot, absoluteArtifact));
    }

    @Test
    void rejectsArtifactsOutsideDebugRoot() {
        Path root = Path.of("build", "realm_debug_path_test");
        Path outside = root.toAbsolutePath().resolveSibling("outside.png");

        assertThrows(IllegalArgumentException.class,
                () -> RealmPlanningHttpController.relativeArtifact(root, outside));
    }
}
