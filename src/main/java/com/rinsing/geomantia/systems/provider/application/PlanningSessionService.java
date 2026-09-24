package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Provider-independent planning entry point; all progress is recovered from world artifacts. */
public final class PlanningSessionService implements AutoCloseable {
    public static String programBlockMessage(JsonObject queue) {
        JsonObject current = queue.has("currentCity") ? queue.getAsJsonObject("currentCity") : queue;
        if (queue.has("items") && queue.has("currentCitySeedId")) {
            for (var value : queue.getAsJsonArray("items")) {
                JsonObject item = value.getAsJsonObject();
                if (queue.get("currentCitySeedId").equals(item.get("citySeedId"))) { current = item; break; }
            }
        }
        String reason = current.has("failureReasonCode") ? current.get("failureReasonCode").getAsString()
                : current.has("reasonCode") ? current.get("reasonCode").getAsString() : "POST_D4_PROGRAM_FAILURE";
        String step = current.has("failedStep") ? current.get("failedStep").getAsString() : "";
        String detail = current.has("message") ? current.get("message").getAsString()
                : current.has("error") ? current.get("error").getAsString() : "";
        return "PLANNING_HOST_BLOCKED: " + (step.isBlank() ? "" : step + " · ") + reason
                + (detail.isBlank() ? "" : " · " + detail);
    }

