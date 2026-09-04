package com.rinsing.geomantia.systems.provider.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public record PlayerProviderConfig(String providerKind, boolean enabled, String baseUrl,
                                   String model, String apiProtocol, int timeoutSeconds,
                                   String agentRuntime) {
    public static final String DEEPSEEK = "deepseek";
    public static final String CUSTOM = "custom";
    public static final String RESPONSES = "responses";
    public static final String CHAT_COMPLETIONS = "chat_completions";
    public static final String HERMES = "hermes";
    public static final String LEGACY = "legacy";
    public static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    public static final String DEEPSEEK_VISION_MODEL = "deepseek-v4-flash-vision-exp";

    public PlayerProviderConfig {
        providerKind = normalizeKind(providerKind);
        baseUrl = safe(baseUrl);
        model = safe(model);
        apiProtocol = normalizeProtocol(apiProtocol);
        agentRuntime = normalizeRuntime(agentRuntime);
        timeoutSeconds = Math.max(5, Math.min(120, timeoutSeconds));
        if (DEEPSEEK.equals(providerKind)) {
            baseUrl = DEEPSEEK_BASE_URL;
            model = DEEPSEEK_VISION_MODEL;
            apiProtocol = RESPONSES;
        }
    }

    public PlayerProviderConfig(String providerKind, boolean enabled, String baseUrl,
                                String model, String apiProtocol, int timeoutSeconds) {
        this(providerKind, enabled, baseUrl, model, apiProtocol, timeoutSeconds, HERMES);
    }

    public PlayerProviderConfig(String providerKind, boolean enabled, String baseUrl,
                                String model, int timeoutSeconds) {
        this(providerKind, enabled, baseUrl, model, RESPONSES, timeoutSeconds, HERMES);
    }

    public static PlayerProviderConfig defaults() {
        return new PlayerProviderConfig(DEEPSEEK, false, DEEPSEEK_BASE_URL,
                DEEPSEEK_VISION_MODEL, RESPONSES, 20, HERMES);
    }

    public PlayerProviderConfig validated() {
        if (model.isBlank() || model.length() > 160) {
            throw new IllegalArgumentException("PROVIDER_MODEL_INVALID");
        }
        if (baseUrl.isBlank() || baseUrl.length() > 512) {
            throw new IllegalArgumentException("PROVIDER_BASE_URL_INVALID");
        }
        try {
            URI uri = new URI(baseUrl);
            String scheme = safe(uri.getScheme()).toLowerCase(Locale.ROOT);
            String host = safe(uri.getHost()).toLowerCase(Locale.ROOT);
            boolean localHttp = "http".equals(scheme)
                    && ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host));
            if (!("https".equals(scheme) || localHttp) || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("PROVIDER_BASE_URL_UNSAFE");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("PROVIDER_BASE_URL_INVALID", exception);
        }
        return this;
    }

    private static String normalizeKind(String value) {
        return CUSTOM.equalsIgnoreCase(safe(value)) ? CUSTOM : DEEPSEEK;
    }

    private static String normalizeProtocol(String value) {
        return CHAT_COMPLETIONS.equalsIgnoreCase(safe(value)) ? CHAT_COMPLETIONS : RESPONSES;
    }

    private static String normalizeRuntime(String value) {
        return LEGACY.equalsIgnoreCase(safe(value)) ? LEGACY : HERMES;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
