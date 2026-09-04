package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

public final class ProviderConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ENV_GENERIC = "GEOMANTIA_PROVIDER_API_KEY";
    private static final String ENV_DEEPSEEK = "DEEPSEEK_API_KEY";

    private final Path configPath;
    private final Path secretPath;

    public ProviderConfigStore(Path geomantiaConfigDirectory) {
        Path root = geomantiaConfigDirectory.toAbsolutePath().normalize();
        this.configPath = root.resolve("provider.json");
        this.secretPath = root.resolve("provider-secret.txt");
    }

    public synchronized PlayerProviderConfig load() throws IOException {
        if (!Files.isRegularFile(configPath)) return PlayerProviderConfig.defaults();
        JsonObject json = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8))
                .getAsJsonObject();
        return new PlayerProviderConfig(string(json, "providerKind", PlayerProviderConfig.DEEPSEEK),
                bool(json, "enabled", false), string(json, "baseUrl", PlayerProviderConfig.DEEPSEEK_BASE_URL),
                string(json, "model", PlayerProviderConfig.DEEPSEEK_VISION_MODEL),
                string(json, "apiProtocol", PlayerProviderConfig.RESPONSES),
                integer(json, "timeoutSeconds", 20),
                string(json, "agentRuntime", PlayerProviderConfig.HERMES)).validated();
    }

    public synchronized Credentials credentials(PlayerProviderConfig config) throws IOException {
        if (Files.isRegularFile(secretPath)) {
            String stored = Files.readString(secretPath, StandardCharsets.UTF_8).trim();
            if (!stored.isBlank()) return new Credentials(stored, "stored");
        }
        String generic = System.getenv(ENV_GENERIC);
        if (generic != null && !generic.isBlank()) return new Credentials(generic.trim(), "environment");
        if (PlayerProviderConfig.DEEPSEEK.equals(config.providerKind())) {
            String deepSeek = System.getenv(ENV_DEEPSEEK);
            if (deepSeek != null && !deepSeek.isBlank()) {
                return new Credentials(deepSeek.trim(), "environment");
            }
        }
        return new Credentials("", "none");
    }

    public synchronized void save(PlayerProviderConfig config, String replacementApiKey,
                                  boolean clearStoredApiKey) throws IOException {
        PlayerProviderConfig value = config.validated();
        Files.createDirectories(configPath.getParent());
        JsonObject json = new JsonObject();
        json.addProperty("schema", "geomantia_player_provider.v0.3");
        json.addProperty("providerKind", value.providerKind());
        json.addProperty("enabled", value.enabled());
        json.addProperty("baseUrl", value.baseUrl());
        json.addProperty("model", value.model());
        json.addProperty("apiProtocol", value.apiProtocol());
        json.addProperty("timeoutSeconds", value.timeoutSeconds());
        json.addProperty("agentRuntime", value.agentRuntime());
        atomicWrite(configPath, GSON.toJson(json));

        if (clearStoredApiKey) {
            Files.deleteIfExists(secretPath);
        } else if (replacementApiKey != null && !replacementApiKey.isBlank()) {
            if (replacementApiKey.length() > 4096) {
                throw new IllegalArgumentException("PROVIDER_API_KEY_TOO_LONG");
            }
            atomicWrite(secretPath, replacementApiKey.trim() + System.lineSeparator());
            restrictSecretPermissions();
        }
    }

    private void restrictSecretPermissions() {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(secretPath, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows ACLs and some filesystems do not expose POSIX permissions.
        }
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;
    }

    public record Credentials(String apiKey, String source) {
        public Credentials {
            apiKey = apiKey == null ? "" : apiKey;
            source = source == null ? "none" : source;
        }

        public boolean present() {
            return !apiKey.isBlank();
        }
    }
}
