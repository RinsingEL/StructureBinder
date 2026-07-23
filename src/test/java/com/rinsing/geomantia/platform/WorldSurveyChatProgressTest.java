package com.rinsing.geomantia.platform;

import com.rinsing.geomantia.systems.realm_planning.WorldSurveyRunner;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldSurveyChatProgressTest {
    @Test
    void throttlesRepeatedUpdatesButSendsPhaseChangesAndCompletionImmediately() {
        List<Component> messages = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        WorldSurveyChatProgress progress = new WorldSurveyChatProgress(messages::add, clock::get);

        progress.onProgress(update("running", "tile_scan", 0.0, -1L, 0, 10, 0L, 100L));
        clock.set(WorldSurveyChatProgress.CHAT_INTERVAL_NANOS - 1L);
        progress.onProgress(update("running", "tile_scan", 20.0, 8_000L, 2, 10, 0L, 100L));
        clock.set(WorldSurveyChatProgress.CHAT_INTERVAL_NANOS);
        progress.onProgress(update("running", "tile_scan", 30.0, 7_000L, 3, 10, 0L, 100L));
        progress.onProgress(update("running", "micro_sampling", 40.0, 6_000L, 10, 10, 40L, 100L));
        progress.onProgress(update("completed", "complete", 100.0, 0L, 10, 10, 100L, 100L));

        assertEquals(4, messages.size());
        assertTrue(messages.get(0).getString().contains("tiles 0/10"));
        assertTrue(messages.get(1).getString().contains("tiles 3/10"));
        assertTrue(messages.get(2).getString().contains("micro cells 40/100"));
        assertTrue(messages.get(3).getString().contains("W scan completed"));
    }

    private static WorldSurveyRunner.ProgressUpdate update(String status, String phase, double percent, long etaMs,
            int processedTiles, int totalTiles, long completedMicroCells, long totalMicroCells) {
        return new WorldSurveyRunner.ProgressUpdate("chat_progress_test", status, phase, "", 10_000L, percent,
                etaMs, processedTiles, totalTiles, completedMicroCells, totalMicroCells);
    }
}
