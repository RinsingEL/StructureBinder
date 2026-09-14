package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Records model assessments, not a programmatic judgement of beauty. Called under the city's submission lock. */
final class CityDesignReviewWorkflow {
    private static final String FILE = "city_design_review.json";
    private CityDesignReviewWorkflow() { }

    static JsonObject status(Path dir, String contextId, JsonObject draft) throws IOException {
        JsonObject state = load(dir, contextId);
        JsonObject result = new JsonObject();
        JsonArray pending = new JsonArray();
        JsonObject assessments = new JsonObject();
        boolean valid = draft != null && "preview_valid".equals(text(draft, "status"));
        if (valid) {
            for (var group : draft.getAsJsonObject("previousBlueprint").getAsJsonArray("groups")) {
                String id = text(group.getAsJsonObject(), "groupId");
                String fingerprint = groupFingerprint(dir, draft, id);
                JsonObject record = object(object(state, "groups"), id);
                if (fingerprint.isEmpty() || !fingerprint.equals(text(record, "reviewed"))) pending.add(id);
                else assessments.addProperty(id, text(record, "assessment"));
            }
            result.add("baseDraftHash", draft.get("baseDraftHash"));
        }
        boolean overall = valid && pending.isEmpty()
                && overviewFingerprint(dir, draft).equals(text(object(state, "overview"), "reviewed"))
                && !text(object(state, "overview"), "reviewed").isEmpty();
        result.addProperty("stage", !valid ? "district_initial" : !pending.isEmpty() ? "district_refinement"
                : !overall ? "city_refinement" : "ready_for_final");
        result.add("pendingGroupIds", pending);
        result.add("groupAssessments", assessments);
        result.addProperty("overviewReviewed", overall);
        if (overall) result.addProperty("overviewAssessment", text(object(state, "overview"), "assessment"));
        result.addProperty("readyForFinal", overall);
        result.addProperty("instruction", "Design one functional district at a time, including its nested child arrays. "
                + "Use designReview={baseDraftHash,groupIds:[...]} (up to 3) to receive actual local images; "
                + "then repeat with assessment comparing original intent to visible shared spaces, frontage, spacing, hierarchy and retained scale. Design quality comes first: all buildings surviving or all districts being non-empty is not sufficient. After deletions or count/nesting reductions, check lost design substance and repair it if needed. "
                + "Revise with DRAFT if needed, or explain why it should remain. After local reviews use "
                + "designReview={baseDraftHash,overview:true}, then repeat with assessment of district relationships, "
                + "roads, open space and any purposeful outward additions. Be bold about adjusting valid but weak layouts. Suggested initial array counts are not caps or stopping criteria; unexplained gaps are not automatically deliberate open space. Review changed areas again. "
                + "FINAL must match the reviewed draft. Reviews do not consume rejection budgets; no forced edits.");
        return result;
    }

    static JsonObject submit(Path dir, String contextId, JsonObject draft, JsonObject review) throws IOException {
        if (draft == null || !"preview_valid".equals(text(draft, "status")))
            return pending(dir, contextId, draft, "Submit a valid DRAFT before reviewing its preview.");
        if (!text(draft, "baseDraftHash").equals(text(review, "baseDraftHash")))
            return pending(dir, contextId, draft, "Review base is stale. Use the current baseDraftHash and request its images again.");
        if (!Set.of("baseDraftHash", "groupIds", "overview", "assessment").containsAll(review.keySet()))
            throw new IllegalArgumentException("CITY_DESIGN_REVIEW_FIELDS: use baseDraftHash, groupIds OR overview=true, and optional assessment.");
        boolean overview = review.has("overview") && review.get("overview").getAsBoolean();
        if (overview == review.has("groupIds"))
            throw new IllegalArgumentException("CITY_DESIGN_REVIEW_TARGET: choose groupIds (1..3) OR overview=true.");
        JsonObject state = load(dir, contextId);
        JsonObject targets = new JsonObject();
        if (overview) {
            if (!status(dir, contextId, draft).getAsJsonArray("pendingGroupIds").isEmpty())
                return pending(dir, contextId, draft, "Finish current local reviews before the final city overview review.");
            targets.addProperty("overview", previewPath(dir, text(draft, "compiledPreview")).toString());
        } else {
            JsonArray ids = review.getAsJsonArray("groupIds");
            if (ids.isEmpty() || ids.size() > 3) throw new IllegalArgumentException("CITY_DESIGN_REVIEW_TARGET: request 1..3 groupIds per image batch.");
            for (var value : ids) {
                String id = value.getAsString();
                boolean known = false;
                for (var group : draft.getAsJsonObject("previousBlueprint").getAsJsonArray("groups"))
                    if (id.equals(text(group.getAsJsonObject(), "groupId"))) known = true;
                if (!known || targets.has(id)) throw new IllegalArgumentException("CITY_DESIGN_REVIEW_GROUP: use each current groupId once; invalid ID " + id);
                targets.addProperty(id, previewPath(dir, text(object(draft, "compiledGroupPreviews"), id)).toString());
            }
        }
        boolean assessing = review.has("assessment");
        String assessment = text(review, "assessment").trim();
        if (assessing && assessment.isBlank()) throw new IllegalArgumentException("CITY_DESIGN_REVIEW_ASSESSMENT: describe what the images show and why to preserve or revise this design.");
        for (String id : targets.keySet()) {
            String fingerprint = overview ? overviewFingerprint(dir, draft) : groupFingerprint(dir, draft, id);
            JsonObject records = overview ? state : object(state, "groups");
            JsonObject record = object(records, id);
            if (assessing && !fingerprint.equals(text(record, "viewed")))
                return pending(dir, contextId, draft, "Request these current images without assessment first, then inspect them before recording your judgement.");
            record.addProperty(assessing ? "reviewed" : "viewed", fingerprint);
            if (assessing) record.addProperty("assessment", assessment);
            records.add(id, record);
            if (!overview) state.add("groups", records);
        }
        save(dir, state);
        JsonObject result = receipt();
        // Put only requested images before workflow text; the shared presentation embeds them into tool image content.
        if (!assessing) result.add("requestedPreviews", targets);
        result.add("designReviewWorkflow", status(dir, contextId, draft));
        return result;
    }

