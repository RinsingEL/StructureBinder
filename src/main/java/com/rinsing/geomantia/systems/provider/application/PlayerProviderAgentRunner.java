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

    private final ProviderConfigStore store;
    private final DeepSeekToolLoopClient agentClient;
    private final Consumer<AutomationStatus> statusListener;
    private final AtomicBoolean turnRunning = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;
    private volatile ProviderPlanningDiscovery discovery;
    private volatile Path serverDirectory;
    private volatile int apiPort;
    private volatile long retryAfterEpochSecond;
    private volatile String lastCompletedIdentity = "";
    private volatile AutomationStatus status = AutomationStatus.idle();

    public PlayerProviderAgentRunner(ProviderConfigStore store, DeepSeekToolLoopClient agentClient,
                                     Consumer<AutomationStatus> statusListener) {
        this.store = Objects.requireNonNull(store, "store");
        this.agentClient = Objects.requireNonNull(agentClient, "agentClient");
        this.statusListener = Objects.requireNonNull(statusListener, "statusListener");
    }

    public synchronized void start(Path serverDirectory, int apiPort, long worldSeed) {
        close();
        this.serverDirectory = serverDirectory.toAbsolutePath().normalize();
        this.apiPort = apiPort;
        this.discovery = new ProviderPlanningDiscovery(this.serverDirectory.resolve("realm_debug"), worldSeed);
        this.lastCompletedIdentity = "";
        this.retryAfterEpochSecond = 0;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Geomantia-Provider-Agent");
            thread.setDaemon(true);
            return thread;
        });
        update(new AutomationStatus("idle", "", "", "", "", Instant.now().toString()));
        scheduler.scheduleWithFixedDelay(this::safeTick, 2, POLL_SECONDS, TimeUnit.SECONDS);
    }

    public AutomationStatus status() {
        return status;
    }

    public void retryNow() {
        lastCompletedIdentity = "";
        retryAfterEpochSecond = 0;
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
        if (discovery == null || serverDirectory == null || turnRunning.get()) return;
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
        ProviderPlanningDiscovery.PlanningStep run = discovery.nextStep();
        if (!run.stage().actionable()) {
            String state = run.stage() == ProviderPlanningDiscovery.Stage.COMPLETE ? "completed" : "waiting";
            updateIfChanged(new AutomationStatus(state, "", run.runId(), run.citySeedId(),
                    run.nextAction(), Instant.now().toString()));
            return;
        }
        if (run.semanticIdentity().equals(lastCompletedIdentity)) return;
        if (!turnRunning.compareAndSet(false, true)) return;
        update(new AutomationStatus("running", "", run.runId(), run.citySeedId(), run.nextAction(),
                Instant.now().toString()));
        try {
            ProviderPlanningToolGateway gateway = ProviderPlanningToolGateway.forStep(
                    apiPort, serverDirectory, run);
            DeepSeekToolLoopClient.LoopResult result = agentClient.run(config, credentials,
                    run.state(), run.initialImages(), toolsFor(run.stage()), gateway);
            if (result.success()) {
                lastCompletedIdentity = run.semanticIdentity();
                retryAfterEpochSecond = Instant.now().getEpochSecond() + NO_PROGRESS_BACKOFF_SECONDS;
                update(new AutomationStatus("waiting", "", run.runId(), run.citySeedId(),
                        run.nextAction(), Instant.now().toString()));
            } else {
                retryAfterEpochSecond = Instant.now().getEpochSecond() + ERROR_BACKOFF_SECONDS;
                update(new AutomationStatus("error", result.errorCode(), run.runId(), run.citySeedId(),
                        run.nextAction(), Instant.now().toString()));
            }
        } finally {
            turnRunning.set(false);
        }
    }

    private static List<String> toolsFor(ProviderPlanningDiscovery.Stage stage) {
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
                    "city_post_d4_auto_compile_status", "city_post_d4_auto_compile_retry");
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
        turnRunning.set(false);
        if (current != null) current.shutdownNow();
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
