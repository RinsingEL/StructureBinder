package com.user.terra_script.server.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldControllerCriteriaTest {
    @Test
    void checkCriteriaAcceptsValuesInsideWindow() {
        assertTrue(WorldController.checkCriteria(1.2, 0.3, 0.5, 2.0, -0.5, 1.0));
    }

    @Test
    void checkCriteriaRejectsSlopeOutsideWindow() {
        assertFalse(WorldController.checkCriteria(2.5, 0.0, 0.5, 2.0, -1.0, 1.0));
    }

    @Test
    void checkCriteriaRejectsTpiOutsideWindow() {
        assertFalse(WorldController.checkCriteria(1.0, 3.2, 0.5, 2.0, -1.0, 1.0));
    }
}
