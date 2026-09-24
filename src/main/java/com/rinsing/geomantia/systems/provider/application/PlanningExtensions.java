package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import com.rinsing.geomantia.api.planning.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** City-scoped extension scheduling and durable receipts. Discovery is read-only. */
final class PlanningExtensions {
    private final PlanningExtensionRegistry registry;
    PlanningExtensions(PlanningExtensionRegistry registry) { this.registry = registry; }
    record Pending(PlanningExtensionRegistry.Entry entry, PlanningExtensionContext context, String taskRevision) {
        JsonObject state() {
            JsonObject state = new JsonObject();
            state.addProperty("schema", "planning_extension_task.v1");
            state.addProperty("runId", context.runId());
            state.addProperty("realmId", context.realmId());
            state.addProperty("citySeedId", context.citySeedId());
            state.addProperty("nextAction", entry.id());
            state.addProperty("extensionId", entry.id());
            state.addProperty("extensionVersion", entry.version());
            state.addProperty("extensionTitle", entry.title());
            state.addProperty("inputRevision", context.inputRevision());
            state.addProperty("taskRevision", taskRevision);
            return state;
        }
    }
    Pending next(Path run, JsonObject queue) throws IOException {
        if (queue == null || !queue.has("items")) return null;
        for (var value : queue.getAsJsonArray("items")) {
            var item = value.getAsJsonObject();
            if (!"waiting_for_generation".equals(text(item, "status"))) continue;
            String city = text(item, "citySeedId");
            Path journal = journalPath(run, city);
            if (registry.entries().isEmpty() && !Files.exists(journal)) continue;
            var context = context(run, city);
            var saved = readJournal(run, city);
            checkMissing(saved, context);
            for (var entry : registry.entries()) {
                String revision = revision(entry, context);
                if (!done(saved, entry.id(), revision)) return pending(entry, context);
            }
        }
        return null;
    }
    PreparedPlanningTurn prepare(ProviderPlanningDiscovery.PlanningStep step) throws Exception {
        String id = text(step.state(), "extensionId");
        var entry = registry.entry(id);
        if (entry == null) throw new IOException("PLANNING_EXTENSION_MISSING: " + id);
        var pending = pending(entry, context(step.runDirectory(), step.citySeedId()));
        var context = pending.context();
        if (!pending.taskRevision().equals(text(step.state(), "taskRevision")))
            throw new IOException("PLANNING_EXTENSION_INPUT_CHANGED: " + id);
        pin(context);
        if (done(readJournal(context.runDirectory(), context.citySeedId()), id, pending.taskRevision())) return null;
        boolean applies = callback(entry, () -> entry.extension().applies(context));
        if (!applies) { receipt(pending, "skipped"); return null; }
        if (callback(entry, () -> entry.extension().isComplete(context))) { receipt(pending, "complete"); return null; }
        JsonObject task = callback(entry, () -> Objects.requireNonNull(entry.extension().prepare(context)));
        JsonObject state = pending.state();
        state.add("extensionTask", task.deepCopy());
        var definitions = definitions(entry);
        var executor = new PlanningToolExecutor() {
            @Override public JsonArray definitions(List<String> allowed) { return definitions.deepCopy(); }
            @Override public JsonElement execute(String tool, JsonObject arguments) throws Exception {
                var definition = entry.tools().stream().filter(t -> t.name().equals(tool)).findFirst().orElse(null);
                if (definition == null) return rejected("PLANNING_TOOL_NOT_IN_CURRENT_DECISION_SCOPE: " + tool);
                try { ExtensionToolSchema.validate(definition.parameters(), arguments); }
                catch (IllegalArgumentException error) { return rejected(error.getMessage()); }
                assertCurrent(pending);
                // Recover a published addon transaction even if the host receipt write previously failed.
                if (callback(entry, () -> entry.extension().isComplete(context))) {
                    receipt(pending, "complete"); return accepted(new JsonObject(), true);
                }
                JsonObject output = callback(entry, () -> Objects.requireNonNull(
                        entry.extension().execute(context, tool, arguments.deepCopy())));
                String error = PlanningTurnControl.failure(output);
                if (!error.isBlank()) return rejected(error, output);
                boolean complete = callback(entry, () -> entry.extension().isComplete(context));
                if (complete) receipt(pending, "complete");
                return accepted(output, complete);
            }
        };
        return new PreparedPlanningTurn(state, List.of(), entry.tools().stream().map(PlanningTool::name).toList(),
                new PlanningTurnControl(executor, state));
    }
    private <T> T callback(PlanningExtensionRegistry.Entry entry, java.util.concurrent.Callable<T> call) throws Exception {
        try { return registry.call(call); }
        catch (InterruptedException error) { throw error; }
        catch (Exception error) { throw new IOException("PLANNING_EXTENSION_FAILED: " + entry.id() + ": " + error.getMessage(), error); }
    }
    private void assertCurrent(Pending pending) throws IOException {
        var queue = readRequired(pending.context().runDirectory().resolve("automation/city_design_queue.json"));
        var item = find(queue, "items", "citySeedId", pending.context().citySeedId());
        if (!"waiting_for_generation".equals(text(item, "status")))
            throw new IOException("PLANNING_EXTENSION_INPUT_CHANGED: city planning is no longer ready");
        var current = context(pending.context().runDirectory(), pending.context().citySeedId());
        if (!current.inputRevision().equals(pending.context().inputRevision()))
            throw new IOException("PLANNING_EXTENSION_INPUT_CHANGED: " + pending.entry().id());
    }
    private void pin(PlanningExtensionContext context) throws IOException {
        JsonObject saved = readJournal(context.runDirectory(), context.citySeedId());
        checkMissing(saved, context);
        if (!context.inputRevision().equals(text(saved, "inputRevision"))) saved.add("receipts", new JsonObject());
        saved.addProperty("schema", "planning_extension_receipts.v1");
        saved.addProperty("inputRevision", context.inputRevision());
        var required = object(saved, "required");
        for (var entry : registry.entries()) required.addProperty(entry.id(), entry.version());
        saved.add("required", required);
        if (!saved.has("receipts")) saved.add("receipts", new JsonObject());
        writeJournal(context, saved);
    }
    private void checkMissing(JsonObject saved, PlanningExtensionContext context) throws IOException {
        for (var required : object(saved, "required").entrySet()) {
            if (registry.entry(required.getKey()) != null) continue;
            var prior = object(object(saved, "receipts"), required.getKey());
            if (!context.inputRevision().equals(text(saved, "inputRevision")) || !finished(prior))
                throw new IOException("PLANNING_EXTENSION_MISSING: " + required.getKey());
        }
    }
    private void receipt(Pending pending, String status) throws IOException {
        assertCurrent(pending);
        var context = pending.context();
        var saved = readJournal(context.runDirectory(), context.citySeedId());
        var receipts = object(saved, "receipts");
        var result = new JsonObject();
        result.addProperty("taskRevision", pending.taskRevision());
        result.addProperty("status", status);
        receipts.add(pending.entry().id(), result); saved.add("receipts", receipts);
        writeJournal(context, saved);
    }
    private Pending pending(PlanningExtensionRegistry.Entry entry, PlanningExtensionContext source) {
        String revision = revision(entry, source);
        var context = new PlanningExtensionContext(source.runId(), source.realmId(), source.citySeedId(),
                source.runDirectory(), source.inputRevision(), revision, source.realmProfile(), source.citySeed(), source.blueprint());
        return new Pending(entry, context, revision);
    }
    private String revision(PlanningExtensionRegistry.Entry entry, PlanningExtensionContext context) {
        return revision(entry, context, new HashMap<>());
    }
    private String revision(PlanningExtensionRegistry.Entry entry, PlanningExtensionContext context, Map<String,String> memo) {
        if (memo.containsKey(entry.id())) return memo.get(entry.id());
        var value = new JsonObject();
        value.addProperty("input", context.inputRevision());
        value.addProperty("id", entry.id()); value.addProperty("version", entry.version());
        for (String dependency : new TreeSet<>(entry.after()))
            value.addProperty(dependency, revision(registry.entry(dependency), context, memo));
        String revision = hash(value);
        memo.put(entry.id(), revision);
        return revision;
    }
    private static boolean done(JsonObject saved, String id, String revision) {
        var receipt = object(object(saved, "receipts"), id);
        return finished(receipt) && revision.equals(text(receipt, "taskRevision"));
    }
    private static boolean finished(JsonObject receipt) { return Set.of("complete", "skipped").contains(text(receipt, "status")); }
    private static PlanningExtensionContext context(Path run, String city) throws IOException {
        requireId(city);
        JsonObject seed = find(readRequired(run.resolve("city_seed_registry.json")), "citySeeds", "citySeedId", city);
        String realm = text(seed, "realmId");
        JsonElement profiles = JsonParser.parseString(Files.readString(run.resolve("realm_profiles.json")));
        JsonObject profile = find(profiles, "realmProfiles", "realmId", realm);
        Path blueprintPath = run.resolve("city_test_runs").resolve(city).resolve("steps/blueprint/city_blueprint.json");
        JsonObject blueprint = readRequired(blueprintPath);
        var source = new JsonObject(); source.add("citySeed", seed); source.add("realmProfile", profile); source.add("blueprint", blueprint);
        return new PlanningExtensionContext(run.getFileName().toString(), realm, city, run, hash(source), "", profile, seed, blueprint);
    }
    private static JsonObject find(JsonElement source, String arrayKey, String idKey, String id) throws IOException {
        JsonArray array = source.isJsonArray() ? source.getAsJsonArray() : source.getAsJsonObject().getAsJsonArray(arrayKey);
        if (array != null) for (var value : array) if (value.isJsonObject() && id.equals(text(value.getAsJsonObject(), idKey))) return value.getAsJsonObject().deepCopy();
        throw new IOException("PLANNING_EXTENSION_CONTEXT_MISSING: " + idKey + "=" + id);
    }
    private static JsonObject readRequired(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("PLANNING_EXTENSION_CONTEXT_MISSING: " + path.getFileName());
        try { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
        catch (RuntimeException error) { throw new IOException("PLANNING_EXTENSION_CONTEXT_INVALID: " + path.getFileName(), error); }
    }
    private static JsonObject readJournal(Path run, String city) throws IOException {
        Path path = journalPath(run, city);
        if (!Files.exists(path)) return new JsonObject();
        var saved = readRequired(path);
        if (!"planning_extension_receipts.v1".equals(text(saved, "schema"))
                || !saved.has("required") || !saved.get("required").isJsonObject()
                || !saved.has("receipts") || !saved.get("receipts").isJsonObject())
            throw new IOException("PLANNING_EXTENSION_RECEIPTS_INVALID: " + city);
        return saved;
    }
    private static Path journalPath(Path run, String city) {
        requireId(city);
        return run.resolve("automation/planning_extensions").resolve(city + ".json");
    }
    private static void requireId(String city) {
        if (city == null || !city.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("PLANNING_EXTENSION_CITY_ID_INVALID");
    }
    private static void writeJournal(PlanningExtensionContext context, JsonObject saved) throws IOException {
        Path target = journalPath(context.runDirectory(), context.citySeedId());
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "receipt-", ".tmp");
        try {
            Files.writeString(temporary, saved.toString());
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static JsonArray definitions(PlanningExtensionRegistry.Entry entry) {
        var result = new JsonArray();
        for (var tool : entry.tools()) {
            var definition = new JsonObject(); definition.addProperty("type", "function");
            definition.addProperty("name", tool.name()); definition.addProperty("description", tool.description());
            definition.add("parameters", tool.parameters()); result.add(definition);
        }
        return result;
    }
    private static JsonObject accepted(JsonObject output, boolean complete) {
        var result = new JsonObject(); result.addProperty("ok", true); result.add("extensionOutput", output.deepCopy());
        result.addProperty("hostDecisionCommitted", complete); return result;
    }
    private static JsonObject rejected(String error) { return rejected(error, new JsonObject()); }
    private static JsonObject rejected(String error, JsonObject output) {
        var result = new JsonObject(); result.addProperty("ok", false); result.addProperty("error", error);
        result.add("extensionOutput", output.deepCopy()); return result;
    }
    private static JsonObject object(JsonObject source, String key) {
        return source.has(key) && source.get(key).isJsonObject() ? source.getAsJsonObject(key).deepCopy() : new JsonObject();
    }
    private static String text(JsonObject source, String key) {
        return source.has(key) && !source.get(key).isJsonNull() ? source.get(key).getAsString() : "";
    }
    private static String hash(JsonElement value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static String canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var sorted = new TreeMap<String, JsonElement>(value.getAsJsonObject().asMap());
            return sorted.entrySet().stream().map(e -> new JsonPrimitive(e.getKey()) + ":" + canonical(e.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value.isJsonArray()) return value.getAsJsonArray().asList().stream().map(PlanningExtensions::canonical)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return value.toString();
    }
}
