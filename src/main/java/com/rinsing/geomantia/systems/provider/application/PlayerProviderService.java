package com.rinsing.geomantia.systems.provider.application;

import com.rinsing.geomantia.systems.provider.application.ProviderConfigStore.Credentials;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerProviderService {
    private static final int MAX_ACTIVITY_EVENTS = 160;
    private static final PlayerProviderService INSTANCE = new PlayerProviderService(
            new ProviderConfigStore(FMLPaths.CONFIGDIR.get().resolve("geomantia")),
            new ProviderConnectionTester(), new MultimodalProviderClient());

    private final ProviderConfigStore store;
    private final ProviderConnectionTester tester;
    private final MultimodalProviderClient client;
    private final PlayerProviderAgentRunner agentRunner;
    private final ExecutorService executor;
    private final ArrayDeque<AgentActivityEvent> activityEvents = new ArrayDeque<>();
    private volatile String connectionState = "not_tested";
    private volatile String message = "";

    PlayerProviderService(ProviderConfigStore store, ProviderConnectionTester tester,
                          MultimodalProviderClient client) {
        this.store = store;
        this.tester = tester;
        this.client = client;
        this.agentRunner = new PlayerProviderAgentRunner(store, new DeepSeekToolLoopClient(),
                ignored -> { }, this::recordActivity);
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Geomantia-Player-Provider");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static PlayerProviderService instance() {
        return INSTANCE;
    }

    public void startAutomation(Path serverDirectory, int apiPort, long worldSeed) {
        agentRunner.start(serverDirectory, apiPort, worldSeed);
    }

    public void stopAutomation() {
        agentRunner.close();
    }

    public List<AgentActivityEvent> activityEvents() {
        synchronized (activityEvents) {
            return List.copyOf(new ArrayList<>(activityEvents));
        }
    }

    public ProviderSettingsSnapshot snapshot(boolean editable) {
        try {
            PlayerProviderConfig config = store.load();
            Credentials credentials = store.credentials(config);
            return snapshot(config, credentials, editable, connectionState, message);
        } catch (IOException | RuntimeException exception) {
            PlayerProviderConfig fallback = PlayerProviderConfig.defaults();
            return snapshot(fallback, new Credentials("", "none"), editable,
                    "error", "PROVIDER_CONFIG_READ_FAILED");
        }
    }

    public CompletableFuture<ProviderSettingsSnapshot> save(PlayerProviderConfig config,
                                                             String replacementApiKey,
                                                             boolean clearStoredApiKey,
                                                             boolean editable) {
        if (!editable) return CompletableFuture.completedFuture(deniedSnapshot());
        connectionState = "saving";
        message = "PROVIDER_SAVING";
        return CompletableFuture.supplyAsync(() -> {
            try {
                store.save(config, replacementApiKey, clearStoredApiKey);
                agentRunner.retryNow();
                connectionState = "saved";
                message = "PROVIDER_SETTINGS_SAVED";
                return snapshot(true);
            } catch (IOException | RuntimeException exception) {
                connectionState = "error";
                message = exception instanceof IllegalArgumentException
                        ? exception.getMessage() : "PROVIDER_CONFIG_WRITE_FAILED";
                return snapshot(true);
            }
        }, executor);
    }

    public CompletableFuture<ProviderSettingsSnapshot> test(boolean editable) {
        if (!editable) return CompletableFuture.completedFuture(deniedSnapshot());
        connectionState = "testing";
        message = "PROVIDER_TESTING";
        return CompletableFuture.supplyAsync(() -> {
            try {
                PlayerProviderConfig config = store.load();
                Credentials credentials = store.credentials(config);
                ProviderConnectionTester.TestResult result = tester.test(config, credentials);
                connectionState = result.state();
                message = result.message();
                return snapshot(config, credentials, true, result.state(), result.message());
            } catch (IOException | RuntimeException exception) {
                connectionState = "error";
                message = "PROVIDER_CONNECTION_FAILED";
                return snapshot(true);
            }
        }, executor);
    }

    public CompletableFuture<MultimodalProviderClient.InvocationResult> analyzeImage(
            Path imagePath, String mimeType, String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                PlayerProviderConfig config = store.load();
                Credentials credentials = store.credentials(config);
                return client.analyze(config, credentials, Files.readAllBytes(imagePath), mimeType, prompt);
            } catch (IOException | RuntimeException exception) {
                return new MultimodalProviderClient.InvocationResult(false,
                        "PROVIDER_IMAGE_READ_FAILED", "");
            }
        }, executor);
    }

    private ProviderSettingsSnapshot deniedSnapshot() {
        ProviderSettingsSnapshot value = snapshot(false);
        return new ProviderSettingsSnapshot(value.providerKind(), value.enabled(), value.baseUrl(),
                value.model(), value.timeoutSeconds(), value.hasApiKey(), value.apiKeySource(),
                false, "forbidden", "PROVIDER_ADMIN_REQUIRED", value.automationState(),
                value.automationMessage(), value.activeRunId(), value.activeCitySeedId(), value.activeTool());
    }

    private ProviderSettingsSnapshot snapshot(PlayerProviderConfig config, Credentials credentials,
                                              boolean editable, String state, String message) {
        PlayerProviderAgentRunner.AutomationStatus automation = agentRunner.status();
        return new ProviderSettingsSnapshot(config.providerKind(), config.enabled(), config.baseUrl(),
                config.model(), config.timeoutSeconds(), credentials.present(), credentials.source(),
                editable, state, message, automation.state(), automation.message(), automation.runId(),
                automation.citySeedId(), automation.activeTool());
    }

    private void recordActivity(AgentActivityEvent event) {
        if (event == null || event.message().isBlank()) return;
        AgentActivityEvent value = event.occurredAt().isBlank()
                ? new AgentActivityEvent(Instant.now().toString(), event.kind(), event.message()) : event;
        synchronized (activityEvents) {
            activityEvents.addLast(value);
            while (activityEvents.size() > MAX_ACTIVITY_EVENTS) activityEvents.removeFirst();
        }
    }
}
