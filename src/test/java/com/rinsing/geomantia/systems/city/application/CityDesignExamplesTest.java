package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class CityDesignExamplesTest {
    @TempDir Path output;
    @Test void catalogIsSmallAndEachCaseDeliversTwoRealImagesOnlyOnDemand() throws Exception {
        for (var entry : CityDesignExamples.index()) {
            assertFalse(entry.getAsJsonObject().has("images"));
            assertFalse(entry.getAsJsonObject().has("process"));
            JsonObject request = new JsonObject();
            request.add("caseId", entry.getAsJsonObject().get("id"));
            var first = CityDesignExamples.read(output, "context-a", request);
            assertEquals(2, first.getAsJsonArray("imageEvidence").size());
            for (var image : first.getAsJsonArray("imageEvidence")) {
                var bytes = Base64.getDecoder().decode(image.getAsJsonObject().get("data").getAsString());
                assertNotNull(ImageIO.read(new ByteArrayInputStream(bytes)));
            }
            assertFalse(CityDesignExamples.read(output, "context-a", request).has("imageEvidence"));
            request.addProperty("reloadImages", true);
            assertTrue(CityDesignExamples.read(output, "context-a", request).has("imageEvidence"));
        }
    }
    @Test void unknownCaseDoesNotResolveArbitraryPathsAndNewContextCanReadAgain() throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("caseId", "../outside");
        assertFalse(CityDesignExamples.read(output, "a", request).get("ok").getAsBoolean());
        request.addProperty("caseId", "deepen_blocks");
        CityDesignExamples.read(output, "a", request);
        assertTrue(CityDesignExamples.read(output, "b", request).has("imageEvidence"));
    }
}
