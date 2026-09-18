package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Read-only evidence access, confined to the real current-run directory. */
final class PlanningArtifacts {
    static JsonObject read(Path run, String operation, String path, String query, int offset) throws Exception {
        Path root = run.toRealPath();
        Path requested = Path.of(path.isBlank() ? "." : path);
        if (requested.isAbsolute()) throw new IllegalArgumentException("ARTIFACT_RELATIVE_PATH_REQUIRED");
        Path target = root.resolve(requested).normalize().toRealPath();
        if (!target.startsWith(root)) throw new IllegalArgumentException("ARTIFACT_OUT_OF_SCOPE");
        JsonObject result = new JsonObject(); result.addProperty("ok", true);
        result.addProperty("path", root.relativize(target).toString());
        switch (operation) {
            case "list", "search" -> {
                JsonArray entries = new JsonArray();
                try (var stream = operation.equals("list") ? Files.list(target) : Files.walk(target, 12)) {
                    var iterator = stream.iterator(); int visited = 0;
                    while (iterator.hasNext() && visited++ < 5000 && entries.size() < 200) {
                        Path item = iterator.next();
                        if (Files.isSymbolicLink(item) || !item.toRealPath().startsWith(root)) continue;
                        String relative = root.relativize(item).toString();
                        if (!operation.equals("search") || relative.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                            entries.add(relative + (Files.isDirectory(item) ? "/" : ""));
                    }
                    result.addProperty("truncated", iterator.hasNext());
                }
                result.add("entries", entries);
            }
            case "text", "image" -> {
                if (!Files.isRegularFile(target) || Files.size(target) > 8L * 1024 * 1024)
                    throw new IllegalArgumentException("ARTIFACT_FILE_TOO_LARGE_OR_NOT_REGULAR");
                String filename = target.getFileName().toString().toLowerCase(Locale.ROOT);
                if (operation.equals("text")) {
                    if (!filename.matches(".*\\.(json|jsonl|txt|md|csv|log|yaml|yml)$"))
                        throw new IllegalArgumentException("ARTIFACT_TEXT_TYPE_REQUIRED");
                    String text = Files.readString(target, StandardCharsets.UTF_8);
                    int start = Math.min(Math.max(0, offset), text.length()), end = Math.min(start + 24000, text.length());
                    result.addProperty("text", text.substring(start, end));
                    result.addProperty("nextOffset", end); result.addProperty("hasMore", end < text.length());
                } else {
                    String mime = filename.endsWith(".png") ? "image/png" : filename.matches(".*\\.jpe?g$") ? "image/jpeg" : filename.endsWith(".webp") ? "image/webp" : "";
                    if (mime.isEmpty()) throw new IllegalArgumentException("ARTIFACT_IMAGE_TYPE_REQUIRED");
                    JsonObject image = new JsonObject(); image.addProperty("type", "image"); image.addProperty("mimeType", mime);
                    image.addProperty("data", Base64.getEncoder().encodeToString(Files.readAllBytes(target)));
                    JsonArray images = new JsonArray(); images.add(image); result.add("imageEvidence", images);
                }
            }
            default -> throw new IllegalArgumentException("ARTIFACT_OPERATION_UNKNOWN");
        }
        return result;
    }
}
