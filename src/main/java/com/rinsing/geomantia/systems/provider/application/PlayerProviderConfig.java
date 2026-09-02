package com.rinsing.geomantia.systems.provider.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public record PlayerProviderConfig(String providerKind, boolean enabled, String baseUrl,
                                   String model, int timeoutSeconds) {
    public static final String DEEPSEEK = "deepseek";
    public static final String CUSTOM = "custom";
    public static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    public static final String DEEPSEEK_VISION_MODEL = "deepseek-v4-flash-vision-exp";

    public PlayerProviderConfig {
        providerKind = normalizeKind(providerKind);
        baseUrl = safe(baseUrl);
        model = safe(model);
        timeoutSeconds = Math.max(5, Math.min(120, timeoutSeconds));
        if (DEEPSEEK.equals(providerKind)) {
            baseUrl = DEEPSEEK_BASE_URL;
            model = DEEPSEEK_VISION_MODEL;
        }
    }

    public static PlayerProviderConfig defaults() {
        return new PlayerProviderConfig(DEEPSEEK, false, DEEPSEEK_BASE_URL,
                DEEPSEEK_VISION_MODEL, 20);
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

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
