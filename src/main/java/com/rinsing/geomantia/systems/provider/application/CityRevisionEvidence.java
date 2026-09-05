package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Host reads only its current city's formal outputs; the designer does not browse raw artifacts. */
final class CityRevisionEvidence {
    private CityRevisionEvidence() { }

    static JsonObject load(Path debugRoot, JsonObject context, JsonObject budget) throws IOException {
        if (!budget.has("failureCount") || budget.get("failureCount").getAsInt() == 0) return null;
        Path root = debugRoot.toRealPath();
        String run = identity(context, "runId"), city = identity(context, "cityId");
        Path steps = root.resolve(run).resolve("city_test_runs").resolve(city).resolve("steps");
        JsonObject accepted = read(root, steps.resolve("blueprint/city_blueprint_submission_trace.json"));
        Path blueprintPath = checked(root, steps.resolve("blueprint/city_blueprint.json"));
        String blueprintRaw = Files.readString(blueprintPath);
        String contextId = context.get("contextId").getAsString();
        if (!contextId.equals(string(accepted, "contextId")) || !city.equals(string(accepted, "cityId"))
                || !"accepted".equals(string(accepted, "status"))
                || !hash(blueprintRaw).equals(string(accepted, "cityBlueprintHash"))) {
            throw new IOException("PLANNING_REVISION_EVIDENCE_STALE");
        }
        JsonObject blueprint = JsonParser.parseString(blueprintRaw).getAsJsonObject();
        if (!city.equals(string(blueprint, "cityId"))
                || !Objects.equals(blueprint.get("sourceD3Ref"), context.get("sourceD3Ref"))
                || !Objects.equals(blueprint.get("catalogSnapshotRef"), context.get("catalogSnapshotRef"))) {
            throw new IOException("PLANNING_REVISION_EVIDENCE_STALE");
        }
        JsonObject trace = read(root, steps.resolve("d4/city_generation_compile_trace.json"));
        if (!city.equals(string(trace, "cityId"))) throw new IOException("PLANNING_REVISION_EVIDENCE_STALE");
        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema", "city_revision_decision_evidence.v0.1");
        evidence.add("previousBlueprint", blueprint);
        evidence.add("failureBudget", budget.deepCopy());
        evidence.add("compileOutcome", pick(trace, "status", "reasonCode", "compilationAcceptance"));
        JsonArray groups = new JsonArray();
        if (trace.has("groupResults")) for (JsonElement entry : trace.getAsJsonArray("groupResults")) {
            groups.add(pick(entry.getAsJsonObject(), "groupId", "layoutAlgorithm", "preferredPatchRefs",
                    "actualStructureCount", "requiredStructureCount", "missingRequiredStructures",
                    "terrainPlacementFailures", "stopReason"));
        }
        evidence.add("groupOutcomes", groups);
        Path qualityPath = steps.resolve("d4/quality_report.json");
        if (Files.isRegularFile(qualityPath)) {
            evidence.add("quality", pick(read(root, qualityPath), "passed", "hardBlocks", "needsReview"));
        }
        Path preview = steps.resolve("d4/structure_anchor_preview.png");
        if (Files.isRegularFile(preview)) evidence.addProperty("compiledPreview", checked(root, preview).toString());
        evidence.addProperty("instruction", "Use exact group/structure/road conflicts below to revise the previous "
                + "Blueprint locally. Preserve unaffected choices and author semantics. Do not delete required "
                + "functions just to pass. If execution is inconsistent, report that blocker instead of blind retries.");
        return evidence;
    }

    private static JsonObject pick(JsonObject source, String... keys) {
        JsonObject result = new JsonObject();
        for (String key : keys) if (source.has(key)) result.add(key, source.get(key).deepCopy());
        return result;
    }

    private static String identity(JsonObject object, String key) throws IOException {
        String value = string(object, key);
        if (!value.matches("[A-Za-z0-9_-]+")) throw new IOException("PLANNING_REVISION_IDENTITY_INVALID");
        return value;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    private static JsonObject read(Path root, Path file) throws IOException {
        return JsonParser.parseString(Files.readString(checked(root, file))).getAsJsonObject();
    }

    private static Path checked(Path root, Path file) throws IOException {
        Path actual = file.toRealPath();
        if (!actual.startsWith(root)) throw new IOException("PLANNING_REVISION_EVIDENCE_OUTSIDE_WORLD");
        return actual;
    }

    private static String hash(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
