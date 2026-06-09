package com.user.terra_script.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class AiProviderConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("terra_script_ai.json");
    private static volatile Root cached;

    private AiProviderConfig() {}

    public static synchronized Root load() {
        try {
            ensureExists();
            Root root = GSON.fromJson(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8), Root.class);
            if (root == null) root = defaultConfig();
            normalize(root);
            applyEnvOverrides(root);
            cached = root;
            return root;
        } catch (Exception e) {
            Root fallback = defaultConfig();
            applyEnvOverrides(fallback);
            cached = fallback;
            return fallback;
        }
    }

    public static Root get() {
        Root current = cached;
        return current != null ? current : load();
    }

    public static synchronized JsonSafeSummary describe() {
        Root root = get();
        JsonSafeSummary summary = new JsonSafeSummary();
        summary.enabled = root.enabled;
        summary.default_provider = root.default_provider;
        for (Map.Entry<String, Provider> entry : root.providers.entrySet()) {
            Provider provider = entry.getValue();
            ProviderSummary item = new ProviderSummary();
            item.id = entry.getKey();
            item.baseUrl = provider.baseUrl;
            item.model = provider.model;
            item.timeoutSeconds = provider.timeoutSeconds;
            item.apiKeyConfigured = provider.apiKey != null && !provider.apiKey.isBlank() && !"YOUR_API_KEY_HERE".equals(provider.apiKey);
            summary.providers.put(item.id, item);
        }
        return summary;
    }

    private static void ensureExists() throws Exception {
        if (Files.exists(CONFIG_PATH)) return;
        Files.createDirectories(CONFIG_PATH.getParent());
        Files.writeString(CONFIG_PATH, GSON.toJson(defaultConfig()), StandardCharsets.UTF_8);
    }

    private static Root defaultConfig() {
        Root root = new Root();
        Provider openai = new Provider();
        openai.baseUrl = "https://api.openai.com/v1";
        openai.model = "gpt-4.1";
        root.providers.put("openai", openai);

        Provider deepseek = new Provider();
        deepseek.baseUrl = "https://api.deepseek.com";
        deepseek.model = "deepseek-chat";
        root.providers.put("deepseek", deepseek);
        return root;
    }

    private static void normalize(Root root) {
        if (root.providers == null) root.providers = new LinkedHashMap<>();
        if (root.default_provider == null || root.default_provider.isBlank()) root.default_provider = "openai";
        for (Provider provider : root.providers.values()) {
            if (provider == null) continue;
            if (provider.baseUrl == null) provider.baseUrl = "";
            if (provider.model == null) provider.model = "";
            if (provider.apiKey == null) provider.apiKey = "";
            if (provider.timeoutSeconds <= 0) provider.timeoutSeconds = 90;
            if (provider.extraHeaders == null) provider.extraHeaders = new LinkedHashMap<>();
        }
    }

    private static void applyEnvOverrides(Root root) {
        for (Map.Entry<String, Provider> entry : root.providers.entrySet()) {
            String providerId = entry.getKey();
            Provider provider = entry.getValue();
            if (provider == null) continue;
            String prefix = providerId == null ? "" : providerId.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            String envKey = System.getenv(prefix + "_API_KEY");
            if (envKey != null && !envKey.isBlank()) {
                provider.apiKey = envKey.trim();
            }
        }
    }

    public static final class Root {
        public boolean enabled = true;
        public String default_provider = "openai";
        public Map<String, Provider> providers = new LinkedHashMap<>();
    }

    public static final class Provider {
        public String baseUrl = "";
        public String model = "";
        public String apiKey = "YOUR_API_KEY_HERE";
        public int timeoutSeconds = 90;
        public Map<String, String> extraHeaders = new LinkedHashMap<>();
    }

    public static final class JsonSafeSummary {
        public boolean enabled;
        public String default_provider;
        public Map<String, ProviderSummary> providers = new LinkedHashMap<>();
    }

    public static final class ProviderSummary {
        public String id;
        public String baseUrl;
        public String model;
        public int timeoutSeconds;
        public boolean apiKeyConfigured;
    }
}
