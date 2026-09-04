package com.rinsing.geomantia.systems.provider.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConfigStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsToDisabledDeepSeekVisionPreset() throws Exception {
        ProviderConfigStore store = new ProviderConfigStore(temporaryDirectory.resolve("geomantia"));

        PlayerProviderConfig config = store.load();

        assertEquals(PlayerProviderConfig.DEEPSEEK, config.providerKind());
        assertEquals(PlayerProviderConfig.DEEPSEEK_BASE_URL, config.baseUrl());
        assertEquals(PlayerProviderConfig.DEEPSEEK_VISION_MODEL, config.model());
        assertEquals(PlayerProviderConfig.RESPONSES, config.apiProtocol());
        assertEquals(PlayerProviderConfig.HERMES, config.agentRuntime());
        assertFalse(config.enabled());
    }

    @Test
    void storesSecretSeparatelyAndNeverWritesItIntoPublicConfig() throws Exception {
        Path root = temporaryDirectory.resolve("geomantia");
        ProviderConfigStore store = new ProviderConfigStore(root);
        PlayerProviderConfig config = new PlayerProviderConfig(PlayerProviderConfig.CUSTOM, true,
                "http://127.0.0.1:8123/v1", "vision-model",
                PlayerProviderConfig.CHAT_COMPLETIONS, 35);

        store.save(config, "secret-test-key", false);

        assertEquals(config, store.load());
        assertTrue(store.credentials(config).present());
        assertEquals("stored", store.credentials(config).source());
        assertFalse(Files.readString(root.resolve("provider.json")).contains("secret-test-key"));
        assertTrue(Files.readString(root.resolve("provider-secret.txt")).contains("secret-test-key"));
        assertTrue(Files.readString(root.resolve("provider.json")).contains("chat_completions"));
        assertTrue(Files.readString(root.resolve("provider.json")).contains("hermes"));
    }

    @Test
    void rejectsRemotePlainHttpCustomEndpoint() {
        PlayerProviderConfig config = new PlayerProviderConfig(PlayerProviderConfig.CUSTOM, true,
                "http://example.com/v1", "vision-model", 20);

        assertThrows(IllegalArgumentException.class, config::validated);
    }
}
