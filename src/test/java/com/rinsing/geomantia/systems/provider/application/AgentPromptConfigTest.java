package com.rinsing.geomantia.systems.provider.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class AgentPromptConfigTest {
    @TempDir Path config;

    @Test void initializesAllFilesAndPreservesEditsOnRestart() throws Exception {
        AgentPromptConfig.ensureDefaults(config);
        for (String name : AgentPromptConfig.FILES)
            assertFalse(AgentPromptConfig.read(config, name).isBlank(), name);
        Path file = config.resolve("geomantia/prompts/city/d4_v2/integration.md");
        Files.writeString(file, "先看图，再做有用途的区际连接。");
        AgentPromptConfig.ensureDefaults(config);
        assertEquals("先看图，再做有用途的区际连接。", AgentPromptConfig.read(config, "city/d4_v2/integration.md"));
        Files.writeString(file, "\uFEFF新的阶段任务");
        assertEquals("新的阶段任务", AgentPromptConfig.read(config, "city/d4_v2/integration.md"));
    }

    @Test void invalidFileDoesNotSilentlyRestoreDefaults() throws Exception {
        AgentPromptConfig.ensureDefaults(config);
        Path file = config.resolve("geomantia/prompts/agent.md");
        Files.writeString(file, "  \n");
        assertThrows(IOException.class, () -> AgentPromptConfig.read(config, "agent.md"));
        assertEquals("  \n", Files.readString(file));
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, () -> AgentPromptConfig.read(config, "agent.md"));
        assertThrows(IllegalArgumentException.class, () -> AgentPromptConfig.read(config, "../other.md"));
    }
}
