package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.city.infrastructure.json.CityJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** A rejected proposal is a revision base, never an accepted geometry or a world-generation input. */
public final class CityBlueprintDraft {
    public static final String FILE = "city_blueprint_draft.json";
    private CityBlueprintDraft() { }

    public static JsonObject create(Path directory, String contextId, JsonObject blueprint, JsonObject feedback,
                                    boolean programFailure) throws IOException {
        JsonObject draft = new JsonObject();
        draft.addProperty("schema", "city_blueprint_draft.v1");
        draft.addProperty("contextId", contextId);
        draft.addProperty("cityId", blueprint.get("cityId").getAsString());
        draft.addProperty("status", "rejected");
        draft.addProperty("baseDraftHash", hash(CityJson.GSON.toJson(blueprint)));
        draft.addProperty("acceptedRevisionAtCreation", acceptedHash(directory));
        draft.addProperty("failureOwner", programFailure ? "program" : "design");
        draft.add("previousBlueprint", blueprint.deepCopy());
        draft.add("designFeedback", feedback.deepCopy());
        return draft;
    }

    public static JsonObject current(Path directory, String contextId, String cityId) throws IOException {
        Path file = directory.resolve(FILE);
        if (!Files.isRegularFile(file)) return null;
        // Do not follow an artifact symlink outside this city's blueprint directory.
        if (!file.toRealPath().startsWith(directory.toRealPath())) throw new IOException("CITY_BLUEPRINT_DRAFT_OUTSIDE_CITY");
        JsonObject draft;
        try { draft = JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
        catch (RuntimeException ex) { throw new IOException("CITY_BLUEPRINT_DRAFT_INVALID", ex); }
        if (!java.util.Set.of("rejected", "preview_valid").contains(string(draft, "status")) || !contextId.equals(string(draft, "contextId"))
                || !cityId.equals(string(draft, "cityId"))
                || !acceptedHash(directory).equals(string(draft, "acceptedRevisionAtCreation"))) return null;
        if (!draft.has("previousBlueprint") || !hash(CityJson.GSON.toJson(draft.get("previousBlueprint")))
                .equals(string(draft, "baseDraftHash"))) throw new IOException("CITY_BLUEPRINT_DRAFT_HASH_MISMATCH");
        return draft;
    }

    public static JsonObject evidence(JsonObject draft) {
        JsonObject result = draft.deepCopy();
        result.remove("acceptedRevisionAtCreation");
        if (draft.has("compiledLayout") && draft.get("compiledLayout").isJsonObject()) {
            JsonObject layout = draft.getAsJsonObject("compiledLayout");
            JsonObject review = new JsonObject();
            if (layout.has("compilationAcceptance"))
                review.add("acceptance", layout.get("compilationAcceptance").deepCopy());
            if (layout.has("streetFirstNetworkTrace")) {
                JsonArray unresolved = new JsonArray();
                for (JsonElement item : layout.getAsJsonObject("streetFirstNetworkTrace").getAsJsonArray("accessOutcomes"))
                    if ("UNRESOLVED".equals(string(item.getAsJsonObject(), "status"))) unresolved.add(item.deepCopy());
                review.add("unresolvedEntrances", unresolved);
            }
            review.addProperty("instruction", "Safety admission is not appearance or complete entrance access. Review current warnings and the preview before FINAL; adjust affected groups' patch selection, extent, spacing or density where necessary. Do not move buildings merely to force a road through them.");
            result.add("compiledDesignReview", review);
        }
        result.remove("compiledLayout");
        result.remove("landscapeLayout");
        result.remove("groupExtentMap");
        result.addProperty("instruction", "This is the latest working draft, NOT a final accepted city. Use submissionMode=DRAFT while adding or correcting districts; use FINAL after inspecting the complete design. "
                + "For an intentional local design change use baseDraftHash with replace-only blueprintPatch; "
                + "do not send baseBlueprintHash too. Preserve unaffected groups and generationSeed. "
                + "Program-owned failure requires host diagnosis before retry; no blind redesign.");
        return result;
    }

    private static String acceptedHash(Path directory) throws IOException {
        Path file = directory.resolve("city_blueprint.json");
        if (!Files.isRegularFile(file)) return "";
        if (!file.toRealPath().startsWith(directory.toRealPath())) throw new IOException("CITY_BLUEPRINT_OUTSIDE_CITY");
        return hash(Files.readString(file));
    }
    private static String hash(String text) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String string(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }
}