    static JsonObject finalGate(Path dir, String contextId, JsonObject canonical) throws IOException {
        JsonObject draft = CityBlueprintDraft.current(dir, contextId, text(canonical, "cityId"));
        if (draft == null || !canonical.equals(draft.get("previousBlueprint")))
            return pending(dir, contextId, draft, "Submit these changes as DRAFT first. FINAL cannot introduce geometry that was not previewed and reviewed.");
        JsonObject workflow = status(dir, contextId, draft);
        return workflow.get("readyForFinal").getAsBoolean() ? null
                : pending(dir, contextId, draft, "The design is still in review. Complete the pending local and city overview assessments before FINAL.");
    }

    private static JsonObject pending(Path dir, String contextId, JsonObject draft, String instruction) throws IOException {
        JsonObject result = receipt();
        result.addProperty("instruction", instruction);
        result.add("designReviewWorkflow", status(dir, contextId, draft));
        return result;
    }
    private static JsonObject receipt() {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("designInProgress", true);
        result.addProperty("nextAction", "city_submit_d4_blueprint");
        return result;
    }
    private static String groupFingerprint(Path dir, JsonObject draft, String id) throws IOException {
        String path = text(object(draft, "compiledGroupPreviews"), id);
        if (path.isEmpty()) return "";
        // The local rendered result includes retained/skipped members, terrain, roads and landscape.
        // Adding an unrelated district need not invalidate the existing local review.
        return digest(Files.readAllBytes(previewPath(dir, path)));
    }
    private static String overviewFingerprint(Path dir, JsonObject draft) throws IOException {
        String path = text(draft, "compiledPreview");
        return path.isEmpty() ? "" : text(draft, "baseDraftHash") + ":" + digest(Files.readAllBytes(previewPath(dir, path)));
    }
    private static Path previewPath(Path dir, String value) throws IOException {
        if (value.isBlank()) throw new IOException("CITY_DESIGN_REVIEW_PREVIEW_MISSING");
        Path path = Path.of(value).toRealPath();
        if (!path.startsWith(dir.toRealPath()) || !path.toString().toLowerCase(Locale.ROOT).endsWith(".png")
                || !Files.isRegularFile(path) || Files.size(path) > 8L * 1024 * 1024)
            throw new IOException("CITY_DESIGN_REVIEW_PREVIEW_UNAVAILABLE");
        return path;
    }
    private static JsonObject load(Path dir, String contextId) throws IOException {
        Path file = dir.resolve(FILE);
        if (Files.isRegularFile(file)) {
            if (!file.toRealPath().startsWith(dir.toRealPath())) throw new IOException("CITY_DESIGN_REVIEW_OUTSIDE_CITY");
            JsonObject value = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (contextId.equals(text(value, "contextId"))) return value;
        }
        JsonObject value = new JsonObject(); value.addProperty("contextId", contextId);
        return value;
    }
    private static void save(Path dir, JsonObject state) throws IOException {
        Path temporary = Files.createTempFile(dir, "city-review-", ".tmp");
        try {
            Files.writeString(temporary, CityJson.GSON.toJson(state));
            try { Files.move(temporary, dir.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonObject object(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : new JsonObject();
    }
    private static String text(JsonObject value, String key) {
        return value.has(key) ? value.get(key).getAsString() : "";
    }
}
