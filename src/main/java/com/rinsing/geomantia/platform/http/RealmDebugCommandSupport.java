package com.rinsing.geomantia.platform.http;

import java.util.Locale;
import java.util.Set;

final class RealmDebugCommandSupport {
    private static final Set<String> UNSAFE_ROOT_COMMANDS = Set.of(
            "stop",
            "reload",
            "save-all",
            "save-off",
            "save-on",
            "op",
            "deop",
            "ban",
            "ban-ip",
            "pardon",
            "pardon-ip",
            "whitelist"
    );

    private RealmDebugCommandSupport() {
    }

    static String normalizeCommand(String command) {
        if (command == null) {
            throw new IllegalArgumentException("command is required.");
        }
        String normalized = command.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("command is required.");
        }
        if (normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("command must be a single Minecraft command line.");
        }
        return normalized;
    }

    static String prefixedCommand(String normalizedCommand) {
        return "/" + normalizeCommand(normalizedCommand);
    }

    static String normalizeSourceMode(String sourceMode) {
        if (sourceMode == null || sourceMode.isBlank()) {
            return "auto";
        }
        String normalized = sourceMode.trim().toLowerCase(Locale.ROOT);
        if ("auto".equals(normalized) || "player".equals(normalized) || "server".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("sourceMode must be one of: auto, player, server.");
    }

    static void requireSafeOrExplicit(String normalizedCommand, boolean allowUnsafeCommand) {
        String root = rootCommand(normalizedCommand);
        if (!allowUnsafeCommand && UNSAFE_ROOT_COMMANDS.contains(root)) {
            throw new IllegalArgumentException("UNSAFE_DEBUG_COMMAND_REQUIRES_ALLOW_UNSAFE: /" + root
                    + " requires allowUnsafeCommand=true.");
        }
    }

    private static String rootCommand(String normalizedCommand) {
        String command = normalizeCommand(normalizedCommand);
        int space = command.indexOf(' ');
        String root = space >= 0 ? command.substring(0, space) : command;
        return root.toLowerCase(Locale.ROOT);
    }
}
