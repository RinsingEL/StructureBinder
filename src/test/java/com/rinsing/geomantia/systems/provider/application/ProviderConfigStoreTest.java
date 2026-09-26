package com.rinsing.geomantia.harness.systems.provider.application;
import com.rinsing.geomantia.systems.provider.application.*;

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
    @Test void independentRoleProfilesAndTakeoverFlagsDoNotShareKeys() throws Exception {
        var flash=new ProviderConfigStore(temporaryDirectory,PlanningRole.FLASH);
        var advanced=new ProviderConfigStore(temporaryDirectory,PlanningRole.ADVANCED);
        var f=new PlayerProviderConfig(PlayerProviderConfig.CUSTOM,true,"https://flash.example/v1","flash-model",PlayerProviderConfig.RESPONSES,20);
        var a=new PlayerProviderConfig(PlayerProviderConfig.CUSTOM,true,"https://advanced.example/v1","advanced-model",PlayerProviderConfig.RESPONSES,20);
        flash.save(f,"flash-test-secret",false);
        assertFalse(advanced.configured());assertEquals("",advanced.load().model());
        advanced.save(a,"advanced-test-secret",false);
        assertEquals("flash-test-secret",flash.credentials(f).apiKey());
        assertEquals("advanced-test-secret",advanced.credentials(a).apiKey());
        assertEquals(f,flash.load());assertEquals(a,advanced.load());
        var settings=com.rinsing.geomantia.platform.mcp.McpServerConfig.loadDirectory(temporaryDirectory);
        assertTrue(settings.embeddedAdvanced());assertTrue(settings.embeddedFlash());
        com.rinsing.geomantia.platform.mcp.McpServerConfig.setTakeover(temporaryDirectory,PlanningRole.FLASH,false);
        assertFalse(flash.load().enabled());assertTrue(advanced.load().enabled());
        assertFalse(Files.readString(temporaryDirectory.resolve("provider-advanced.json")).contains("advanced-test-secret"));
        advanced.save(a,"",true);
        assertTrue(Files.exists(temporaryDirectory.resolve("provider-secret.txt")));
        assertFalse(Files.exists(temporaryDirectory.resolve("provider-advanced-secret.txt")));
    }

    @Test
    void defaultsToDisabledDeepSeekVisionPreset() throws Exception {
        ProviderConfigStore store = new ProviderConfigStore(temporaryDirectory.resolve("geomantia"));

        PlayerProviderConfig config = store.load();

        assertEquals(PlayerProviderConfig.DEEPSEEK, config.providerKind());
        assertEquals(PlayerProviderConfig.DEEPSEEK_BASE_URL, config.baseUrl());
        assertEquals(PlayerProviderConfig.DEEPSEEK_VISION_MODEL, config.model());
        assertEquals(PlayerProviderConfig.CHAT_COMPLETIONS, config.apiProtocol());
        assertEquals(PlayerProviderConfig.HARNESS, config.agentRuntime());
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
        assertTrue(Files.readString(root.resolve("provider.json")).contains("harness"));
    }

    @Test
    void migratesHermesRuntimeWithoutChangingCustomEndpointOrSecret() throws Exception {
        Path root = temporaryDirectory.resolve("migrated");
        Files.createDirectories(root);
        Files.writeString(root.resolve("provider.json"), """
                {"providerKind":"custom","enabled":true,"baseUrl":"https://opencode.ai/zen/go/v1",
                 "model":"deepseek-v4.1-flash","apiProtocol":"responses","agentRuntime":"hermes"}
                """);
        Files.writeString(root.resolve("provider-secret.txt"), "migration-test-key");
        ProviderConfigStore store = new ProviderConfigStore(root);
        var config = store.load();
        assertEquals(PlayerProviderConfig.HARNESS, config.agentRuntime());
        assertEquals("https://opencode.ai/zen/go/v1", config.baseUrl());
        assertEquals("deepseek-v4.1-flash", config.model());
        assertEquals("migration-test-key", store.credentials(config).apiKey());
    }

    @Test
    void rejectsRemotePlainHttpCustomEndpoint() {
        PlayerProviderConfig config = new PlayerProviderConfig(PlayerProviderConfig.CUSTOM, true,
                "http://example.com/v1", "vision-model", 20);

        assertThrows(IllegalArgumentException.class, config::validated);
    }
}
