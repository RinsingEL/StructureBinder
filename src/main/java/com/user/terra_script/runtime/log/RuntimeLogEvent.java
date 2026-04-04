package com.user.terra_script.runtime.log;

public enum RuntimeLogEvent {
    HTTP_REQUEST_RECEIVED("http_request_received"),
    HTTP_REQUEST_SUCCEEDED("http_request_succeeded"),
    HTTP_REQUEST_FAILED("http_request_failed"),
    TASK_STARTED("task_started"),
    TASK_PROGRESS("task_progress"),
    TASK_COMPLETED("task_completed"),
    TASK_FAILED("task_failed"),
    STAGE_STARTED("stage_started"),
    STAGE_COMPLETED("stage_completed"),
    STAGE_FAILED("stage_failed");

    private final String eventName;

    RuntimeLogEvent(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }
}
