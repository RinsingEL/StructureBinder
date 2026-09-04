package com.rinsing.geomantia.systems.provider.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs the bundled, dependency-free Hermes runtime inside the Minecraft instance. */
final class HermesPortableRuntime {
    static final String VERSION = "0.18.2-win-x64";
    static final String DIRECTORY_NAME = "hermes-" + VERSION;
    static final String ARCHIVE_SHA256 = "806B3392A6D222CFB0EC3484406962C74A040953A5C612E0BBEC4EFCDE245937";
    private static final String RESOURCE = "/geomantia/sidecar/hermes-runtime-0.18.2-win-x64.zip";
    private static final String MARKER = ".geomantia-runtime.sha256";

    Path ensureInstalled(Path serverDirectory, Consumer<String> progress) throws IOException {
        requireWindowsX64();
        Path root = serverDirectory.toAbsolutePath().normalize()
                .resolve("config").resolve("geomantia").resolve("runtime");
        Path target = root.resolve(DIRECTORY_NAME);
        if (ready(target)) return target;

        Consumer<String> listener = progress == null ? ignored -> { } : progress;
        listener.accept("首次解压 Hermes 内置运行时（约 267 MB）…");
        Files.createDirectories(root);
        Path staging = root.resolve("." + DIRECTORY_NAME + "-install-" + UUID.randomUUID());
        try {
            extract(staging);
            Path extracted = staging.resolve(DIRECTORY_NAME);
            validate(extracted);
            if (Files.exists(target)) deleteTree(target);
            try {
                Files.move(extracted, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(extracted, target);
            }
            Files.writeString(target.resolve(MARKER), ARCHIVE_SHA256, StandardCharsets.US_ASCII);
            listener.accept("Hermes 内置运行时已就绪");
            return target;
        } finally {
            if (Files.exists(staging)) deleteTree(staging);
        }
    }

    private static void extract(Path staging) throws IOException {
        try (InputStream resource = HermesPortableRuntime.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) throw new IOException("内置 Hermes 运行时资源缺失");
            try (ZipInputStream zip = new ZipInputStream(resource)) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    Path destination = staging.resolve(entry.getName()).normalize();
                    if (!destination.startsWith(staging)) {
                        throw new IOException("Hermes 运行时压缩包包含越界路径");
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zip.closeEntry();
                }
            }
        }
    }

    private static boolean ready(Path target) {
        try {
            return ARCHIVE_SHA256.equals(Files.readString(target.resolve(MARKER), StandardCharsets.US_ASCII).trim())
                    && Files.isRegularFile(target.resolve("python/python.exe"))
                    && Files.isRegularFile(target.resolve("site-packages/hermes_cli/main.py"))
                    && Files.isRegularFile(target.resolve("node/node.exe"));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void validate(Path extracted) throws IOException {
        if (!Files.isRegularFile(extracted.resolve("python/python.exe"))
                || !Files.isRegularFile(extracted.resolve("site-packages/hermes_cli/main.py"))
                || !Files.isRegularFile(extracted.resolve("node/node.exe"))) {
            throw new IOException("Hermes 运行时压缩包不完整");
        }
    }

    private static void deleteTree(Path path) throws IOException {
        try (Stream<Path> entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static void requireWindowsX64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            throw new UnsupportedOperationException("Hermes 内置运行时目前仅支持 Windows x64");
        }
    }
}
