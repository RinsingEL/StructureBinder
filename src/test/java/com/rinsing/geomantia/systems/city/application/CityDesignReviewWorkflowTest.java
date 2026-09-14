package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CityDesignReviewWorkflowTest {
    @TempDir Path dir;

    @Test void currentImagesMustBeRequestedBeforeAssessmentAndLocalsBeforeOverview() throws Exception {
        JsonObject draft = draft("one");
        JsonObject local = review("one", false);
        local.addProperty("assessment", "Keep courtyard");
        assertFalse(submit(draft, local).getAsJsonObject("designReviewWorkflow").get("readyForFinal").getAsBoolean());
        assertFalse(submit(draft, review("one", true)).has("requestedPreviews"));
        local.remove("assessment");
        assertEquals(2, submit(draft, local).getAsJsonObject("requestedPreviews").size());
        local.addProperty("assessment", "Keep courtyard");
        assertEquals("city_refinement", submit(draft, local).getAsJsonObject("designReviewWorkflow").get("stage").getAsString());
        assess(draft, review("one", true));
        assertTrue(CityDesignReviewWorkflow.status(dir, "context", draft).get("readyForFinal").getAsBoolean());
        assertFalse(CityDesignReviewWorkflow.status(dir, "new-context", draft).get("readyForFinal").getAsBoolean());
    }

    @Test void changedLocalImageInvalidatesOnlyThatGroupAndTheOverview() throws Exception {
        JsonObject draft = draft("one");
        assess(draft, review("one", false)); assess(draft, review("one", true));
        // New instance/read after persisted state; different draft but exactly the same local pictures.
        draft.addProperty("baseDraftHash", "two");
        JsonObject status = CityDesignReviewWorkflow.status(dir, "context", draft);
        assertTrue(status.getAsJsonArray("pendingGroupIds").isEmpty());
        assertFalse(status.get("overviewReviewed").getAsBoolean());
        Files.writeString(dir.resolve("a.png"), "changed retained buildings");
        status = CityDesignReviewWorkflow.status(dir, "context", draft);
        assertEquals(JsonParser.parseString("['a']"), status.get("pendingGroupIds"));
        assertTrue(status.getAsJsonObject("groupAssessments").has("b"));
        JsonObject stale = submit(draft, review("one", false));
        assertFalse(stale.has("requestedPreviews"));
        assertTrue(stale.get("instruction").getAsString().contains("stale"));
    }

    @Test void changedBetweenViewAndAssessmentRequiresNewViewAndBatchIsAtomic() throws Exception {
        JsonObject draft = draft("one");
        JsonObject review = review("one", false);
        submit(draft, review);
        Files.writeString(dir.resolve("b.png"), "changed");
        review.addProperty("assessment", "retain");
        submit(draft, review);
        assertEquals(2, CityDesignReviewWorkflow.status(dir, "context", draft).getAsJsonArray("pendingGroupIds").size());
    }

    @Test void noReviewCanFinalizeDifferentBlueprintOrRejectedGeometry() throws Exception {
        JsonObject draft = draft("one");
        var blueprint = draft.getAsJsonObject("previousBlueprint");
        // Real draft identity/hash handling, without a compiler.
        JsonObject actual = CityBlueprintDraft.create(dir, "context", blueprint, new JsonObject(), false);
        actual.addProperty("status", "preview_valid");
        actual.add("compiledGroupPreviews", draft.get("compiledGroupPreviews"));
        actual.add("compiledPreview", draft.get("compiledPreview"));
        Files.writeString(dir.resolve(CityBlueprintDraft.FILE), actual.toString());
        String hash = actual.get("baseDraftHash").getAsString();
        assess(actual, review(hash, false)); assess(actual, review(hash, true));
        assertNull(CityDesignReviewWorkflow.finalGate(dir, "context", blueprint));
        var modified = blueprint.deepCopy(); modified.addProperty("newDesign", true);
        assertNotNull(CityDesignReviewWorkflow.finalGate(dir, "context", modified));
        actual.addProperty("status", "rejected");
        Files.writeString(dir.resolve(CityBlueprintDraft.FILE), actual.toString());
        assertNotNull(CityDesignReviewWorkflow.finalGate(dir, "context", blueprint));
    }

    @Test void unknownDuplicatesEmptyAndOversizeBatchesAreActionableFormatErrors() throws Exception {
        JsonObject draft = draft("one");
        for (String ids : new String[]{"[]", "['a','a']", "['unknown']", "['a','b','c','d']"}) {
            var review = review("one", false); review.add("groupIds", JsonParser.parseString(ids));
            assertThrows(IllegalArgumentException.class, () -> submit(draft, review));
        }
        var blank = review("one", false); blank.addProperty("assessment", " ");
        assertThrows(IllegalArgumentException.class, () -> submit(draft, blank));
    }

    @Test void missingOrOutsideImagesCannotProduceAReview() throws Exception {
        JsonObject draft = draft("one");
        Files.delete(dir.resolve("a.png"));
        assertThrows(java.io.IOException.class, () -> submit(draft, review("one", false)));
    }

    private JsonObject draft(String hash) throws Exception {
        var draft = JsonParser.parseString("{status:'preview_valid',previousBlueprint:{cityId:'city',groups:[{groupId:'a'},{groupId:'b'}]}}").getAsJsonObject();
        draft.addProperty("baseDraftHash", hash);
        JsonObject paths = new JsonObject();
        for (String id : new String[]{"a", "b", "overview"}) {
            Path file = dir.resolve(id + ".png"); Files.writeString(file, "image " + id);
            if (!id.equals("overview")) paths.addProperty(id, file.toString());
            else draft.addProperty("compiledPreview", file.toString());
        }
        draft.add("compiledGroupPreviews", paths); return draft;
    }
    private JsonObject review(String hash, boolean overview) {
        JsonObject review = new JsonObject(); review.addProperty("baseDraftHash", hash);
        if (overview) review.addProperty("overview", true);
        else review.add("groupIds", JsonParser.parseString("['a','b']"));
        return review;
    }
    private JsonObject submit(JsonObject draft, JsonObject review) throws Exception {
        return CityDesignReviewWorkflow.submit(dir, "context", draft, review);
    }
    private void assess(JsonObject draft, JsonObject review) throws Exception {
        assertTrue(submit(draft, review).has("requestedPreviews"));
        review.addProperty("assessment", "The space is legible; keep this arrangement.");
        assertTrue(submit(draft, review).get("ok").getAsBoolean());
    }
}
