package com.rinsing.geomantia.systems.provider.application;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.provider.application.ProviderConfigStore.Credentials;
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
    private final ProviderAgentClient hermesClient;
    private final Consumer<AutomationStatus> statusListener;
    private final Consumer<AgentActivityEvent> activityListener;
    private final AtomicBoolean turnRunning = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;
    private volatile ProviderPlanningDiscovery discovery;
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
                              ProviderAgentClient hermesClient,
                              Consumer<AutomationStatus> statusListener,
                              Consumer<AgentActivityEvent> activityListener) {
        this.store = Objects.requireNonNull(store, "store");
        this.legacyClient = Objects.requireNonNull(legacyClient, "legacyClient");
        this.hermesClient = Objects.requireNonNull(hermesClient, "hermesClient");
        this.statusListener = Objects.requireNonNull(statusListener, "statusListener");
        this.activityListener = Objects.requireNonNull(activityListener, "activityListener");
    }

    public synchronized void start(Path serverDirectory, int apiPort, long worldSeed) {
        start(serverDirectory, serverDirectory.resolve("realm_debug"), apiPort, worldSeed);
    }

    public synchronized void start(Path serverDirectory, Path debugRoot, int apiPort, long worldSeed) {
        close();
        this.serverDirectory = serverDirectory.toAbsolutePath().normalize();
        this.debugRoot = debugRoot.toAbsolutePath().normalize();
        this.apiPort = apiPort;
        this.discovery = new ProviderPlanningDiscovery(this.debugRoot, worldSeed);
        legacyClient.start(this.serverDirectory, this.debugRoot, apiPort);
        if (hermesClient != legacyClient) hermesClient.start(this.serverDirectory, this.debugRoot, apiPort);
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
        if (!credentials.present()) {
            updateIfChanged(new AutomationStatus("missing_key", "PROVIDER_API_KEY_MISSING",
                    "", "", "", Instant.now().toString()));
            return;
        }
        if (Instant.now().getEpochSecond() < retryAfterEpochSecond) return;
        ProviderPlanningDiscovery.PlanningStep run = currentDiscovery.nextStep();
        if (!run.stage().actionable()) {
            String state = run.stage() == ProviderPlanningDiscovery.Stage.COMPLETE ? "completed" : "waiting";
            updateIfChanged(new AutomationStatus(state, "", run.runId(), run.citySeedId(),
                    run.nextAction(), Instant.now().toString()));
            return;
        }
        if (run.semanticIdentity().equals(lastCompletedIdentity)
                || run.semanticIdentity().equals(haltedIdentity)) return;
        if (!turnRunning.compareAndSet(false, true)) return;
        update(new AutomationStatus("running", "", run.runId(), run.citySeedId(), run.nextAction(),
                Instant.now().toString()));
        activity("stage", "开始 " + run.stage().name() + formatScope(run)
                + "，下一步 " + run.nextAction());
        try {
            ProviderPlanningToolGateway gateway = ProviderPlanningToolGateway.forStep(
                    apiPort, serverDirectory, debugRoot, run);
            ProviderAgentClient client = PlayerProviderConfig.HERMES.equals(config.agentRuntime())
                    ? hermesClient : legacyClient;
            DeepSeekToolLoopClient.LoopResult result = client.run(config, credentials,
                    sessionId(debugRoot, run), run.state(), run.initialImages(), toolsFor(run.stage()), gateway,
                    this::recordLoopActivity);
            if (result.success()) {
                acceptSuccessfulTurnOnlyAfterStateProgress(currentDiscovery, run);
            } else {
                recordFailedTurn(run, result.errorCode());
            }
        } finally {
            turnRunning.set(false);
        }
    }

    void recordFailedTurn(ProviderPlanningDiscovery.PlanningStep run, String errorCode) {
        int attempts = recordNoProgress(run.semanticIdentity());
        boolean exhausted = attempts >= MAX_CONSECUTIVE_NO_PROGRESS;
        if (exhausted) haltedIdentity = run.semanticIdentity();
        retryAfterEpochSecond = Instant.now().getEpochSecond() + ERROR_BACKOFF_SECONDS;
        update(new AutomationStatus(exhausted ? "error" : "waiting", errorCode,
                run.runId(), run.citySeedId(), run.nextAction(), Instant.now().toString()));
        activity(exhausted ? "error" : "waiting", exhausted
                ? "Agent loop 连续失败（" + attempts + "/" + MAX_CONSECUTIVE_NO_PROGRESS
                        + "），自动规划已停止；重新保存 Provider 配置可重试：" + errorCode
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

    static List<String> toolsFor(ProviderPlanningDiscovery.Stage stage) {
        return switch (stage) {
            case W -> List.of("realm_w_refresh");
            case T1 -> List.of("realm_t1_prepare");
            case T2 -> List.of("patch_explorer_open", "patch_explorer_show_candidates",
                    "patch_explorer_select_candidate", "realm_t2_select_coordinate");
            case T3 -> List.of("realm_t3_expand");
            case T4 -> List.of("realm_t4_patch_planning_create", "patch_explorer_open",
                    "patch_explorer_show_candidates", "patch_explorer_select_candidate",
                    "realm_t4_patch_planning_select_capital", "realm_t4_patch_planning_add_city",
                    "realm_t4_patch_planning_finalize");
            case QUEUE_REFRESH -> List.of("city_design_queue_refresh");
            case CITY -> List.of("city_design_queue_status", "city_plan_d3", "city_review_d3_site",
                    "patch_explorer_open", "patch_explorer_show_candidates",
                    "city_prepare_d4_blueprint_context", "city_submit_d4_blueprint",
                    "city_post_d4_auto_compile_retry");
            case WAITING, COMPLETE -> List.of();
        };
    }

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
        serverDirectory = null;
        debugRoot = null;
        turnRunning.set(false);
        if (current != null) current.shutdownNow();
        legacyClient.close();
        if (hermesClient != legacyClient) hermesClient.close();
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
