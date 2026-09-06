package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Read-only, city-scoped access to the complete persisted terrain evidence. */
final class CityD3ReviewDecisionView {
    static final String TOOL = "city_inspect_d3_patches";
    private CityD3ReviewDecisionView() {}

    static JsonObject overview(JsonObject review) {
        JsonObject result = review.deepCopy();
        result.remove("landformPatches");
        JsonArray patches = review.getAsJsonArray("landformPatches");
        JsonObject counts = new JsonObject();
        JsonArray examples = new JsonArray();
        for (var element : patches) {
            JsonObject patch = element.getAsJsonObject();
            String type = patch.get("landformType").getAsString();
            counts.addProperty(type, counts.has(type) ? counts.get(type).getAsInt() + 1 : 1);
            if (examples.size() < 8) examples.add(PlanningToolPresentation.compact(patch));
        }
        result.addProperty("totalPatchCount", patches.size());
        result.add("patchCountsByLandformType", counts);
        result.add("firstPatchSummaries", examples);
        result.addProperty("evidenceAccess", "Overview only; firstPatchSummaries are not a ranking or complete list. "
                + "Use attached maps for site review. If more evidence is needed, call " + TOOL
                + " with optional landformType or exact landformPatchId, and zero-based page. "
                + "Pages return complete patch records including geometry. Reading every page is not required. "
                + "The complete original D3 artifact remains unchanged.");
        return result;
    }

    static JsonObject page(JsonObject review, JsonObject args) {
        JsonObject result = new JsonObject();
        int page;
        int size;
        try {
            page = args.has("page") ? Integer.parseInt(args.get("page").getAsString()) : 0;
            size = args.has("pageSize") ? Integer.parseInt(args.get("pageSize").getAsString()) : 8;
            if (page < 0 || size < 1 || size > 16) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            result.addProperty("ok", false);
            result.addProperty("error", "INVALID_PATCH_PAGE: page >= 0; pageSize between 1 and 16");
            return result;
        }
        JsonArray matches = new JsonArray();
        for (var element : review.getAsJsonArray("landformPatches")) {
            JsonObject patch = element.getAsJsonObject();
            if (args.has("landformType") && !args.get("landformType").equals(patch.get("landformType"))) continue;
            if (args.has("landformPatchId") && !args.get("landformPatchId").equals(patch.get("landformPatchId"))) continue;
            matches.add(patch);
        }
        long offset = (long) page * size;
        JsonArray records = new JsonArray();
        for (long i = offset; i < Math.min(offset + size, matches.size()); i++) records.add(matches.get((int) i).deepCopy());
        result.addProperty("ok", true);
        result.addProperty("page", page);
        result.addProperty("pageSize", size);
        result.addProperty("totalMatched", matches.size());
        result.addProperty("hasMore", offset + size < matches.size());
        if (offset + size < matches.size()) result.addProperty("nextPage", page + 1);
        result.add("landformPatches", records);
        return result;
    }
}
