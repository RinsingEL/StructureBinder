package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;

/** Host-owned stop policy shared by embedded and sidecar loops. */
final class PlanningTurnControl implements DeepSeekToolLoopClient.ToolExecutor {
    private final DeepSeekToolLoopClient.ToolExecutor delegate;
    private volatile boolean finished;
    private volatile String error = "";
    private String lastFailure = "";
    private int repeats;
    private boolean formatCorrection;
    private boolean revisionProgress;
    private String lastRevision = "";
    boolean permitsDesignContinuation() { return error.isBlank() && (formatCorrection || revisionProgress); }


    PlanningTurnControl(DeepSeekToolLoopClient.ToolExecutor delegate) { this(delegate, new JsonObject()); }
    PlanningTurnControl(DeepSeekToolLoopClient.ToolExecutor delegate, JsonObject initialState) {
        this.delegate = delegate;
        this.lastRevision = revisionIdentity(initialState);
    }
    private static String revisionIdentity(JsonObject payload) {
        if (!payload.has("revisionEvidence") || !payload.get("revisionEvidence").isJsonObject()) return "";
        JsonObject evidence = payload.getAsJsonObject("revisionEvidence");
        for (String key : new String[]{"baseDraftHash", "baseBlueprintHash"})
            if (evidence.has(key)) return evidence.get(key).getAsString();
        return "";
    }
    boolean finished() { return finished; }
    DeepSeekToolLoopClient.LoopResult result(int calls) {
        return new DeepSeekToolLoopClient.LoopResult(error.isBlank(), error.isBlank() ? "completed" : "blocked", error, calls, "");
    }

    @Override
    public synchronized JsonElement execute(String tool, JsonObject arguments) throws Exception {
        if (finished) throw new IllegalStateException("PLANNING_TURN_FINISHED: wait for the host's next task.");
        JsonElement output;
        try { output = delegate.execute(tool, arguments); }
        catch (Exception ex) {
            JsonObject failure = new JsonObject();
            failure.addProperty("ok", false);
            failure.addProperty("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            output = failure;
        }
        JsonObject payload = payload(output);
        String failure = failure(payload);
        if (!failure.isBlank()) {
            if (payload.has("formatRetryBudget")) {
                formatCorrection = true;
                if (!payload.getAsJsonObject("formatRetryBudget").get("retryAllowed").getAsBoolean()) {
                    error = "PLANNING_FORMAT_RETRIES_EXHAUSTED";
                    finished = true;
                }
                return output;
            }
            String revision = revisionIdentity(payload);
            String fingerprint = failure + "\n" + (arguments == null ? "" : arguments.toString());
            if (!revision.isBlank() && !revision.equals(lastRevision)) {
                revisionProgress = true;
                lastRevision = revision;
                repeats = 0;
            }
            if (fingerprint.equals(lastFailure)) repeats++; else { repeats = 1; lastFailure = fingerprint; }
            if (hostFailure(failure) || "program".equals(payload.has("failureOwner") ? payload.get("failureOwner").getAsString() : "") || repeats >= 3) {
                error = hostFailure(failure) || "program".equals(payload.has("failureOwner") ? payload.get("failureOwner").getAsString() : "") ? "PLANNING_HOST_BLOCKED: " + failure : "PLANNING_REPEATED_REJECTION: " + failure;
                finished = true;
            }
        } else {
            repeats = 0; lastFailure = "";
            if (payload.has("designInProgress") && payload.get("designInProgress").getAsBoolean()) {
                String revision = revisionIdentity(payload);
                revisionProgress |= !revision.isBlank() && !revision.equals(lastRevision);
                lastRevision = revision;
                finished = true; // Next prepared turn attaches the new rendered preview.
                return output;
            }
            if (Set.of("realm_t1_prepare", "realm_t2_select_coordinate", "realm_t4_patch_planning_finalize",
                    "city_submit_d4_blueprint", "city_review_d3_site").contains(tool)
                    || payload.has("hostDecisionCommitted") && payload.get("hostDecisionCommitted").getAsBoolean()) finished = true;
        }
        return output;
    }

    static JsonObject payload(JsonElement output) {
        try {
            if (output.isJsonPrimitive()) return JsonParser.parseString(output.getAsString()).getAsJsonObject();
            if (output.isJsonArray()) {
                for (JsonElement part : output.getAsJsonArray()) {
                    JsonObject item = part.getAsJsonObject();
                    if (item.has("text")) return JsonParser.parseString(item.get("text").getAsString()).getAsJsonObject();
                }
            }
            if (output.isJsonObject()) return output.getAsJsonObject();
        } catch (RuntimeException ignored) { }
        return new JsonObject();
    }

    static String failure(JsonObject payload) {
        if (payload.has("response") && payload.get("response").isJsonObject()) {
            String nested = failure(payload.getAsJsonObject("response"));
            if (!nested.isBlank()) return nested;
        }
        for (String key : new String[]{"error", "errorCode"}) {
            if (payload.has(key) && !payload.get(key).isJsonNull()) {
                String value = payload.get(key).isJsonPrimitive() ? payload.get(key).getAsString() : payload.get(key).toString();
                if (!value.isBlank()) return value;
            }
        }
        if ((payload.has("ok") && !payload.get("ok").getAsBoolean())
                || (payload.has("status") && Set.of("failed", "needs_agent", "blocked_by_program").contains(payload.get("status").getAsString()))) {
            if (payload.has("validationReport") && payload.get("validationReport").isJsonObject()) {
                JsonObject report = payload.getAsJsonObject("validationReport");
                if (report.has("issues")) return report.get("issues").toString();
                return report.toString();
            }
            if (payload.has("reasonCode")) return payload.get("reasonCode").getAsString();
            if (payload.has("errors")) return payload.get("errors").toString();
            return "PLANNING_TOOL_REJECTED";
        }
        return "";
    }

    private static boolean hostFailure(String error) {
        return error.contains("MANAGED_CITY_SOURCE") || error.contains("CITY_TEMPLATE_CONTENT")
                || error.contains("PLANNING_PRESENTATION_TOO_LARGE")
                || error.contains("PLANNING_AUTHOR_ANNOTATION_REQUIRED")
                || error.contains("CITY_TEMPLATE_CATALOG") || error.contains("ConnectException")
                || error.contains("CITY_TEMPLATE_NBT") || error.contains("PLANNING_SOURCE_HOST_OWNED");
    }
}
