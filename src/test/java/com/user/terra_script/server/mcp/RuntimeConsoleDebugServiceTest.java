package com.user.terra_script.server.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConsoleDebugServiceTest {
    @Test
    void mergesOverlappingKeywordWindows() throws Exception {
        Method merge = RuntimeConsoleDebugService.class.getDeclaredMethod("mergeSegments", List.class, int.class, int.class);
        merge.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> segments = (List<Object>) merge.invoke(null, List.of(10, 15), 5, 100);

        assertEquals(1, segments.size());
        assertEquals(5, readInt(segments.get(0), "start"));
        assertEquals(20, readInt(segments.get(0), "end"));
        assertEquals(2, readInt(segments.get(0), "matchCount"));
    }

    @Test
    void tailModeKeepsCompatLinesArray() throws Exception {
        Method tail = RuntimeConsoleDebugService.class.getDeclaredMethod("tailMode", JsonObject.class, List.class, int.class);
        tail.setAccessible(true);

        JsonObject result = (JsonObject) tail.invoke(null, new JsonObject(), List.of("a", "b", "c", "d"), 2);

        JsonArray lines = result.getAsJsonArray("lines");
        JsonArray segments = result.getAsJsonArray("segments");
        assertEquals(2, lines.size());
        assertEquals("c", lines.get(0).getAsString());
        assertEquals(1, segments.size());
        assertEquals(3, segments.get(0).getAsJsonObject().get("start_line").getAsInt());
    }

    @Test
    void mergedSegmentsPreserveSeparatedHits() throws Exception {
        Method merge = RuntimeConsoleDebugService.class.getDeclaredMethod("mergeSegments", List.class, int.class, int.class);
        merge.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> segments = (List<Object>) merge.invoke(null, List.of(5, 40), 3, 100);

        assertEquals(2, segments.size());
        assertTrue(readInt(segments.get(0), "end") < readInt(segments.get(1), "start"));
    }

    @Test
    void findsLatestGradleDaemonLogWhenPresent() throws Exception {
        Path path = RuntimeConsoleDebugService.findLatestGradleDaemonLog();
        assertNotNull(path);
        assertTrue(path.getFileName().toString().endsWith(".out.log"));
    }

    private static int readInt(Object target, String field) throws Exception {
        var declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        return declared.getInt(target);
    }
}