    private final ProviderPlanningDiscovery discovery;
    private final PlanningExtensions extensions;
    private final Path serverDirectory, debugRoot;
    private final int port;
    @FunctionalInterface interface TurnPreparer {
        public PreparedPlanningTurn prepare(ProviderPlanningDiscovery.PlanningStep step, ProviderPlanningToolGateway gateway) throws Exception;
    }
    private final TurnPreparer turnPreparer;
    private final PlanningLease lease = new PlanningLease();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Geomantia-Planning-Session"); t.setDaemon(true); return t;
    });
    private volatile PreparedPlanningTurn prepared;
    private volatile String taskId = "", preparedOwner = "", failure = "";
    private volatile boolean running;
    private String lastActionId = "";
    private String lastActionTask = "", lastActionInput = "";
    private JsonObject lastActionResult;

    public PlanningSessionService(Path serverDirectory, Path debugRoot, int port, long seed) {
        this(serverDirectory, debugRoot, port, seed, PlanningExtensionRegistry.empty(), null);
    }
    public PlanningSessionService(Path serverDirectory, Path debugRoot, int port, long seed, PlanningExtensionRegistry registry) {
        this(serverDirectory, debugRoot, port, seed, registry, null);
    }
    PlanningSessionService(Path serverDirectory, Path debugRoot, int port, long seed, TurnPreparer turnPreparer) {
        this(serverDirectory, debugRoot, port, seed, PlanningExtensionRegistry.empty(), turnPreparer);
    }
    private PlanningSessionService(Path serverDirectory, Path debugRoot, int port, long seed,
                                   PlanningExtensionRegistry registry, TurnPreparer turnPreparer) {
        this.serverDirectory = serverDirectory; this.debugRoot = debugRoot; this.port = port;
        discovery = new ProviderPlanningDiscovery(debugRoot, seed, registry);
        extensions = new PlanningExtensions(registry);
        this.turnPreparer = turnPreparer == null
                ? (step, gateway) -> PreparedPlanningTurn.prepare(step, gateway, serverDirectory, debugRoot) : turnPreparer;
    }
    public PreparedPlanningTurn prepare(ProviderPlanningDiscovery.PlanningStep step, ProviderPlanningToolGateway gateway) throws Exception {
        return step.stage() == ProviderPlanningDiscovery.Stage.EXTENSION ? extensions.prepare(step) : turnPreparer.prepare(step, gateway);
    }
    public static boolean isCityDesign(ProviderPlanningDiscovery.PlanningStep step) { return PreparedCityDesignTurn.applies(step); }
    public ProviderPlanningDiscovery discovery() { return discovery; }
    public JsonObject artifact(String operation, String path, String query, int offset) throws Exception {
        Path run = discovery.nextStep().runDirectory().toRealPath();
        if (!run.startsWith(debugRoot.toRealPath())) throw new IllegalArgumentException("ARTIFACT_OUT_OF_SCOPE");
        return PlanningArtifacts.read(run, operation, path, query, offset);
    }
    public synchronized String acquireEmbedded() {
        String token = lease.acquire("embedded");
        if (!token.equals(preparedOwner)) { prepared = null; taskId = ""; failure = ""; }
        return token;
    }
    public AutoCloseable enter(String token) { return lease.enter(token); }
    public boolean owns(String token) { return lease.owns(token); }
    public void heartbeat(String token) { lease.touch(token); }
    public synchronized void release(String token) {
        lease.release(token); prepared = null; taskId = ""; preparedOwner = "";
    }
    public ProviderPlanningToolGateway gateway(ProviderPlanningDiscovery.PlanningStep step, String token) {
        return ProviderPlanningToolGateway.forStep(port, serverDirectory, debugRoot, step).withPlanningToken(token);
    }
    public JsonObject snapshot() throws Exception {
        var step = discovery.nextStep();
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("runId", step.runId());
        result.addProperty("realmId", step.realmId());
        result.addProperty("citySeedId", step.citySeedId());
        result.addProperty("stage", step.stage().name());
        result.addProperty("nextAction", step.nextAction());
        if (step.stage() == ProviderPlanningDiscovery.Stage.EXTENSION) {
            result.add("extensionId", step.state().get("extensionId"));
            result.add("extensionTitle", step.state().get("extensionTitle"));
        }
        result.addProperty("owner", lease.owner().isEmpty() ? "" : lease.owner().equals("embedded") ? "embedded" : "external");
        String status = step.stage() == ProviderPlanningDiscovery.Stage.COMPLETE ? "complete"
                : step.stage() == ProviderPlanningDiscovery.Stage.WAITING ? "waiting" : "ready";
        JsonObject queue = step.state().has("cityDesignQueue") ? step.state().getAsJsonObject("cityDesignQueue") : step.state();
        String error = failure;
        if (queue.has("status") && ("blocked_by_program".equals(queue.get("status").getAsString())
                || step.stage() == ProviderPlanningDiscovery.Stage.WAITING && "needs_agent".equals(queue.get("status").getAsString())))
            error = programBlockMessage(queue);
        result.addProperty("status", running ? "running" : !error.isBlank() ? "blocked" : status);
        result.addProperty("error", error);
        result.addProperty("cursor", step.semanticIdentity() + ":" + taskId + ":" + running + ":" + error + ":" + result.get("owner"));
        result.addProperty("hasSavedProgress", Files.isDirectory(step.runDirectory()));
        if (savedDesign(step)) {
            result.addProperty("instruction", "设计已保存，尚未施工。用户授权继续后调用 planning_resume(retry=true)，直接使用保存蓝图入队；不要再次提交 D4。");
        } else if (retryableProgramBlock(step)) {
            result.addProperty("instruction", "当前城市因程序错误暂停。确认修复并获用户授权后，调用 planning_resume(retry=true)，通过当前规划会话重试已有城市；不要改写设计或绕过会话调用旧接口。");
        }
        result.addProperty("artifactRoot", step.runDirectory().toString());
        return result;
    }
    public synchronized JsonObject resume(String owner, String token, boolean retry) throws Exception {
        if (owner == null || !owner.matches("[A-Za-z0-9_-]{8,100}")) throw new IllegalArgumentException("PLANNING_OWNER_REQUIRED");
        // Existing sessions must prove ownership; the transport keeps this credential out of prompts.
        if (!lease.owner().isEmpty() && lease.owner().equals("external-" + owner)) lease.require(token);
        String acquired = lease.acquire("external-" + owner);
        if (!acquired.equals(preparedOwner)) {
            prepared = null; taskId = ""; failure = ""; preparedOwner = acquired;
            lastActionId = ""; lastActionResult = null;
        }
        if (retry && !running) { failure = ""; prepared = null; }
        if (!running && failure.isBlank() && (prepared == null || prepared.control().finished())) {
            if (prepared != null && !prepared.control().result(0).success()) failure = prepared.control().result(0).errorCode();
            else {
                prepared = null;
                running = true;
                AutoCloseable pin = lease.enter(acquired);
                worker.submit(() -> {
                    try (pin) { advance(acquired, retry); }
                    catch (Exception ex) { failure = message(ex); }
                    finally { running = false; }
                });
            }
        }
        JsonObject result = view(acquired);
        result.addProperty("leaseToken", acquired);
        return result;
    }
    private void advance(String token, boolean retry) throws Exception {
        var initial = discovery.nextStep();
        if (retry && (savedDesign(initial) || retryableProgramBlock(initial))) {
            var result = gateway(initial, token).executeHost("city_post_d4_auto_compile_retry", new JsonObject());
            String error = PlanningTurnControl.failure(PlanningTurnControl.payload(result));
            if (!error.isBlank()) throw new IllegalStateException(error);
            return;
        }
        // Bounded program work per request; no model invocation and no Provider credentials.
        for (int count = 0; count < 8; count++) {
            var step = discovery.nextStep();
            if (!step.stage().actionable()) return;
            var gateway = gateway(step, token);
            if (!PlanningStepPolicy.hostOnly(step)) {
                prepared = prepare(step, gateway);
                if (prepared == null) continue;
                taskId = UUID.randomUUID().toString();
                return;
            }
            var result = gateway.executeHost(step.nextAction(), PlanningStepPolicy.hostArguments(step, debugRoot));
            String error = PlanningTurnControl.failure(PlanningTurnControl.payload(result));
            if (!error.isBlank()) throw new IllegalStateException(error);
            if (step.semanticIdentity().equals(discovery.nextStep().semanticIdentity()))
                throw new IllegalStateException("PLANNING_HOST_NO_PROGRESS: " + step.nextAction());
        }
    }
    private static boolean retryableProgramBlock(ProviderPlanningDiscovery.PlanningStep step) {
        JsonObject queue = step.state().has("cityDesignQueue")
                ? step.state().getAsJsonObject("cityDesignQueue") : step.state();
        return step.stage() == ProviderPlanningDiscovery.Stage.WAITING && queue.has("status")
                && "blocked_by_program".equals(queue.get("status").getAsString())
                && "city_post_d4_auto_compile_retry".equals(step.nextAction());
    }
    private static boolean savedDesign(ProviderPlanningDiscovery.PlanningStep step) {
        JsonObject queue = step.state().has("cityDesignQueue")
                ? step.state().getAsJsonObject("cityDesignQueue") : step.state();
        return step.stage() == ProviderPlanningDiscovery.Stage.WAITING && queue.has("status")
                && "design_saved".equals(queue.get("status").getAsString())
                && "city_post_d4_auto_compile_retry".equals(step.nextAction());
    }
    public JsonObject view(String token) throws Exception {
        lease.touch(token);
        JsonObject result = snapshot();
        PreparedPlanningTurn task = prepared;
        if (!running && task != null && failure.isBlank()) {
            result.addProperty("taskId", taskId);
            result.addProperty("taskFinished", task.control().finished());
            result.add("state", task.state().deepCopy());
            result.add("tools", task.control().definitions(task.tools()));
            result.addProperty("instructions", AgentPromptConfig.read("agent.md") +
                    "\n通过 planning_action 调用本次 tools 中的工具。任务完成后调用 planning_resume 领取下一项；程序运行时使用 planning_wait。用户暂停时调用 planning_release。图片必须实际读取，不能仅凭路径判断。不要调用旧入口绕过本次任务范围。");
            JsonArray images = new JsonArray();
            for (Path path : task.images()) {
                Path real = path.toRealPath();
                if (!real.startsWith(debugRoot.toRealPath()) || Files.size(real) > 8L * 1024 * 1024)
                    throw new IllegalStateException("PLANNING_IMAGE_OUT_OF_SCOPE");
                JsonObject image = new JsonObject();
                image.addProperty("type", "image"); image.addProperty("mimeType", "image/png");
                image.addProperty("path", real.toString());
                image.addProperty("data", Base64.getEncoder().encodeToString(Files.readAllBytes(real)));
                images.add(image);
            }
            result.add("imageEvidence", images);
        }
        return result;
    }
    public JsonObject action(String token, String task, String actionId, String tool, JsonObject arguments) throws Exception {
        PreparedPlanningTurn current;
        AutoCloseable pin;
        synchronized (this) {
            lease.touch(token);
            if (actionId == null || actionId.isBlank()) throw new IllegalArgumentException("PLANNING_ACTION_ID_REQUIRED");
            String input = tool + "\n" + arguments;
            if (actionId.equals(lastActionId) && lastActionResult != null && task.equals(lastActionTask)) {
                if (!input.equals(lastActionInput)) throw new IllegalArgumentException("PLANNING_ACTION_ID_REUSED");
                return lastActionResult.deepCopy();
            }
            if (running) throw new IllegalStateException("PLANNING_OPERATION_RUNNING");
            if (prepared == null || !taskId.equals(task) || !preparedOwner.equals(token))
                throw new IllegalStateException("PLANNING_TASK_STALE: call planning_resume");
            if (!failure.isBlank()) throw new IllegalStateException(failure);
            current = prepared; running = true; pin = lease.enter(token);
        }
        try (pin) {
            JsonObject result = new JsonObject();
            JsonElement output = current.control().execute(tool, arguments);
            result.add("output", output);
            result.addProperty("taskFinished", current.control().finished());
            String rejection = PlanningTurnControl.failure(PlanningTurnControl.payload(output));
            result.addProperty("ok", current.control().result(0).success() && rejection.isBlank());
            result.addProperty("error", current.control().result(0).success() ? rejection : current.control().result(0).errorCode());
            synchronized (this) {
                lastActionId = actionId; lastActionTask = task; lastActionInput = tool + "\n" + arguments;
                lastActionResult = result.deepCopy();
            }
            return result;
        } finally { running = false; }
    }
    public JsonObject await(String token, String cursor, int timeoutSeconds) throws Exception {
        lease.touch(token);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0, Math.min(20, timeoutSeconds)));
        JsonObject result;
        do {
            result = snapshot();
            if (!result.get("cursor").getAsString().equals(cursor) || System.nanoTime() >= deadline) break;
            Thread.sleep(250);
        } while (true);
        // Polls contain no image payload; resume retrieves the next full decision package.
        if (!result.has("instruction")) result.addProperty("instruction", "状态改变或任务准备完成后调用 planning_resume；waiting/running 时继续 planning_wait；blocked 时说明错误并停止。");
        return result;
    }
    private static String message(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
    @Override public void close() { worker.shutdownNow(); }
}
