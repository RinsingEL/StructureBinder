package com.rinsing.geomantia.systems.provider.application;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.provider.application.ProviderConfigStore.Credentials;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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
    private volatile ProviderRunDiscovery discovery;
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
        this.discovery = new ProviderRunDiscovery(this.serverDirectory.resolve("realm_debug"), worldSeed);
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
        Optional<ProviderRunDiscovery.ActiveRun> active = discovery.newestActionableRun();
        if (active.isEmpty()) {
            updateIfChanged(new AutomationStatus("idle", "", "", "", "", Instant.now().toString()));
            return;
        }
        ProviderRunDiscovery.ActiveRun run = active.get();
        if (run.semanticIdentity().equals(lastCompletedIdentity)) return;
        if (!turnRunning.compareAndSet(false, true)) return;
        update(new AutomationStatus("running", "", run.runId(), run.citySeedId(), run.nextAction(),
                Instant.now().toString()));
        try {
            ProviderPlanningToolGateway gateway = new ProviderPlanningToolGateway(
                    apiPort, serverDirectory, run.runId(), run.citySeedId());
            DeepSeekToolLoopClient.LoopResult result = agentClient.run(config, credentials,
                    run.queueState(), ProviderPlanningToolGateway.allowedTools(), gateway);
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
