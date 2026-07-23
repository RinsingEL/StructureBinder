package com.rinsing.geomantia.platform;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.systems.realm_planning.WorldSurveyRunner;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class WorldSurveyChatProgress implements WorldSurveyRunner.ProgressListener {
    static final long CHAT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Consumer<Component> messageSink;
    private final LongSupplier nanoTime;
    private boolean sent;
    private long lastSentAtNanos;
    private String lastStatus = "";
    private String lastPhase = "";

    WorldSurveyChatProgress(Consumer<Component> messageSink, LongSupplier nanoTime) {
        this.messageSink = messageSink;
        this.nanoTime = nanoTime;
    }

    public static WorldSurveyRunner.ProgressListener forPlayer(ServerPlayer player) {
        if (player == null) {
            return WorldSurveyRunner.ProgressListener.NONE;
        }
        return new WorldSurveyChatProgress(player::sendSystemMessage, System::nanoTime);
    }

    @Override
    public synchronized void onProgress(WorldSurveyRunner.ProgressUpdate update) {
        long now = nanoTime.getAsLong();
        boolean stateChanged = !update.status().equals(lastStatus) || !update.phase().equals(lastPhase);
        if (sent && !stateChanged && now - lastSentAtNanos < CHAT_INTERVAL_NANOS) {
            return;
        }

        Component message = Component.literal(formatMessage(update)).withStyle(color(update));
        try {
            messageSink.accept(message);
        } catch (RuntimeException ex) {
            LOGGER.warn("Could not send W survey progress to chat: {}", ex.getMessage());
        }
        sent = true;
        lastSentAtNanos = now;
        lastStatus = update.status();
        lastPhase = update.phase();
    }

    static String formatMessage(WorldSurveyRunner.ProgressUpdate update) {
        if ("failed".equals(update.status())) {
            String detail = update.detail() == null || update.detail().isBlank() ? "unknown error" : update.detail();
            return "[Geomantia] W scan failed (" + update.runId() + "): " + detail;
        }
        if ("complete".equals(update.phase())) {
            return "[Geomantia] W scan completed (" + update.runId() + ") in "
                    + formatDuration(update.elapsedMs());
        }

        String progress;
        if ("micro_sampling".equals(update.phase())) {
            progress = "micro cells " + update.completedMicroCells() + "/" + update.totalMicroCells();
        } else {
            progress = "tiles " + update.processedTiles() + "/" + update.totalTiles();
        }
        return "[Geomantia] W scan " + progress + " ("
                + String.format(Locale.ROOT, "%.1f", update.phaseProgressPercent()) + "%), ETA "
                + formatEta(update.estimatedRemainingMs());
    }

    private static ChatFormatting color(WorldSurveyRunner.ProgressUpdate update) {
        if ("failed".equals(update.status())) {
            return ChatFormatting.RED;
        }
        if ("complete".equals(update.phase())) {
            return ChatFormatting.GREEN;
        }
        return ChatFormatting.AQUA;
    }

    private static String formatEta(long milliseconds) {
        return milliseconds < 0L ? "calculating" : formatDuration(milliseconds);
    }

    private static String formatDuration(long milliseconds) {
        long seconds = Math.max(0L, milliseconds / 1000L);
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        return minutes > 0L ? minutes + "m " + remainingSeconds + "s" : remainingSeconds + "s";
    }
}
