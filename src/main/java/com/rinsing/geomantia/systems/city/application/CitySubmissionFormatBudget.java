package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

/** Submission validation is independent of the five design-compilation failures. */
final class CitySubmissionFormatBudget {
    static final int LIMIT = 10;
    static void attach(Path directory, String context, JsonObject response, String reason) throws IOException {
        if (reason.contains("DESIGN_GEOMETRY") || reason.contains("DESIGN_COMPILER")
                || reason.contains("CONTEXT") || reason.contains("STALE") || reason.contains("D3_")
                || reason.contains("CATALOG") || reason.contains("FAILURE_BUDGET")) {
            response.addProperty("rejectionKind", reason.contains("DESIGN_") ? "design" : "recovery");
            return;
        }
        Path file = directory.resolve("city_submission_format_budget.json");
        JsonObject state = Files.isRegularFile(file)
                ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
        int attempts = state.has("contextId") && context.equals(state.get("contextId").getAsString())
                ? state.get("failedAttempts").getAsInt() : 0;
        state.addProperty("contextId", context);
        state.addProperty("failedAttempts", ++attempts);
        state.addProperty("maximumAttempts", LIMIT);
        state.addProperty("remainingAttempts", Math.max(0, LIMIT - attempts));
        state.addProperty("retryAllowed", attempts < LIMIT);
        Path temporary = Files.createTempFile(directory, "format-budget-", ".tmp");
        try {
            Files.writeString(temporary, state.toString());
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        response.addProperty("rejectionKind", "format");
        response.add("formatRetryBudget", state);
        if (attempts >= LIMIT) response.addProperty("nextAction", "stop_for_human_review");
    }
}
