package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rinsing.geomantia.systems.provider.application.ProviderConfigStore.Credentials;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Drives a persistent, tool-using Hermes Agent through its local Runs API. */
final class HermesAgentClient implements ProviderAgentClient {
    private static final Duration BOOT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(20);
    private static final String MCP_RESOURCE = "/geomantia/sidecar/geomantia-mcp-bundle.mjs";
    private static final String INSTRUCTIONS = """
            You are the Geomantia in-game planning agent. Work only on the current host-provided planning state.
            Use only the enabled Geomantia MCP tools and follow formal nextAction and validation evidence. Never read
            source code or project documents, invent artifact contents, bypass a failure budget, or start another
            run/realm/city. A completed tool call is not proof of progress: stop after the formal state advances or
            becomes waiting, failed, waiting_for_generation, or requires a human. The host resumes this same session
            when new deterministic work is available. Never poll a background compilation; the host will wake you.
            For City D4, only CONNECTION creates a terrain-routed main road. Keep non-isolated groups in one reachable
            relation network and use explicit CONNECTION edges for actual destinations. Submit a complete blueprint,
            but on validation failure revise the existing design instead of redesigning the city from scratch.
            """;

    private final HttpClient httpClient;
    private final HermesPortableRuntime portableRuntime = new HermesPortableRuntime();
    private Path serverDirectory;
    private Path debugRoot;
    private int minecraftApiPort;
    private Process process;
    private int sidecarPort;
    private String apiKey = "";
    private String profileName = "";
    private String fingerprint = "";

    HermesAgentClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    HermesAgentClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public synchronized void start(Path serverDirectory, Path debugRoot, int apiPort) {
        close();
        this.serverDirectory = serverDirectory.toAbsolutePath().normalize();
        this.debugRoot = debugRoot.toAbsolutePath().normalize();
        this.minecraftApiPort = apiPort;
        this.profileName = "geomantia-" + Integer.toUnsignedString(this.debugRoot.toString().hashCode(), 36);
    }

    @Override
    public DeepSeekToolLoopClient.LoopResult run(
            PlayerProviderConfig config, Credentials credentials, String sessionId,
            JsonObject initialState, List<Path> initialImages, List<String> allowedTools,
            DeepSeekToolLoopClient.ToolExecutor ignoredToolExecutor,
            Consumer<AgentActivityEvent> activityListener) {
        if (!config.enabled()) return failure("PROVIDER_DISABLED");
        if (!credentials.present()) return failure("PROVIDER_API_KEY_MISSING");
        if (initialState == null || allowedTools == null || allowedTools.isEmpty() || sessionId == null
                || sessionId.isBlank()) return failure("PROVIDER_AGENT_INPUT_INVALID");
        Consumer<AgentActivityEvent> activity = activityListener == null ? ignored -> { } : activityListener;
        try {
            ensureStarted(config.validated(), credentials, allowedTools, activity);
            ensureSession(sessionId, config.model());
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("message", prompt(initialState, initialImages));
            requestBody.addProperty("instructions", INSTRUCTIONS);
            HttpRequest request = authorizedRequest("/api/sessions/" + sessionId + "/chat/stream")
                    .timeout(RUN_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() / 100 != 2) {
                emit(activity, "error", "Hermes 会话任务失败：HTTP " + response.statusCode());
                return failure("HERMES_RUN_START_FAILED");
            }
            emit(activity, "system", "Hermes 会话已接管：" + sessionId);
            SessionStreamResult terminal = streamSession(response.body(), activity);
            String status = terminal.status();
            String output = terminal.output();
            if (!output.isBlank()) emit(activity, "model", compact(output));
            if ("completed".equals(status)) {
                return new DeepSeekToolLoopClient.LoopResult(true, status, "", terminal.toolCalls(), output);
            }
            return new DeepSeekToolLoopClient.LoopResult(false, status, "HERMES_RUN_"
                    + status.toUpperCase(Locale.ROOT), terminal.toolCalls(), output);
        } catch (HermesException exception) {
            emit(activity, "error", exception.visibleMessage);
            return failure(exception.code);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure("HERMES_RUN_INTERRUPTED");
        } catch (IOException | RuntimeException exception) {
            emit(activity, "error", "Hermes 通信失败：" + exception.getClass().getSimpleName());
            return failure("HERMES_RUN_FAILED");
        }
    }

