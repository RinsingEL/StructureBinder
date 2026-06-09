package com.user.terra_script.runtime.log;

import com.google.gson.JsonObject;
import com.user.terra_script.runtime.clock.RuntimeTickClock;
import com.user.terra_script.runtime.context.RuntimeLogContext;
import net.minecraft.server.MinecraftServer;

public final class RuntimeLogger {
    private final MinecraftServer server;
    private final RuntimeLogContext context;

    private RuntimeLogger(MinecraftServer server, RuntimeLogContext context) {
        this.server = server;
        this.context = context;
    }

    public static RuntimeLogger forServer(MinecraftServer server, RuntimeLogContext context) {
        return new RuntimeLogger(server, context);
    }

    public void info(RuntimeLogEvent event, String message) {
        write("INFO", event, message, null);
    }

    public void info(RuntimeLogEvent event, String message, JsonObject details) {
        write("INFO", event, message, details);
    }

    public void warn(RuntimeLogEvent event, String message, JsonObject details) {
        write("WARN", event, message, details);
    }

    public void error(RuntimeLogEvent event, String message, JsonObject details) {
        write("ERROR", event, message, details);
    }

    private void write(String level, RuntimeLogEvent event, String message, JsonObject details) {
        RuntimeLogEntry entry = new RuntimeLogEntry();
        entry.ts_epoch_ms = System.currentTimeMillis();
        entry.tick = RuntimeTickClock.currentTick();
        entry.level = level;
        entry.source = context.source;
        entry.domain = context.domain;
        entry.scope = context.scope;
        entry.event = event.eventName();
        entry.message = message == null ? "" : message;
        entry.task_id = context.taskId;
        entry.stage_id = context.stageId;
        entry.city_id = context.cityId;
        entry.territory_id = context.territoryId;
        entry.thread = Thread.currentThread().getName();
        entry.details = details == null ? new JsonObject() : details.deepCopy();
        RuntimeLogManager.append(server, entry, context);
    }
}
