package com.rinsing.geomantia.systems.city.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityD4PatchReviewServiceTest {
    @TempDir
    Path temporary;

    @Test
    void requiresShownTopPatchPageAndInvalidatesEvidenceAfterD3Changes() throws Exception {
        String runId = "run_review";
        String cityId = "city_review";
        Path d3 = temporary.resolve(runId).resolve("city_test_runs").resolve(cityId)
                .resolve("steps/d3/city_landform_review_package.json");
        Files.createDirectories(d3.getParent());
        Files.writeString(d3, "{\"schema\":\"city_landform_review\",\"cityId\":\"city_review\"}");

        CityD4PatchReviewService service = new CityD4PatchReviewService(temporary);
        JsonObject open = response(cityId);
        JsonObject pending = service.begin(runId, cityId, open);
        assertEquals("waiting_for_patch_review", pending.get("status").getAsString());
        IllegalArgumentException required = assertThrows(IllegalArgumentException.class,
                () -> service.requireReviewed(runId, cityId));
        assertTrue(required.getMessage().contains("CITY_D4_PATCH_REVIEW_REQUIRED"));

        JsonObject request = new JsonObject();
        JsonArray interestTypes = new JsonArray();
        interestTypes.add("plain");
        interestTypes.add("slope");
        request.add("interestTypes", interestTypes);
        JsonObject shown = response(cityId);
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("topPatchesOverview", "run_review/patch_explorer/top.png");
        shown.add("artifacts", artifacts);
        JsonObject reviewed = service.complete(runId, cityId, request, shown);
        assertEquals("reviewed", reviewed.get("status").getAsString());
        assertEquals(2, service.requireReviewed(runId, cityId)
                .getAsJsonArray("interestTypes").size());

        Files.writeString(d3, "{\"schema\":\"city_landform_review\",\"cityId\":\"city_review\",\"changed\":true}");
        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
                () -> service.requireReviewed(runId, cityId));
        assertTrue(stale.getMessage().contains("STALE_AFTER_D3_CHANGE"));
    }

    private static JsonObject response(String cityId) {
        JsonObject response = new JsonObject();
        response.addProperty("scopeType", "city_d4");
        response.addProperty("scopeId", cityId);
        response.addProperty("sessionId", "pex_review");
        response.addProperty("sourceIdentity", "sha256:source");
        return response;
    }
}
