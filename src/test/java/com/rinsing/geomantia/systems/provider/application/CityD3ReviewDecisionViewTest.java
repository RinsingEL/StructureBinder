package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CityD3ReviewDecisionViewTest {
    @Test void all705PatchesRemainAccessibleWithCompleteGeometry() {
        JsonObject review = new JsonObject();
        JsonArray patches = new JsonArray();
        for (int i = 0; i < 705; i++) {
            JsonObject patch = new JsonObject();
            patch.addProperty("landformPatchId", "p." + i);
            patch.addProperty("landformType", i % 2 == 0 ? "plain" : "water");
            patch.add("memberCells", JsonParser.parseString("[1,2,3]"));
            patches.add(patch);
        }
        review.add("landformPatches", patches);
        verify(review);
        JsonObject filter = new JsonObject();
        filter.addProperty("landformPatchId", "p.704");
        assertEquals(patches.get(704), CityD3ReviewDecisionView.page(review, filter).getAsJsonArray("landformPatches").get(0));
        filter.addProperty("page", -1);
        assertFalse(CityD3ReviewDecisionView.page(review, filter).get("ok").getAsBoolean());
    }

    @Test void realWorldFixtureWhenProvided() throws Exception {
        String path = System.getenv("GEOMANTIA_D3_REVIEW_FIXTURE");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null);
        JsonObject review = JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject();
        assertEquals(705, review.getAsJsonArray("landformPatches").size());
        verify(review);
        assertTrue(CityD3ReviewDecisionView.overview(review).toString().length() < review.toString().length() / 10);
        System.out.println("D3 fixture: patches=705; rawChars=" + review.toString().length()
                + "; overviewChars=" + CityD3ReviewDecisionView.overview(review).toString().length());
    }

    private void verify(JsonObject review) {
        String original = review.toString();
        JsonObject overview = CityD3ReviewDecisionView.overview(review);
        assertFalse(overview.has("landformPatches"));
        assertEquals(705, overview.get("totalPatchCount").getAsInt());
        JsonArray collected = new JsonArray();
        for (int page = 0; ; page++) {
            JsonObject args = new JsonObject();
            args.addProperty("page", page);
            JsonObject result = CityD3ReviewDecisionView.page(review, args);
            collected.addAll(result.getAsJsonArray("landformPatches"));
            if (!result.get("hasMore").getAsBoolean()) break;
        }
        assertEquals(review.get("landformPatches"), collected);
        assertEquals(original, review.toString());
    }

    @Test void largeAuthoredTextIsNotRejectedOrTruncated() throws Exception {
        JsonObject source = new JsonObject();
        source.addProperty("ok", true);
        source.addProperty("authorNotes", "x".repeat(300000) + "AUTHOR_TAIL");
        assertEquals(source.get("authorNotes"), PlanningToolPresentation.present(source, Path.of(".")).get("authorNotes"));
        assertEquals("host_execution_receipt.v0.1", PlanningToolPresentation.hostResult(source).get("presentation").getAsString());
        source.addProperty("ok", false);
        assertEquals(source, PlanningToolPresentation.hostResult(source));
    }
}
