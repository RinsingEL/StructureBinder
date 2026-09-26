package com.rinsing.geomantia.harness.systems.provider.application;
import com.rinsing.geomantia.systems.provider.application.*;

import com.google.gson.JsonObject;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.harness.systems.provider.application.ProviderConfigStore.Credentials;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Background consumer for the same persistent waiting_for_agent queue used by MCP. */
public final class PlayerProviderAgentRunner implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long POLL_SECONDS = 5;
    private static final long ERROR_BACKOFF_SECONDS = 60;
    private static final long NO_PROGRESS_BACKOFF_SECONDS = 30;
    private static final int MAX_CONSECUTIVE_NO_PROGRESS = 3;

    private final ProviderConfigStore store;
    private final ProviderAgentClient legacyClient;
    private final ProviderAgentClient harnessClient;
    private final Consumer<AutomationStatus> statusListener;
    private final Consumer<AgentActivityEvent> activityListener;
    private final AtomicBoolean turnRunning = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;
    private volatile ProviderPlanningDiscovery discovery;
    private volatile PlanningSessionService planning;
    private boolean ownsPlanning;
    public PlanningSessionService planning() { return planning; }
    private volatile Path serverDirectory;
    private volatile Path debugRoot;
    private volatile int apiPort;
    private volatile long retryAfterEpochSecond;
    private volatile String lastCompletedIdentity = "";
    private volatile String haltedIdentity = "";
    private volatile String noProgressIdentity = "";
    private volatile int consecutiveNoProgress;
    private volatile AutomationStatus status = AutomationStatus.idle();

    public PlayerProviderAgentRunner(ProviderConfigStore store, DeepSeekToolLoopClient agentClient,
                                     Consumer<AutomationStatus> statusListener) {
        this(store, agentClient, agentClient, statusListener, ignored -> { });
    }

    public PlayerProviderAgentRunner(ProviderConfigStore store, DeepSeekToolLoopClient agentClient,
                                     Consumer<AutomationStatus> statusListener,
                                     Consumer<AgentActivityEvent> activityListener) {
        this(store, agentClient, agentClient, statusListener, activityListener);
    }

    PlayerProviderAgentRunner(ProviderConfigStore store, ProviderAgentClient legacyClient,
                              ProviderAgentClient harnessClient,
                              Consumer<AutomationStatus> statusListener,
                              Consumer<AgentActivityEvent> activityListener) {
        this.store = Objects.requireNonNull(store, "store");
        this.legacyClient = Objects.requireNonNull(legacyClient, "legacyClient");
        this.harnessClient = Objects.requireNonNull(harnessClient, "harnessClient");
        this.statusListener = Objects.requireNonNull(statusListener, "statusListener");
        this.activityListener = Objects.requireNonNull(activityListener, "activityListener");
    }

    public synchronized void start(Path serverDirectory, int apiPort, long worldSeed) {
        start(serverDirectory, serverDirectory.resolve("realm_debug"), apiPort, worldSeed);
    }

    public synchronized void start(Path serverDirectory, Path debugRoot, int apiPort, long worldSeed) {
        start(serverDirectory, debugRoot, apiPort, worldSeed, PlanningExtensionRegistry.empty());
    }

    public synchronized void start(Path serverDirectory, Path debugRoot, int apiPort, long worldSeed, PlanningExtensionRegistry extensions) {
        start(serverDirectory, debugRoot, apiPort, worldSeed, extensions, null);
    }

    public synchronized void start(Path serverDirectory, Path debugRoot, int apiPort, long worldSeed, PlanningExtensionRegistry extensions, PlanningSessionService shared) {
        close();
        this.serverDirectory = serverDirectory.toAbsolutePath().normalize();
        this.debugRoot = debugRoot.toAbsolutePath().normalize();
        this.apiPort = apiPort;
        this.ownsPlanning = shared == null;
        this.planning = shared == null ? new PlanningSessionService(this.serverDirectory, this.debugRoot, apiPort, worldSeed, extensions) : shared;
        this.discovery = planning.discovery();
        legacyClient.start(this.serverDirectory, this.debugRoot, apiPort);
        if (harnessClient != legacyClient) harnessClient.start(this.serverDirectory, this.debugRoot, apiPort);
        this.lastCompletedIdentity = "";
        this.haltedIdentity = "";
        resetNoProgress();
        this.retryAfterEpochSecond = 0;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Geomantia-Provider-Agent");
            thread.setDaemon(true);
            return thread;
        });
        update(new AutomationStatus("idle", "", "", "", "", Instant.now().toString()));
        activity("system", "自动规划器已启动");
        scheduler.scheduleWithFixedDelay(this::safeTick, 2, POLL_SECONDS, TimeUnit.SECONDS);
    }

    public AutomationStatus status() {
        return status;
    }

    public void retryNow() {
        lastCompletedIdentity = "";
        haltedIdentity = "";
        resetNoProgress();
        retryAfterEpochSecond = 0;
        ScheduledExecutorService current = scheduler;
        if (current != null && !current.isShutdown()) current.execute(this::safeTick);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception exception) {
            retryAfterEpochSecond = Instant.now().getEpochSecond() + ERROR_BACKOFF_SECONDS;
            update(new AutomationStatus("error", "PROVIDER_AGENT_RUNNER_FAILED", "", "", "",
                    Instant.now().toString()));
            LOGGER.warn("Geomantia Provider agent turn failed without exposing credentials: {}",
                    exception.getClass().getSimpleName());
        }
    }

    private void tick() throws IOException {
        ProviderPlanningDiscovery currentDiscovery = discovery;
        if (currentDiscovery == null || serverDirectory == null || debugRoot == null || turnRunning.get()) return;
        PlayerProviderConfig config = store.load();
        if (!config.enabled()) {
            updateIfChanged(new AutomationStatus("disabled", "", "", "", "", Instant.now().toString()));
            return;
        }
        Credentials credentials = store.credentials(config);
        if (Instant.now().getEpochSecond() < retryAfterEpochSecond) return;
        ProviderPlanningDiscovery.PlanningStep run = currentDiscovery.nextStep();
        if (run.stage().actionable() && !hostOnly(run) && !credentials.present()) {
            updateIfChanged(new AutomationStatus("missing_key", "PROVIDER_API_KEY_MISSING",
                    run.runId(), run.citySeedId(), run.nextAction(), Instant.now().toString()));
            return;
        }
        if (!run.stage().actionable()) {
            boolean programBlocked = run.state().has("cityDesignQueue") && "blocked_by_program".equals(
                    run.state().getAsJsonObject("cityDesignQueue").get("status").getAsString());
            String state = programBlocked ? "error" : run.stage() == ProviderPlanningDiscovery.Stage.COMPLETE ? "completed" : "waiting";
            updateIfChanged(new AutomationStatus(state, programBlocked ? programBlockMessage(run.state().getAsJsonObject("cityDesignQueue")) : "", run.runId(), run.citySeedId(),
                    run.nextAction(), Instant.now().toString()));
            return;
        }
        if (run.semanticIdentity().equals(lastCompletedIdentity)
                || run.semanticIdentity().equals(haltedIdentity)) return;
        PlanningSessionService sessions = planning;
        if (!hostOnly(run) && sessions.requiredRole(run) != PlanningRole.FLASH) {
            updateIfChanged(new AutomationStatus("waiting", "PLANNING_WAITING_FOR_ADVANCED", run.runId(),run.citySeedId(),run.nextAction(),Instant.now().toString()));
            return;
        }
        String token;
        try { token = sessions.acquireEmbedded(PlanningRole.FLASH); }
        catch (Exception occupied) { return; }
        if (!turnRunning.compareAndSet(false, true)) { sessions.release(token); return; }
        update(new AutomationStatus("running", "", run.runId(), run.citySeedId(), run.nextAction(),
                Instant.now().toString()));
        activity("stage", "开始 " + run.stage().name() + formatScope(run)
                + "，下一步 " + run.nextAction());
        try (var ownership = sessions.enter(token)) {
            // Another executor may have advanced while this tick was acquiring ownership.
            if (!run.semanticIdentity().equals(currentDiscovery.nextStep().semanticIdentity())) return;
            ProviderPlanningToolGateway gateway = sessions.gateway(run, token);
            ProviderAgentClient client = PlayerProviderConfig.HARNESS.equals(config.agentRuntime())
                    ? harnessClient : legacyClient;
            PlanningLoopResult result;
            PlanningTurnControl turnControl = null;
            if (hostOnly(run)) {
                // These transitions contain no design choice and must not cost a model turn.
                var output = gateway.executeHost(run.nextAction(), PlanningStepPolicy.hostArguments(run, debugRoot));
                String failure = PlanningTurnControl.failure(PlanningTurnControl.payload(output));
                if (!failure.isBlank()) throw new IOException(failure);
                result = new PlanningLoopResult(true, "completed", "", 0, "");
            } else {
                var prepared = sessions.prepare(run, gateway);
                if (prepared == null) {
                    acceptSuccessfulTurnOnlyAfterStateProgress(currentDiscovery, run);
                    return;
                }
                var designState = prepared.state();
                var designImages = prepared.images();
                var designTools = prepared.tools();
                turnControl = prepared.control();
                result = client.run(config, credentials,
                        designSessionId(debugRoot, run, designState), designState, designImages, designTools, turnControl,
                        this::recordLoopActivity);
            }
            if (PlanningSessionService.isCityDesign(run) && turnControl != null && turnControl.permitsDesignContinuation()
                    && run.semanticIdentity().equals(currentDiscovery.nextStep().semanticIdentity())) {
                resetNoProgress();
                retryAfterEpochSecond = Instant.now().getEpochSecond() + NO_PROGRESS_BACKOFF_SECONDS;
                update(new AutomationStatus("waiting", "", run.runId(), run.citySeedId(), run.nextAction(), Instant.now().toString()));
                activity("progress", "D4 修订已记录；保留当前方案与格式修正机会，继续设计");
            } else if (result.success()) {
                acceptSuccessfulTurnOnlyAfterStateProgress(currentDiscovery, run);
            } else {
                recordFailedTurn(run, result.errorCode());
            }
        } catch (Exception exception) {
            recordFailedTurn(run, "PLANNING_HOST_BLOCKED: " + (exception.getMessage() == null
                    ? "PROVIDER_HOST_STEP_FAILED" : exception.getMessage()));
        } finally {
            sessions.release(token);
            turnRunning.set(false);
        }
    }

    static String programBlockMessage(JsonObject queue) { return PlanningSessionService.programBlockMessage(queue); }

    void recordFailedTurn(ProviderPlanningDiscovery.PlanningStep run, String errorCode) {
        int attempts = recordNoProgress(run.semanticIdentity());
        boolean blocked = errorCode.startsWith("PLANNING_HOST_BLOCKED") || errorCode.startsWith("PLANNING_REPEATED_REJECTION") || errorCode.startsWith("PLANNING_FORMAT_RETRIES_EXHAUSTED");
        boolean exhausted = attempts >= MAX_CONSECUTIVE_NO_PROGRESS || blocked;
        if (exhausted) haltedIdentity = run.semanticIdentity();
        retryAfterEpochSecond = Instant.now().getEpochSecond() + ERROR_BACKOFF_SECONDS;
        update(new AutomationStatus(exhausted ? "error" : "waiting", errorCode,
                run.runId(), run.citySeedId(), run.nextAction(), Instant.now().toString()));
        activity(exhausted ? "error" : "waiting", exhausted
                ? (blocked ? "自动规划已停止，需先处理阻塞后再重试：" : "Agent loop 连续失败（" + attempts
                        + "/" + MAX_CONSECUTIVE_NO_PROGRESS + "），自动规划已停止；处理后可重试：") + errorCode
                : "Agent loop 失败（" + attempts + "/" + MAX_CONSECUTIVE_NO_PROGRESS
                        + "），60 秒后重试：" + errorCode);
    }

    private void acceptSuccessfulTurnOnlyAfterStateProgress(
            ProviderPlanningDiscovery currentDiscovery,
            ProviderPlanningDiscovery.PlanningStep before) throws IOException {
        ProviderPlanningDiscovery.PlanningStep after = currentDiscovery.nextStep();
        boolean progressed = !after.stage().actionable()
                || !after.semanticIdentity().equals(before.semanticIdentity());
        if (progressed) {
            lastCompletedIdentity = before.semanticIdentity();
            haltedIdentity = "";
            resetNoProgress();
            retryAfterEpochSecond = 0;
            String state = after.stage() == ProviderPlanningDiscovery.Stage.COMPLETE ? "completed" : "waiting";
            update(new AutomationStatus(state, "", after.runId(), after.citySeedId(),
                    after.nextAction(), Instant.now().toString()));
            activity("progress", "正式状态已推进至 " + after.stage().name()
                    + formatScope(after) + (after.nextAction().isBlank() ? "" : "，下一步 " + after.nextAction()));
            return;
        }

        int attempts = recordNoProgress(before.semanticIdentity());
        boolean exhausted = attempts >= MAX_CONSECUTIVE_NO_PROGRESS;
        if (exhausted) haltedIdentity = before.semanticIdentity();
        retryAfterEpochSecond = Instant.now().getEpochSecond()
                + (exhausted ? ERROR_BACKOFF_SECONDS : NO_PROGRESS_BACKOFF_SECONDS);
        update(new AutomationStatus(exhausted ? "error" : "waiting",
                exhausted ? "PROVIDER_AGENT_NO_PROGRESS" : "PROVIDER_AGENT_NO_PROGRESS_RETRYING",
                before.runId(), before.citySeedId(), before.nextAction(), Instant.now().toString()));
        activity(exhausted ? "error" : "waiting", exhausted
                ? "连续 3 次未产生正式阶段产物，自动规划已停止；重新保存 Provider 配置可重试"
                : "本轮没有推进正式状态，30 秒后重试（" + attempts + "/3）");
    }

    private void recordLoopActivity(AgentActivityEvent event) {
        if (event != null) activityListener.accept(event);
    }

    private void activity(String kind, String message) {
        activityListener.accept(new AgentActivityEvent(Instant.now().toString(), kind, message));
    }

    private static String formatScope(ProviderPlanningDiscovery.PlanningStep step) {
        if (!step.citySeedId().isBlank()) return " / 城市 " + step.citySeedId();
        if (!step.realmId().isBlank()) return " / 国度 " + step.realmId();
        return "";
    }

    static String sessionId(Path debugRoot, ProviderPlanningDiscovery.PlanningStep step) {
        String world = Integer.toUnsignedString(debugRoot.toAbsolutePath().normalize().toString().hashCode(), 36);
        String scope = !step.citySeedId().isBlank() ? step.citySeedId()
                : !step.realmId().isBlank() ? step.realmId() : "world";
        return sanitize("geomantia-" + world + "-" + step.runId() + "-" + scope);
    }

    static String designSessionId(Path debugRoot, ProviderPlanningDiscovery.PlanningStep step, JsonObject state) {
        if (step.stage() == ProviderPlanningDiscovery.Stage.EXTENSION)
            return "geomantia-extension-" + java.util.UUID.nameUUIDFromBytes((debugRoot.toAbsolutePath().normalize()
                    + "|" + step.runId() + "|" + step.citySeedId() + "|" + state.get("taskRevision")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!state.has("preparedBlueprintContext")) return sessionId(debugRoot, step);
        String identity = debugRoot.toAbsolutePath().normalize() + "|" + step.runId() + "|" + step.citySeedId()
                + "|" + state.get("contextId").getAsString() + "|"
                + (state.has("failureCount") ? state.get("failureCount").getAsInt() : 0);
        // Full current evidence is supplied by the host. Keep old sessions as audit records, not live design baggage.
        return "geomantia-design-" + java.util.UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sanitize(String value) {
        String result = value.replaceAll("[^A-Za-z0-9._-]", "-");
        return result.length() <= 200 ? result : result.substring(0, 200);
    }

    private int recordNoProgress(String identity) {
        if (!identity.equals(noProgressIdentity)) {
            noProgressIdentity = identity;
            consecutiveNoProgress = 0;
        }
        return ++consecutiveNoProgress;
    }

    private void resetNoProgress() {
        noProgressIdentity = "";
        consecutiveNoProgress = 0;
    }

    static List<String> toolsFor(ProviderPlanningDiscovery.Stage stage) { return PlanningStepPolicy.toolsFor(stage); }
    static boolean hostOnly(ProviderPlanningDiscovery.PlanningStep step) { return PlanningStepPolicy.hostOnly(step); }

    private void updateIfChanged(AutomationStatus value) {
        AutomationStatus current = status;
        if (!current.state().equals(value.state()) || !current.message().equals(value.message())
                || !current.runId().equals(value.runId()) || !current.citySeedId().equals(value.citySeedId())
                || !current.activeTool().equals(value.activeTool())) update(value);
    }

    private void update(AutomationStatus value) {
        status = value;
        statusListener.accept(value);
    }

    @Override
    public synchronized void close() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        discovery = null;
        PlanningSessionService previousPlanning = planning;
        planning = null;
        if (previousPlanning != null && ownsPlanning) previousPlanning.close();
        serverDirectory = null;
        debugRoot = null;
        turnRunning.set(false);
        if (current != null) current.shutdownNow();
        legacyClient.close();
        if (harnessClient != legacyClient) harnessClient.close();
    }

    public record AutomationStatus(String state, String message, String runId, String citySeedId,
                                   String activeTool, String updatedAt) {
        public AutomationStatus {
            state = safe(state);
            message = safe(message);
            runId = safe(runId);
            citySeedId = safe(citySeedId);
            activeTool = safe(activeTool);
            updatedAt = safe(updatedAt);
        }

        static AutomationStatus idle() {
            return new AutomationStatus("idle", "", "", "", "", "");
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
