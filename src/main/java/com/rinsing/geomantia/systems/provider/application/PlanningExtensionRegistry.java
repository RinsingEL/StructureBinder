package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import com.rinsing.geomantia.api.planning.*;
import java.util.*;
import java.util.concurrent.*;

/** Immutable per-server metadata plus an explicit dispatcher for addon callbacks. */
public final class PlanningExtensionRegistry {
    record Entry(PlanningExtension extension, String id, String version, String title,
                 PlanningHook hook, List<String> after, List<PlanningTool> tools) {}
    private final List<Entry> ordered;
    private final Map<String, Entry> byId;
    private final Executor dispatcher;
    public static PlanningExtensionRegistry empty() { return new PlanningExtensionRegistry(List.of(), Runnable::run); }
    public PlanningExtensionRegistry(Collection<PlanningExtension> extensions, Executor dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
        if (extensions.size() > 128) throw invalid("too many extensions");
        var remaining = new TreeMap<String, Entry>();
        var toolNames = new HashSet<>(ProviderPlanningToolGateway.allowedTools());
        for (var extension : extensions) {
            String id = extension.id(), version = extension.version(), title = extension.title();
            if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || id.length() > 160)
                throw invalid("invalid extension ID");
            if (version == null || version.isBlank() || version.length() > 80) throw invalid(id + ": invalid version");
            if (title == null || title.isBlank() || title.length() > 160) throw invalid(id + ": invalid title");
            var after = List.copyOf(extension.after());
            var tools = List.copyOf(extension.tools());
            if (tools.isEmpty() || tools.size() > 32) throw invalid(id + ": requires 1..32 tools");
            for (var tool : tools) {
                if (!tool.name().matches("[a-zA-Z0-9_-]{1,64}") || !toolNames.add(tool.name()))
                    throw invalid(id + ": duplicate or invalid tool " + tool.name());
                if (tool.description().isBlank() || tool.description().length() > 8000)
                    throw invalid(id + ": invalid tool description");
                ExtensionToolSchema.validateSchema(tool.parameters());
            }
            var entry = new Entry(extension, id, version, title, Objects.requireNonNull(extension.hook()), after, tools);
            if (remaining.putIfAbsent(id, entry) != null) throw invalid("duplicate extension " + id);
        }
        this.byId = Map.copyOf(remaining);
        for (var entry : remaining.values()) for (String dependency : entry.after()) {
            var parent = byId.get(dependency);
            if (parent == null || parent.hook() != entry.hook()) throw invalid(entry.id() + ": missing dependency " + dependency);
        }
        var result = new ArrayList<Entry>();
        var done = new HashSet<String>();
        while (!remaining.isEmpty()) {
            var next = remaining.values().stream().filter(e -> done.containsAll(e.after())).findFirst()
                    .orElseThrow(() -> invalid("dependency cycle"));
            result.add(next); done.add(next.id()); remaining.remove(next.id());
        }
        ordered = List.copyOf(result);
    }
    List<Entry> entries() { return ordered; }
    Entry entry(String id) { return byId.get(id); }
    <T> T call(Callable<T> operation) throws Exception {
        var future = new CompletableFuture<T>();
        dispatcher.execute(() -> {
            try { future.complete(operation.call()); }
            catch (Throwable error) { future.completeExceptionally(error); }
        });
        try { return future.get(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw error; }
        catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw new IllegalStateException("Addon callback failed", error.getCause());
        }
    }
    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("PLANNING_EXTENSION_REGISTRATION_INVALID: " + detail);
    }
}
