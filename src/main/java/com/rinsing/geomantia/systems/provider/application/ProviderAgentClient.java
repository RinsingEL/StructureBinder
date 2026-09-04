package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.JsonObject;
import com.rinsing.geomantia.systems.provider.application.ProviderConfigStore.Credentials;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

interface ProviderAgentClient extends AutoCloseable {
    default void start(Path serverDirectory, Path debugRoot, int apiPort) {
    }

    DeepSeekToolLoopClient.LoopResult run(PlayerProviderConfig config, Credentials credentials,
                                          String sessionId, JsonObject initialState,
                                          List<Path> initialImages, List<String> allowedTools,
                                          DeepSeekToolLoopClient.ToolExecutor toolExecutor,
                                          Consumer<AgentActivityEvent> activityListener);

    @Override
    default void close() {
    }
}
