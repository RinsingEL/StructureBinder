package com.rinsing.geomantia.systems.provider.application;

public record ProviderSettingsSnapshot(String providerKind, boolean enabled, String baseUrl,
                                       String model, String apiProtocol, int timeoutSeconds, String agentRuntime,
                                       boolean hasApiKey,
                                       String apiKeySource, boolean editable,
                                       String connectionState, String message,
                                       String automationState, String automationMessage,
                                       String activeRunId, String activeCitySeedId, String activeTool) {
    public ProviderSettingsSnapshot {
        providerKind = safe(providerKind);
        baseUrl = safe(baseUrl);
        model = safe(model);
        apiProtocol = safe(apiProtocol);
        agentRuntime = safe(agentRuntime);
        timeoutSeconds = Math.max(5, Math.min(120, timeoutSeconds));
        apiKeySource = safe(apiKeySource);
        connectionState = safe(connectionState);
        message = safe(message);
        automationState = safe(automationState);
        automationMessage = safe(automationMessage);
        activeRunId = safe(activeRunId);
        activeCitySeedId = safe(activeCitySeedId);
        activeTool = safe(activeTool);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
