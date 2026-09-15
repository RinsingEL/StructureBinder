package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;

/** Small data-backed examples, separate from the current city's geometry and review budget. */
final class CityDesignExamples {
    private static final String ROOT = "/geomantia/city_design_examples/";
    private CityDesignExamples() { }

    private static byte[] resource(String name) throws IOException {
        if (!name.matches("[a-z_]+\\.(json|png)")) throw new IOException("Invalid example asset name");
        try (var input = CityDesignExamples.class.getResourceAsStream(ROOT + name)) {
            if (input == null) throw new IOException("Missing design example asset: " + name);
            return input.readAllBytes();
        }
    }

    static JsonArray index() {
        try {
            JsonArray index = new JsonArray();
            for (var entry : catalog()) {
                var item = entry.getAsJsonObject();
                JsonObject summary = new JsonObject();
                for (String key : new String[]{"id", "title", "when"}) summary.add(key, item.get(key));
                index.add(summary);
            }
            return index;
        } catch (IOException failure) { throw new IllegalStateException("Cannot load design examples", failure); }
    }

    private static JsonArray catalog() throws IOException {
        return JsonParser.parseString(new String(resource("catalog.json"), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("cases");
    }

    static JsonObject read(Path output, String contextId, JsonObject request) throws IOException {
        String id = request.has("caseId") ? request.get("caseId").getAsString() : "";
        JsonObject selected = null;
        for (var entry : catalog()) if (id.equals(entry.getAsJsonObject().get("id").getAsString()))
            selected = entry.getAsJsonObject();
        JsonObject result = new JsonObject();
        result.addProperty("designInProgress", true);
        result.addProperty("nextAction", "city_submit_d4_blueprint");
        if (selected == null) {
            result.addProperty("ok", false);
            result.addProperty("instruction", "Select one caseId from behaviorExamples; no design budget was consumed.");
            result.add("behaviorExamples", index());
            return result;
        }
        Path file = output.resolve("city_design_examples_seen.json");
        JsonObject seen = Files.exists(file) ? JsonParser.parseString(Files.readString(file)).getAsJsonObject() : new JsonObject();
        if (!contextId.equals(seen.has("contextId") ? seen.get("contextId").getAsString() : "")) seen = new JsonObject();
        seen.addProperty("contextId", contextId);
        boolean repeat = seen.has(id);
        boolean reload = request.has("reloadImages") && request.get("reloadImages").getAsBoolean();
        result.addProperty("ok", true);
        result.addProperty("caseId", id);
        result.addProperty("alreadyDelivered", repeat);
        result.addProperty("title", selected.get("title").getAsString());
        if (repeat && !reload) {
            result.addProperty("instruction", "This example was already delivered. Apply its lesson to the current city; only request reloadImages=true if the earlier images are no longer available in context.");
            return result;
        }
        for (String key : new String[]{"when", "process", "result", "source", "limits"}) result.add(key, selected.get(key));
        JsonArray images = new JsonArray();
        JsonArray order = new JsonArray();
        for (var entry : selected.getAsJsonArray("images")) {
            var asset = entry.getAsJsonObject();
            JsonObject image = new JsonObject();
            image.addProperty("type", "image");
            image.addProperty("mimeType", "image/png");
            image.addProperty("data", Base64.getEncoder().encodeToString(resource(asset.get("file").getAsString())));
            images.add(image);
            order.add(asset.get("label"));
        }
        result.add("imageOrder", order);
        result.add("imageEvidence", images);
        seen.addProperty(id, true);
        Files.writeString(file, seen.toString());
        return result;
    }
}
