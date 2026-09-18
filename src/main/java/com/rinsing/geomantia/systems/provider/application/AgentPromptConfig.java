package com.rinsing.geomantia.systems.provider.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Editable prompts; no content cache and no silent fallback for broken user files. */
public final class AgentPromptConfig {
    static final List<String> FILES = List.of("README.md", "agent.md", "providers/harness.md",
            "providers/direct.md", "city/d4_v2/overview.md", "city/d4_v2/district.md", "city/d4_v2/integration.md",
            "city/d4_v2/finalize.md", "city/d4_v2/complete.md", "city/d4_v2/handbook.md");
    private AgentPromptConfig() { }

    public static String agent(String provider) {
        return read("agent.md") + "\n\n" + read("providers/" + provider + ".md");
    }

    public static String read(String name) {
        try {
            Path config = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
            // Pure JVM tests have no Forge config root; load the same shipped defaults.
            return config == null ? bundled(name) : read(config, name);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot load Agent prompt " + name, failure);
        }
    }

    public static void ensureDefaults(Path config) throws IOException {
        for (String name : FILES) read(config, name);
    }

    static String read(Path config, String name) throws IOException {
        if (!FILES.contains(name)) throw new IllegalArgumentException("Unknown prompt: " + name);
        Path file = config.resolve("geomantia/prompts").resolve(name);
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            try {
                Files.writeString(file, bundled(name), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException concurrentReader) {
                // Another reader initialized it. Read that file, never overwrite it.
            }
        }
        return checked(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }

    private static String bundled(String name) throws IOException {
        if (!FILES.contains(name)) throw new IllegalArgumentException("Unknown prompt: " + name);
        try (var stream = AgentPromptConfig.class.getResourceAsStream("/geomantia/prompts/" + name)) {
            if (stream == null) throw new IOException("Missing bundled prompt: " + name);
            return checked(new String(stream.readAllBytes(), StandardCharsets.UTF_8), name);
        }
    }

    private static String checked(String text, String path) throws IOException {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.isBlank()) throw new IOException("Empty Agent prompt: " + path);
        return text.strip();
    }
}
