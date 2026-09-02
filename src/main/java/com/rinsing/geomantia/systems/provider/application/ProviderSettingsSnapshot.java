package com.rinsing.geomantia.systems.provider.application;

public record ProviderSettingsSnapshot(String providerKind, boolean enabled, String baseUrl,
                                       String model, int timeoutSeconds, boolean hasApiKey,
                                       String apiKeySource, boolean editable,
                                       String connectionState, String message) {
    public ProviderSettingsSnapshot {
        providerKind = safe(providerKind);
        baseUrl = safe(baseUrl);
        model = safe(model);
        timeoutSeconds = Math.max(5, Math.min(120, timeoutSeconds));
        apiKeySource = safe(apiKeySource);
        connectionState = safe(connectionState);
        message = safe(message);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
