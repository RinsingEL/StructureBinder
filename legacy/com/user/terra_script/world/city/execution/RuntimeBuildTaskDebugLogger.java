package com.user.terra_script.world.city.execution;

import com.google.gson.JsonObject;
import com.user.terra_script.runtime.context.RuntimeLogContext;
import com.user.terra_script.runtime.log.RuntimeLogEvent;
import com.user.terra_script.runtime.log.RuntimeLogger;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;

public final class RuntimeBuildTaskDebugLogger implements BuildTaskDebugLogger {
    private final RuntimeLogger logger;
    private final CityC9BuildQueue.BuildTask task;

    public RuntimeBuildTaskDebugLogger(BuildWorldAccess world, CityC9BuildQueue.BuildTask task) {
        this.task = task;
        if (world == null || world.server() == null) {
            this.logger = null;
            return;
        }
        this.logger = RuntimeLogger.forServer(
                world.server(),
                RuntimeLogContext.builder()
                        .domain("city")
                        .scope("task")
                        .taskId(task != null ? task.task_id : null)
                        .stageId("C9")
                        .cityId(task != null ? task.city_id : null)
                        .build()
        );
    }

    @Override
    public void progress(String message, JsonObject details) {
        if (logger == null) return;
        logger.info(RuntimeLogEvent.TASK_PROGRESS, message, enrich(details));
    }

    @Override
    public void completed(String message, JsonObject details) {
        if (logger == null) return;
        logger.info(RuntimeLogEvent.TASK_COMPLETED, message, enrich(details));
    }

    @Override
    public void failed(String message, JsonObject details) {
        if (logger == null) return;
        logger.error(RuntimeLogEvent.TASK_FAILED, message, enrich(details));
    }

    private JsonObject enrich(JsonObject details) {
        JsonObject out = details == null ? new JsonObject() : details.deepCopy();
        if (task == null) return out;
        out.addProperty("task_id", task.task_id);
        out.addProperty("city_id", task.city_id);
        out.addProperty("group_id", task.group_id);
        out.addProperty("build_area_id", task.build_area_id);
        out.addProperty("node_id", task.node_id);
        out.addProperty("template_id", task.template_id);
        return out;
    }
}
