package com.user.terra_script.server.mcp.protocol;

import com.google.gson.JsonObject;
import com.user.terra_script.runtime.task.RuntimeTaskHandle;

public class TaskStatusResponse {
    public String task_id;
    public String state;
    public String message;
    public int progress_percent;
    public long progress_current;
    public long progress_total;
    public String active_operation;
    public boolean waiting_for_ai;
    public String next_action;
    public long started_at;
    public long heartbeat_at;
    public Long finished_at;
    public JsonObject result;
    public String error;

    public static TaskStatusResponse from(RuntimeTaskHandle handle) {
        TaskStatusResponse response = new TaskStatusResponse();
        response.task_id = handle.taskId;
        response.state = handle.state.name();
        response.message = handle.message;
        response.progress_percent = handle.progress == null ? -1 : handle.progress.percent;
        response.progress_current = handle.progress == null ? -1L : handle.progress.current;
        response.progress_total = handle.progress == null ? -1L : handle.progress.total;
        response.active_operation = handle.progress == null ? "" : handle.progress.activeOperation;
        response.waiting_for_ai = handle.waitingForAi;
        response.next_action = handle.nextAction;
        response.started_at = handle.startedAt;
        response.heartbeat_at = handle.heartbeatAt;
        response.finished_at = handle.finishedAt;
        response.result = handle.result == null ? null : handle.result.payload;
        response.error = handle.error;
        return response;
    }
}
