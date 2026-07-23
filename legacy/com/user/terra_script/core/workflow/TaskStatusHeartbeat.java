package com.user.terra_script.core.workflow;

import com.user.terra_script.core.stage.StageContext;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class TaskStatusHeartbeat implements AutoCloseable {
    private final ScheduledExecutorService executor;

    private TaskStatusHeartbeat(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    public static TaskStatusHeartbeat start(FileStageStatusStore store, String taskId, StageContext ctx, String message, String activeOperation) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("TerraScript-Heartbeat-" + taskId);
            return t;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                store.updateHeartbeat(taskId, ctx, message, activeOperation);
            } catch (Exception ignored) {
            }
        }, 60, 60, TimeUnit.SECONDS);
        return new TaskStatusHeartbeat(executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
