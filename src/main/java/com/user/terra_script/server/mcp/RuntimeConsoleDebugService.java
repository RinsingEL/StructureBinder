package com.user.terra_script.server.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class RuntimeConsoleDebugService {
    private RuntimeConsoleDebugService() {}

    static JsonObject readLogs(String source, int lines, String contains) throws Exception {
        return readLogs(source, lines, contains, 20, 500);
    }

    static JsonObject readLogs(String source, int lines, String contains, int maxSegments, int maxTotalLines) throws Exception {
        String normalizedSource = normalizeSource(source);
        Path path = resolveConsolePath(normalizedSource);

        JsonObject result = new JsonObject();
        result.addProperty("source", normalizedSource);
        result.addProperty("path", path != null ? path.toString() : "");
        result.addProperty("exists", path != null && Files.exists(path));
        result.addProperty("requested_lines", Math.max(1, lines));
        if (path == null || !Files.exists(path)) {
            result.add("lines", new JsonArray());
            result.add("segments", new JsonArray());
            result.addProperty("matched_line_count", 0);
            result.addProperty("segment_count", 0);
            return result;
        }

        List<String> content = Files.readAllLines(path, StandardCharsets.UTF_8);
        String filter = contains == null ? "" : contains.trim().toLowerCase(Locale.ROOT);
        int boundedLines = Math.max(1, Math.min(500, lines));
        int boundedSegments = Math.max(1, Math.min(100, maxSegments));
        int boundedTotalLines = Math.max(1, Math.min(5000, maxTotalLines));

        if (filter.isBlank()) {
            return tailMode(result, content, boundedLines);
        }

        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i).toLowerCase(Locale.ROOT).contains(filter)) hits.add(i);
        }

        List<Segment> merged = mergeSegments(hits, boundedLines, content.size());
        if (merged.size() > boundedSegments) {
            merged = new ArrayList<>(merged.subList(0, boundedSegments));
        }

        JsonArray segments = new JsonArray();
        JsonArray array = new JsonArray();
        int emittedLines = 0;
        for (Segment segment : merged) {
            if (emittedLines >= boundedTotalLines) break;
            JsonObject row = new JsonObject();
            row.addProperty("start_line", segment.start + 1);
            row.addProperty("end_line", segment.end + 1);
            row.addProperty("match_count", segment.matchCount);
            JsonArray segmentLines = new JsonArray();
            for (int i = segment.start; i <= segment.end && emittedLines < boundedTotalLines; i++) {
                String line = content.get(i);
                segmentLines.add(line);
                array.add(line);
                emittedLines++;
            }
            row.add("lines", segmentLines);
            segments.add(row);
        }

        result.addProperty("matched_line_count", hits.size());
        result.addProperty("segment_count", segments.size());
        result.add("segments", segments);
        result.add("lines", array);
        return result;
    }

    private static Path resolveConsolePath(String normalizedSource) throws Exception {
        return findLatestGradleDaemonLog();
    }

    static Path findLatestGradleDaemonLog() throws Exception {
        Path daemonDir = Path.of(System.getProperty("user.home"), ".gradle", "daemon");
        if (!Files.exists(daemonDir)) return null;
        try (Stream<Path> stream = Files.walk(daemonDir)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".out.log"))
                    .max(Comparator.comparingLong(RuntimeConsoleDebugService::lastModified))
                    .orElse(null);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static JsonObject tailMode(JsonObject result, List<String> content, int boundedLines) {
        int from = Math.max(0, content.size() - boundedLines);
        JsonArray lines = new JsonArray();
        JsonArray segments = new JsonArray();
        JsonObject segment = new JsonObject();
        segment.addProperty("start_line", content.isEmpty() ? 0 : from + 1);
        segment.addProperty("end_line", content.size());
        segment.addProperty("match_count", 0);
        JsonArray segmentLines = new JsonArray();
        for (int i = from; i < content.size(); i++) {
            String line = content.get(i);
            lines.add(line);
            segmentLines.add(line);
        }
        segment.add("lines", segmentLines);
        if (segmentLines.size() > 0) segments.add(segment);
        result.addProperty("matched_line_count", 0);
        result.addProperty("segment_count", segments.size());
        result.add("segments", segments);
        result.add("lines", lines);
        return result;
    }

    private static List<Segment> mergeSegments(List<Integer> hits, int contextLines, int totalLines) {
        List<Segment> windows = new ArrayList<>();
        for (Integer hit : hits) {
            int start = Math.max(0, hit - contextLines);
            int end = Math.min(totalLines - 1, hit + contextLines);
            windows.add(new Segment(start, end, 1));
        }
        windows.sort(Comparator.comparingInt(segment -> segment.start));
        List<Segment> merged = new ArrayList<>();
        for (Segment window : windows) {
            if (merged.isEmpty()) {
                merged.add(window);
                continue;
            }
            Segment last = merged.get(merged.size() - 1);
            if (window.start <= last.end + 1) {
                last.end = Math.max(last.end, window.end);
                last.matchCount += window.matchCount;
            } else {
                merged.add(window);
            }
        }
        return merged;
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) return "gradle";
        String normalized = source.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "gradle", "idea", "console", "gradle_console", "idea_console" -> "gradle";
            default -> "gradle";
        };
    }

    private static final class Segment {
        int start;
        int end;
        int matchCount;

        Segment(int start, int end, int matchCount) {
            this.start = start;
            this.end = end;
            this.matchCount = matchCount;
        }
    }
}
