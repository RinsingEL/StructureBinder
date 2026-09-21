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
        boolean overall = valid
                && overviewFingerprint(dir, draft).equals(text(object(state, "overview"), "reviewed"))
                && !text(object(state, "overview"), "reviewed").isEmpty();
        JsonArray isolated = isolatedCores(draft);
        boolean overviewReviewed = overall;
        overall &= isolated.isEmpty();
        result.add("isolatedCoreGroupIds", isolated);
        result.addProperty("coreReworkCount", object(state, "coreReworkDrafts").size());
        result.addProperty("coreReworkExhausted", !isolated.isEmpty()
                && object(state, "coreReworkDrafts").size() >= CityBlueprintFailureBudget.MAX_FAILURE_COUNT);
        result.addProperty("stage", !valid ? "district_initial" : !isolated.isEmpty() ? "core_rework"
                : !overall ? "overview_review" : "ready_for_final");
        result.add("pendingGroupIds", new JsonArray());
        result.add("optionalUnreviewedGroupIds", pending);
        result.add("groupAssessments", assessments);
        result.addProperty("overviewReviewed", overviewReviewed);
        if (overviewReviewed) result.addProperty("overviewAssessment", text(object(state, "overview"), "assessment"));
        result.addProperty("readyForFinal", overall);
        result.addProperty("instruction", "检查实际核心与配套的大小关系和组合效果。isolatedCoreGroupIds 非空时必须调整选材或阵列；完整素材自带装饰计入效果。coreReworkCount 按不同返工草稿计数，沿用原有失败边界，不无限重试。局部图按需查看。");
        return result;
    }

    static JsonObject submitRequest(Path dir, String contextId, JsonObject draft, JsonObject request) throws IOException {
        JsonObject review = object(request, "designReview");
        List<String> misplaced = new ArrayList<>();
        for (String key : List.of("baseDraftHash", "groupIds", "overview", "assessment"))
            if (request.has(key)) misplaced.add(key);
        if (!misplaced.isEmpty())
            return reviewError(dir, contextId, draft, request, review, "CITY_DESIGN_REVIEW_FIELD_LOCATION",
                    "Move root fields " + misplaced + " inside designReview. Repeat the COMPLETE designReview object on every call; assessment is not a patch. No assessment was saved.");
        JsonObject result = submit(dir, contextId, draft, review);
        if (result.has("correctedRequestExample"))
            for (String key : List.of("runId", "citySeedId"))
                if (request.has(key)) result.getAsJsonObject("correctedRequestExample").add(key, request.get(key).deepCopy());
        return result;
    }

    private static JsonObject reviewError(Path dir, String contextId, JsonObject draft, JsonObject request,
                                         JsonObject review, String code, String message) throws IOException {
        JsonObject result = pending(dir, contextId, draft, message);
        result.addProperty("reasonCode", code);
        result.addProperty("assessmentRecorded", false);
        JsonObject example = new JsonObject();
        for (String key : List.of("runId", "citySeedId"))
            if (request.has(key)) example.add(key, request.get(key).deepCopy());
        example.addProperty("contextId", contextId);
        JsonObject body = new JsonObject();
        body.addProperty("baseDraftHash", draft == null ? "<current baseDraftHash>" : text(draft, "baseDraftHash"));
        for (String key : List.of("groupIds", "overview", "assessment")) {
            if (review.has(key)) body.add(key, review.get(key).deepCopy());
            else if (request.has(key)) body.add(key, request.get(key).deepCopy());
        }
        if (!body.has("groupIds") && !body.has("overview")) {
            JsonArray ids = new JsonArray(); ids.add("<groupId from pendingGroupIds>"); body.add("groupIds", ids);
        }
        if ("CITY_DESIGN_REVIEW_BASE_STALE".equals(code)) body.remove("assessment");
        example.add("designReview", body);
        result.add("correctedRequestExample", example);
        return result;
    }

    static JsonObject submit(Path dir, String contextId, JsonObject draft, JsonObject review) throws IOException {
        if (draft == null || !"preview_valid".equals(text(draft, "status")))
            return pending(dir, contextId, draft, "Submit a valid DRAFT before reviewing its preview.");
        JsonElement hash = review.get("baseDraftHash");
        if (hash == null || hash.isJsonNull() || !hash.isJsonPrimitive()
                || !hash.getAsJsonPrimitive().isString() || hash.getAsString().isBlank())
            return reviewError(dir, contextId, draft, new JsonObject(), review, "CITY_DESIGN_REVIEW_BASE_REQUIRED",
                    "Missing or invalid baseDraftHash. Use city_d4_preview with the current hash and groupIds OR overview=true, then submit the current overview assessment through city_d4_mark, city_d4_integrate or city_d4_finalize. No assessment was saved.");
        if (!text(draft, "baseDraftHash").equals(hash.getAsString()))
            return reviewError(dir, contextId, draft, new JsonObject(), review, "CITY_DESIGN_REVIEW_BASE_STALE",
                    "Review base is stale. Submitted " + hash.getAsString() + "; current " + text(draft, "baseDraftHash")
                            + ". Use city_d4_preview for current images, then submit your overview assessment for the same version. No assessment was saved.");
        if (!Set.of("baseDraftHash", "groupIds", "overview", "assessment").containsAll(review.keySet()))
            throw new IllegalArgumentException("CITY_DESIGN_REVIEW_FIELDS: use baseDraftHash, groupIds OR overview=true, and optional assessment.");
        boolean overview = review.has("overview") && review.get("overview").getAsBoolean();
        if (overview == review.has("groupIds"))
            throw new IllegalArgumentException("CITY_DESIGN_REVIEW_TARGET: choose groupIds (1..3) OR overview=true.");
        JsonObject state = load(dir, contextId);
        JsonObject targets = new JsonObject();
        if (overview) {
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
        result.addProperty("assessmentRecorded", assessing);
        result.add("designReviewWorkflow", status(dir, contextId, draft));
        return result;
    }

    static boolean overviewViewed(Path dir,String contextId,JsonObject draft)throws IOException {
        return draft!=null && "preview_valid".equals(text(draft,"status"))
                && overviewFingerprint(dir,draft).equals(text(object(load(dir,contextId),"overview"),"viewed"));
    }

    private static JsonArray isolatedCores(JsonObject draft) {
        if (draft == null) return new JsonArray();
        JsonObject review = object(object(draft, "compiledLayout"), "designReview");
        return review.has("isolatedCoreGroupIds") ? review.getAsJsonArray("isolatedCoreGroupIds").deepCopy() : new JsonArray();
    }

    static void recordCoreRework(Path dir, String contextId, JsonObject draft) throws IOException {
        JsonArray isolated = isolatedCores(draft);
        if (isolated.isEmpty()) return;
        JsonObject state = load(dir, contextId);
        JsonObject attempts = object(state, "coreReworkDrafts");
        attempts.add(text(draft, "baseDraftHash"), isolated);
        state.add("coreReworkDrafts", attempts);
        save(dir, state);
    }

    static JsonObject finalGate(Path dir, String contextId, JsonObject canonical) throws IOException {
        JsonObject draft = CityBlueprintDraft.current(dir, contextId, text(canonical, "cityId"));
        if (draft == null || !canonical.equals(draft.get("previousBlueprint")))
            return pending(dir, contextId, draft, "Submit these changes as DRAFT first. FINAL cannot introduce geometry that was not previewed and reviewed.");
        JsonObject workflow = status(dir, contextId, draft);
        if (workflow.get("coreReworkExhausted").getAsBoolean()) {
            JsonObject exhausted = pending(dir,contextId,draft,"孤立核心返工达到五次边界，保留当前证据并停止。");
            exhausted.addProperty("ok",false);
            exhausted.addProperty("reasonCode","CITY_BLUEPRINT_FAILURE_BUDGET_EXHAUSTED");
            exhausted.addProperty("nextAction","stop_for_human_review");
            return exhausted;
        }
        return workflow.get("readyForFinal").getAsBoolean() ? null
                : pending(dir, contextId, draft, "The design is still in review. Inspect the current overview and include your assessment in city_d4_finalize.");
    }

    private static JsonObject pending(Path dir, String contextId, JsonObject draft, String instruction) throws IOException {
        JsonObject result = receipt();
        result.addProperty("instruction", instruction);
        result.addProperty("assessmentRecorded", false);
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
