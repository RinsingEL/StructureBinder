package com.user.terra_script.server.mcp.protocol;

public final class TaskAcceptedResponse extends TaskStatusResponse {
    public static TaskAcceptedResponse from(TaskStatusResponse base) {
        TaskAcceptedResponse response = new TaskAcceptedResponse();
        response.task_id = base.task_id;
        response.state = base.state;
        response.message = base.message;
        response.progress_percent = base.progress_percent;
        response.progress_current = base.progress_current;
        response.progress_total = base.progress_total;
        response.active_operation = base.active_operation;
        response.waiting_for_ai = base.waiting_for_ai;
        response.next_action = base.next_action;
        response.started_at = base.started_at;
        response.heartbeat_at = base.heartbeat_at;
        response.finished_at = base.finished_at;
        response.result = base.result;
        response.error = base.error;
        return response;
    }
}
