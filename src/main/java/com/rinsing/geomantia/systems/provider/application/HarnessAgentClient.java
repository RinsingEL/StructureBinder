package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import com.rinsing.geomantia.systems.provider.application.ProviderConfigStore.Credentials;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Geomantia plugin host for the pinned DeepSeek Harness kernel over private stdio. */
final class HarnessAgentClient implements ProviderAgentClient {
    private Path serverDirectory;
    private Path home;
    private volatile Process process;

    @Override public void start(Path serverDirectory, Path debugRoot, int apiPort) {
        close();
        this.serverDirectory = serverDirectory.toAbsolutePath().normalize();
        this.home = debugRoot.toAbsolutePath().normalize().resolve(".harness");
    }

    @Override public DeepSeekToolLoopClient.LoopResult run(PlayerProviderConfig config, Credentials credentials,
            String sessionId, JsonObject state, List<Path> images, List<String> allowedTools,
            DeepSeekToolLoopClient.ToolExecutor executor, Consumer<AgentActivityEvent> listener) {
        Consumer<AgentActivityEvent> activity = listener == null ? event -> { } : listener;
        if (!config.enabled()) return failure("PROVIDER_DISABLED");
        if (!credentials.present()) return failure("PROVIDER_API_KEY_MISSING");
        if (home == null) return failure("HARNESS_NOT_INITIALIZED");
        int calls = 0;
        try {
            Path runtime = new HarnessPortableRuntime().ensureInstalled(serverDirectory,
                    text -> emit(activity, "system", text));
            Files.createDirectories(home);
            Process child = new ProcessBuilder(runtime.resolve("node.exe").toString(), runtime.resolve("main.mjs").toString())
                    .directory(serverDirectory.toFile())
                    .redirectError(ProcessBuilder.Redirect.appendTo(home.resolve("runtime.log").toFile())).start();
            process = child;
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader input = child.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = input.readLine()) != null) lines.put(line);
                } catch (IOException | InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally { lines.offer(""); }
            }, "geomantia-harness-output");
            reader.setDaemon(true); reader.start();
            try (BufferedWriter writer = child.outputWriter(StandardCharsets.UTF_8)) {
                JsonObject request = new JsonObject();
                request.addProperty("type", "run");
                request.addProperty("home", home.toString());
                request.addProperty("cwd", serverDirectory.toString());
                if (state.has("runId")) {
                    Path artifactRoot = home.getParent().resolve(state.get("runId").getAsString()).normalize();
                    if (!artifactRoot.startsWith(home.getParent()) || artifactRoot.equals(home.getParent()))
                        throw new IOException("HARNESS_ARTIFACT_ROOT_INVALID");
                    if (Files.isDirectory(artifactRoot)) {
                        Path actual = artifactRoot.toRealPath();
                        if (!actual.startsWith(home.getParent().toRealPath())) throw new IOException("HARNESS_ARTIFACT_ROOT_INVALID");
                        request.addProperty("artifactRoot", actual.toString());
                    }
                }
                request.addProperty("sessionId", sessionId);
                request.addProperty("baseUrl", config.baseUrl());
                request.addProperty("model", config.model());
                request.addProperty("apiProtocol", config.apiProtocol());
                request.addProperty("apiKey", credentials.apiKey());
                request.addProperty("affinity", ProviderRequestHeaders.session(sessionId));
                request.addProperty("timeoutSeconds", config.timeoutSeconds());
                request.addProperty("instructions", AgentPromptConfig.agent("harness"));
                request.add("content", promptContent(state, images));
                request.add("tools", ProviderPlanningToolCatalog.definitions(allowedTools));
                send(writer, request);
                emit(activity, "system", "DeepSeek Harness 已接管：" + sessionId);
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
                while (System.nanoTime() < deadline) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    String line = lines.poll(1, TimeUnit.SECONDS);
                    if (line == null) continue;
                    if (line.isEmpty()) return failure("HARNESS_PROCESS_EXITED");
                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                    switch (event.get("type").getAsString()) {
                        case "activity" -> emit(activity, event.get("kind").getAsString(), event.get("text").getAsString());
                        case "request_evidence" -> {
                            Files.writeString(home.resolve("request-evidence.jsonl"), event + "\n", StandardCharsets.UTF_8,
                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            emit(activity, "system", "Harness 请求已附图片：" + event.getAsJsonArray("images").size() + " 张");
                        }
                        case "tool" -> {
                            String tool = event.get("name").getAsString();
                            if (!allowedTools.contains(tool)) throw new IOException("HARNESS_TOOL_OUT_OF_SCOPE");
                            emit(activity, "tool", "调用 " + tool);
                            calls++;
                            JsonElement result;
                            try { result = executor.execute(tool, event.getAsJsonObject("arguments")); }
                            catch (Exception error) {
                                JsonObject failed = new JsonObject(); failed.addProperty("ok", false);
                                failed.addProperty("error", error.getMessage()); result = failed;
                            }
                            JsonObject response = new JsonObject(); response.addProperty("type", "tool_result");
                            response.add("id", event.get("id")); response.add("output", result);
                            response.addProperty("finished", executor instanceof PlanningTurnControl control && control.finished());
                            send(writer, response);
                            emit(activity, "result", "工具完成 " + tool);
                        }
                        case "done" -> {
                            if (executor instanceof PlanningTurnControl control && control.finished()) return control.result(calls);
                            boolean ok = event.get("ok").getAsBoolean();
                            return new DeepSeekToolLoopClient.LoopResult(ok, ok ? "completed" : "failed",
                                    ok ? "" : "HARNESS_RUN_FAILED: " + event.get("error").getAsString(), calls,
                                    event.has("text") ? event.get("text").getAsString() : "");
                        }
                        case "protocol_error" -> throw new IOException("HARNESS_PROTOCOL_ERROR");
                        default -> throw new IOException("HARNESS_PROTOCOL_EVENT_UNKNOWN");
                    }
                }
                return failure("HARNESS_RUN_TIMEOUT");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return failure("HARNESS_RUN_INTERRUPTED");
        } catch (IOException | RuntimeException error) {
            emit(activity, "error", "Harness 运行失败：" + error.getMessage());
            return failure("HARNESS_RUN_FAILED");
        } finally { close(); }
    }

    static JsonArray promptContent(JsonObject state, List<Path> images) throws IOException {
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject(); text.addProperty("type", "input_text");
        text.addProperty("text", "继续当前宿主规划状态：\n" + state); content.add(text);
        for (Path path : images == null ? List.<Path>of() : images) {
            if (!Files.isRegularFile(path) || Files.size(path) > 20L * 1024 * 1024)
                throw new IOException("HARNESS_REQUIRED_IMAGE_UNAVAILABLE: " + path.getFileName());
            JsonObject image = new JsonObject(); image.addProperty("type", "input_image");
            JsonObject label = new JsonObject(); label.addProperty("type", "input_text");
            label.addProperty("text", "当前必需预览：" + path); content.add(label);
            image.addProperty("image_url", "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            content.add(image);
        }
        return content;
    }

    private static void send(BufferedWriter writer, JsonObject value) throws IOException {
        writer.write(value.toString()); writer.newLine(); writer.flush();
    }
    private static void emit(Consumer<AgentActivityEvent> listener, String kind, String text) {
        AgentActivityEvent.emitText(listener, kind, text);
    }
    private static DeepSeekToolLoopClient.LoopResult failure(String code) {
        return new DeepSeekToolLoopClient.LoopResult(false, "failed", code, 0, "");
    }
    @Override public synchronized void close() {
        Process child = process; process = null;
        if (child != null) stopProcess(child);
    }

    static void stopProcess(Process child) {
        boolean interrupted = Thread.interrupted();
        try {
            if (!child.isAlive()) return;
            child.destroy();
            try { if (child.waitFor(2, TimeUnit.SECONDS)) return; }
            catch (InterruptedException ignored) { interrupted = true; }
            child.destroyForcibly();
            // Termination is asynchronous, including on Windows where the executable stays locked.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (child.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("HARNESS_PROCESS_STOP_TIMEOUT");
                try { child.waitFor(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