    private synchronized void ensureStarted(PlayerProviderConfig config, Credentials credentials,
                                            List<String> allowedTools,
                                            Consumer<AgentActivityEvent> activity) throws IOException, InterruptedException {
        requireStartedContext();
        String desired = config.providerKind() + "|" + config.baseUrl() + "|" + config.model() + "|"
                + config.apiProtocol() + "|" + config.timeoutSeconds() + "|" + credentials.apiKey().hashCode()
                + "|" + minecraftApiPort + "|" + String.join(",", allowedTools);
        if (process != null && process.isAlive() && desired.equals(fingerprint) && healthy()) return;
        stopProcess();

        Path hermesRuntime;
        try {
            hermesRuntime = portableRuntime.ensureInstalled(serverDirectory,
                    message -> emit(activity, "system", message));
        } catch (IOException | RuntimeException exception) {
            String detail = exception.getMessage() == null ? exception.getClass().getSimpleName()
                    : compact(exception.getMessage());
            throw new HermesException("HERMES_RUNTIME_INSTALL_FAILED",
                    "Hermes 内置运行时准备失败：" + detail, exception);
        }
        Path bundle = extractMcpBundle();
        Path profileDirectory = profileDirectory();
        Files.createDirectories(profileDirectory);
        sidecarPort = freeLoopbackPort();
        apiKey = UUID.randomUUID().toString() + UUID.randomUUID();
        Files.writeString(profileDirectory.resolve("config.yaml"), profileConfig(config, bundle, allowedTools),
                StandardCharsets.UTF_8);

        List<String> command = new java.util.ArrayList<>(hermesCommand(hermesRuntime));
        command.add("-p");
        command.add(profileName);
        command.add("gateway");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(serverDirectory.toFile());
        builder.redirectErrorStream(true);
        Path log = runtimeDirectory().resolve("hermes-gateway.log");
        Files.createDirectories(log.getParent());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("API_SERVER_ENABLED", "true");
        builder.environment().put("API_SERVER_HOST", "127.0.0.1");
        builder.environment().put("API_SERVER_PORT", Integer.toString(sidecarPort));
        builder.environment().put("API_SERVER_KEY", apiKey);
        builder.environment().put("HERMES_HOME", profileDirectory().toString());
        builder.environment().put("OPENAI_API_KEY", credentials.apiKey());
        builder.environment().put("OPENAI_BASE_URL", config.baseUrl());
        builder.environment().put("HERMES_API_TIMEOUT", Integer.toString(config.timeoutSeconds()));
        builder.environment().put("GEOMANTIA_PROVIDER_API_KEY", credentials.apiKey());
        builder.environment().put("GEOMANTIA_MC_API_URL", "http://127.0.0.1:" + minecraftApiPort);
        builder.environment().put("PYTHONHOME", hermesRuntime.resolve("python").toString());
        builder.environment().put("PYTHONPATH", pythonPath(hermesRuntime));
        builder.environment().put("PYTHONNOUSERSITE", "1");
        String inheritedPath = builder.environment().getOrDefault("PATH", "");
        String nodePath = hermesRuntime.resolve("node").toString();
        builder.environment().put("PATH", inheritedPath.isBlank()
                ? nodePath : nodePath + File.pathSeparator + inheritedPath);
        try {
            process = builder.start();
        } catch (IOException exception) {
            String detail = exception.getMessage() == null ? exception.getClass().getSimpleName()
                    : compact(exception.getMessage());
            try {
                Files.writeString(runtimeDirectory().resolve("hermes-start-error.log"),
                        Instant.now() + " command=" + String.join(" ", command) + System.lineSeparator()
                                + detail + System.lineSeparator(),
                        StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
            throw new HermesException("HERMES_START_FAILED",
                    "无法启动 Hermes：" + detail, exception);
        }
        waitUntilHealthy();
        fingerprint = desired;
        emit(activity, "system", "Hermes Gateway 已启动，规划工具 " + allowedTools.size() + " 个");
    }

    private void ensureSession(String sessionId, String model) throws IOException, InterruptedException {
        HttpResponse<Void> existing = httpClient.send(authorizedRequest("/api/sessions/" + sessionId)
                .GET().build(), HttpResponse.BodyHandlers.discarding());
        if (existing.statusCode() / 100 == 2) return;
        if (existing.statusCode() != 404) throw new IOException("Hermes session lookup HTTP " + existing.statusCode());
        JsonObject body = new JsonObject();
        body.addProperty("id", sessionId);
        body.addProperty("model", model);
        HttpResponse<Void> created = httpClient.send(authorizedRequest("/api/sessions")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.discarding());
        if (created.statusCode() / 100 != 2 && created.statusCode() != 409) {
            throw new IOException("Hermes session create HTTP " + created.statusCode());
        }
    }

    private SessionStreamResult streamSession(Stream<String> responseLines,
                                              Consumer<AgentActivityEvent> activity) {
        int toolCalls = 0;
        String event = "";
        String output = "";
        String status = "failed";
        try (Stream<String> lines = responseLines) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    JsonObject data = object(line.substring(5).trim());
                    if ("tool.started".equals(event)) {
                        toolCalls++;
                        emit(activity, "tool", "Hermes 调用 " + first(data, "tool_name", "name", "tool"));
                    } else if ("tool.completed".equals(event)) {
                        emit(activity, "result", "Hermes 工具完成 " + first(data, "tool_name", "name", "tool"));
                    } else if ("tool.failed".equals(event) || "error".equals(event)) {
                        emit(activity, "error", "Hermes：" + compact(first(data, "error", "message", "detail")));
                    } else if ("assistant.completed".equals(event)) {
                        output = string(data, "content");
                    } else if ("run.completed".equals(event)) {
                        status = "completed";
                    }
                }
            }
        }
        return new SessionStreamResult(status, output, toolCalls);
    }

    String profileConfig(PlayerProviderConfig config, Path bundle, List<String> allowedTools) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("model:\n")
                .append("  provider: custom\n")
                .append("  default: ").append(yaml(config.model())).append('\n')
                .append("  base_url: ").append(yaml(config.baseUrl())).append('\n')
                .append("  api_key: ${GEOMANTIA_PROVIDER_API_KEY}\n")
                .append("  api_mode: ").append(yaml(apiMode(config.apiProtocol()))).append('\n')
                .append("toolsets:\n  - geomantia\n")
                .append("platform_toolsets:\n  api_server:\n    - geomantia\n")
                .append("agent:\n  max_turns: 24\n  disabled_toolsets:\n")
                .append("    - terminal\n    - file\n    - browser\n    - web\n    - memory\n")
                .append("    - session_search\n    - skills\n    - delegation\n    - cronjob\n")
                .append("compression:\n  enabled: true\n  threshold: 0.50\n  in_place: true\n")
                .append("mcp_servers:\n  geomantia:\n    command: node\n    args:\n      - ")
                .append(yaml(bundle.toString())).append('\n')
                .append("    env:\n      GEOMANTIA_MC_API_URL: ")
                .append(yaml("http://127.0.0.1:" + minecraftApiPort)).append('\n')
                .append("    tools:\n      include:\n");
        for (String tool : allowedTools) yaml.append("        - ").append(yaml(tool)).append('\n');
        yaml.append("      resources: false\n      prompts: false\n")
                .append("gateway:\n  api_server:\n    enabled: true\n    host: 127.0.0.1\n    port: ")
                .append(sidecarPort).append("\n    max_concurrent_runs: 1\n");
        return yaml.toString();
    }

    private Path extractMcpBundle() throws IOException {
        Path target = runtimeDirectory().resolve("geomantia-mcp-bundle.mjs");
        Files.createDirectories(target.getParent());
        try (InputStream input = HermesAgentClient.class.getResourceAsStream(MCP_RESOURCE)) {
            if (input != null) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            }
        }
        Path developmentBundle = serverDirectory.getParent() == null ? serverDirectory
                : serverDirectory.getParent().resolve("country_designer_mcp/dist/geomantia-mcp-bundle.mjs");
        if (Files.isRegularFile(developmentBundle)) return developmentBundle.toAbsolutePath().normalize();
        throw new HermesException("HERMES_MCP_BUNDLE_MISSING", "Hermes 的 Geomantia MCP 单文件资源缺失");
    }

    private Path runtimeDirectory() {
        return debugRoot.resolve(".provider_runtime").resolve(profileName);
    }

    private Path profileDirectory() {
        String override = System.getProperty("geomantia.hermes.profileRoot", "").trim();
        Path profiles;
        if (!override.isBlank()) {
            profiles = Path.of(override);
        } else {
            profiles = serverDirectory.resolve("config").resolve("geomantia").resolve("hermes").resolve("profiles");
        }
        return profiles.resolve(profileName).toAbsolutePath().normalize();
    }

    private static List<String> hermesCommand(Path runtime) {
        String configured = System.getProperty("geomantia.hermes.command", "").trim();
        if (!configured.isBlank()) return List.of(configured);
        return List.of(runtime.resolve("python").resolve("python.exe").toString(), "-m", "hermes_cli.main");
    }

    static String pythonPath(Path runtime) {
        Path sitePackages = runtime.resolve("site-packages");
        return String.join(File.pathSeparator,
                sitePackages.toString(),
                sitePackages.resolve("win32").toString(),
                sitePackages.resolve("win32").resolve("lib").toString(),
                sitePackages.resolve("pywin32_system32").toString());
    }

    private void waitUntilHealthy() throws InterruptedException {
        Instant deadline = Instant.now().plus(BOOT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (process == null || !process.isAlive()) {
                throw new HermesException("HERMES_START_FAILED", "Hermes Gateway 启动后立即退出");
            }
            if (healthy()) return;
            TimeUnit.MILLISECONDS.sleep(250);
        }
        stopProcess();
        throw new HermesException("HERMES_START_TIMEOUT", "Hermes Gateway 30 秒内未就绪");
    }

    private boolean healthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint("/health"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
        } catch (IOException ignored) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private HttpRequest.Builder authorizedRequest(String path) {
        return HttpRequest.newBuilder(endpoint(path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + sidecarPort + path);
    }

    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private void requireStartedContext() {
        if (serverDirectory == null || debugRoot == null || minecraftApiPort <= 0) {
            throw new HermesException("HERMES_NOT_INITIALIZED", "Hermes 尚未绑定当前世界");
        }
    }

    private static String prompt(JsonObject state, List<Path> images) {
        StringBuilder text = new StringBuilder("Continue this formal planning state:\n").append(state);
        if (images != null && !images.isEmpty()) {
            text.append("\nHost preview files (advisory evidence; prefer newer MCP image results):");
            for (Path image : images) if (image != null) text.append("\n- ").append(image.toAbsolutePath().normalize());
        }
        return text.toString();
    }

    private static String apiMode(String protocol) {
        return PlayerProviderConfig.RESPONSES.equals(protocol) ? "codex_responses" : "chat_completions";
    }

    private static String yaml(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", "\\n") + '"';
    }

    private static JsonObject object(String json) {
        try {
            JsonElement value = JsonParser.parseString(json);
            return value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private static String first(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = string(object, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        JsonElement value = object.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static String compact(String value) {
        String result = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return result.length() <= 240 ? result : result.substring(0, 237) + "...";
    }

    private static void emit(Consumer<AgentActivityEvent> activity, String kind, String message) {
        if (message != null && !message.isBlank()) {
            activity.accept(new AgentActivityEvent(Instant.now().toString(), kind, message));
        }
    }

    private static DeepSeekToolLoopClient.LoopResult failure(String code) {
        return new DeepSeekToolLoopClient.LoopResult(false, "error", code, 0, "");
    }

    private record SessionStreamResult(String status, String output, int toolCalls) { }

    @Override
    public synchronized void close() {
        stopProcess();
        fingerprint = "";
        apiKey = "";
        sidecarPort = 0;
    }

    private void stopProcess() {
        Process current = process;
        process = null;
        if (current == null || !current.isAlive()) return;
        current.destroy();
        try {
            if (!current.waitFor(3, TimeUnit.SECONDS)) current.destroyForcibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    private static final class HermesException extends RuntimeException {
        private final String code;
        private final String visibleMessage;

        private HermesException(String code, String visibleMessage) {
            super(code);
            this.code = code;
            this.visibleMessage = visibleMessage;
        }

        private HermesException(String code, String visibleMessage, Throwable cause) {
            super(code, cause);
            this.code = code;
            this.visibleMessage = visibleMessage;
        }
    }
}
