package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Shared model-facing view. Formal artifacts remain complete; the model receives design choices, not compiler internals. */
public final class PlanningToolPresentation {
    private static final Set<String> DENSE_FIELDS = Set.of("cells", "blockedCells", "occupiedSeeds",
            "allowedPatches", "cellSamples", "memberCells", "neighborLandformPatchIds", "territoryCells");
    private static final Set<String> COMPILED_ARTIFACTS = Set.of("structureAnchorPlan", "structureAnchorMap",
            "cityGenerationCompileTrace", "groupExtentMap", "reservationMaskPlan", "wallReservationPlan",
            "roadAccessPlan", "buildOperationPlan", "structureMaterializationPlan", "placedStructureLedger",
            "structureMaterializationTrace", "inferredFunctionAreaMap", "landUseOwnerCompletion");
    private PlanningToolPresentation() { }

    public static JsonObject present(JsonObject source, Path debugRoot) throws IOException {
        JsonObject result = compact(source).getAsJsonObject();
        result.addProperty("presentation", "planning_decision_view.v0.1");
        // Hermes wraps the JSON text again. Budget that escaped envelope, not just the raw JSON.
        JsonObject envelope = new JsonObject();
        envelope.addProperty("result", result.toString());
        if (envelope.toString().length() > 90_000) {
            throw new IOException("PLANNING_PRESENTATION_TOO_LARGE: decision envelope chars=" + envelope.toString().length()
                    + " exceeds 90000; "
                    + "host must provide complete paged choices, never truncate author data.");
        }
        JsonArray images = new JsonArray();
        JsonArray warnings = new JsonArray();
        Set<String> paths = new LinkedHashSet<>();
        collectPreviews(result, paths);
        Path root = Files.exists(debugRoot) ? debugRoot.toRealPath() : debugRoot.toAbsolutePath().normalize();
        for (String value : paths) {
            if (images.size() >= 4) break;
            try {
                Path requested = Path.of(value);
                Path path = (requested.isAbsolute() ? requested : root.resolve(requested)).normalize();
                if (!path.startsWith(root) || !Files.isRegularFile(path) || !path.toRealPath().startsWith(root)) {
                    warnings.add("Preview unavailable inside current world: " + value);
                    continue;
                }
                if (Files.size(path) > 8L * 1024 * 1024) {
                    warnings.add("Preview exceeds image size limit: " + value);
                    continue;
                }
                JsonObject image = new JsonObject();
                image.addProperty("type", "image");
                image.addProperty("mimeType", "image/png");
                image.addProperty("data", Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
                images.add(image);
            } catch (IOException | RuntimeException exception) {
                warnings.add("Preview could not be read: " + value);
            }
        }
        if (!images.isEmpty()) result.add("imageEvidence", images);
        if (!warnings.isEmpty()) result.add("previewWarnings", warnings);
        return result;
    }

    public static JsonElement compact(JsonElement source) {
        if (source.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : source.getAsJsonArray()) result.add(compact(item));
            return result;
        }
        if (!source.isJsonObject()) return source.deepCopy();
        if (source.getAsJsonObject().has("schema")
                && "city_blueprint_context".equals(source.getAsJsonObject().get("schema").getAsString())) {
            source = CityPlanningDecisionView.from(source.getAsJsonObject());
        }
        JsonObject result = new JsonObject();
        JsonObject artifacts = source.getAsJsonObject().has("artifacts")
                && source.getAsJsonObject().get("artifacts").isJsonObject()
                ? source.getAsJsonObject().getAsJsonObject("artifacts") : new JsonObject();
        for (var entry : source.getAsJsonObject().entrySet()) {
            if (COMPILED_ARTIFACTS.contains(entry.getKey()) && entry.getValue().isJsonObject()
                    && artifacts.has(entry.getKey()) && artifacts.get(entry.getKey()).isJsonPrimitive()
                    && artifacts.get(entry.getKey()).getAsJsonPrimitive().isString()
                    && !artifacts.get(entry.getKey()).getAsString().isBlank()) {
                JsonObject summary = new JsonObject();
                summary.addProperty("omittedFromDecisionView", true);
                summary.add("artifactPath", artifacts.get(entry.getKey()).deepCopy());
                JsonObject full = entry.getValue().getAsJsonObject();
                for (String key : new String[]{"schema", "cityId", "status", "reasonCode", "locked", "compilationAcceptance",
                        "complete", "plannedOwnerCount", "appliedBeforeCount", "backfilledOwnerCount",
                        "appliedAfterCount", "failures", "maxOwnerActionsPerTick", "synchronousChunkLoads"}) {
                    if (full.has(key)) summary.add(key, compact(full.get(key)));
                }
                if (full.has("anchors") && full.get("anchors").isJsonArray())
                    summary.addProperty("anchorCount", full.getAsJsonArray("anchors").size());
                summary.addProperty("detailAccess", "Complete compiled geometry and search evidence remain in the returned formal artifact; qualityReport and its warnings are not omitted.");
                result.add(entry.getKey(), summary);
            } else if (DENSE_FIELDS.contains(entry.getKey())
                    && (entry.getValue().isJsonArray() || entry.getValue().isJsonObject())) {
                JsonObject summary = new JsonObject();
                summary.addProperty("omittedFromDecisionView", true);
                summary.addProperty("entryCount", entry.getValue().isJsonArray()
                        ? entry.getValue().getAsJsonArray().size() : entry.getValue().getAsJsonObject().size());
                summary.addProperty("detailAccess", "Use the returned Patch Explorer candidate tools for local evidence; full data remains in formal artifacts.");
                result.add(entry.getKey() + "Summary", summary);
            } else result.add(entry.getKey(), compact(entry.getValue()));
        }
        return result;
    }

    private static void collectPreviews(JsonElement value, Set<String> paths) {
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) collectPreviews(entry.getValue(), paths);
        } else if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) collectPreviews(child, paths);
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String path = value.getAsString();
            if (path.toLowerCase(Locale.ROOT).endsWith(".png")) paths.add(path);
        }
    }
}
