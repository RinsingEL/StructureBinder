package com.rinsing.geomantia.platform.http;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RealmPlanningHttpControllerPathTest {
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
