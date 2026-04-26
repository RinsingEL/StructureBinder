package com.user.terra_script.server.mcp;

import com.google.gson.JsonObject;
import com.user.terra_script.domain.territory.stage.T4Stage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowControllerTest {
    @Test
    void parseStageIdAcceptsSnakeCaseAlias() {
        JsonObject req = new JsonObject();
        req.addProperty("stage_id", "t4");

        assertEquals("T4", WorkflowController.parseStageId(req));
    }

    @Test
    void parseStageIdReturnsNullWhenMissing() {
        assertNull(WorkflowController.parseStageId(new JsonObject()));
    }

    @Test
    void parseT4OptionsAcceptsDocumentedAliases() {
        JsonObject req = new JsonObject();
        req.addProperty("sample_stride", 3);
        req.addProperty("max_chunks_per_territory", 128);
        req.addProperty("loaded_only", true);

        T4Stage.RuntimeOptions options = WorkflowController.parseT4Options(req, T4Stage.RuntimeOptions.defaults());

        assertEquals(3, options.sampleStride);
        assertEquals(128, options.maxChunksPerTerritory);
        assertEquals(true, options.loadedOnly);
    }

    @Test
    void parseT4OptionsAcceptsInternalNames() {
        JsonObject req = new JsonObject();
        req.addProperty("t4_sample_stride", 5);
        req.addProperty("t4_max_chunks", 64);
        req.addProperty("t4_loaded_only", false);

        T4Stage.RuntimeOptions options = WorkflowController.parseT4Options(req, T4Stage.RuntimeOptions.defaults());

        assertEquals(5, options.sampleStride);
        assertEquals(64, options.maxChunksPerTerritory);
        assertEquals(false, options.loadedOnly);
    }

    @Test
    void parseT4OptionsRejectsDeprecatedFlags() {
        JsonObject req = new JsonObject();
        req.addProperty("t4_legacy_scan", true);

        assertThrows(IllegalArgumentException.class,
                () -> WorkflowController.parseT4Options(req, T4Stage.RuntimeOptions.defaults()));
    }
}
