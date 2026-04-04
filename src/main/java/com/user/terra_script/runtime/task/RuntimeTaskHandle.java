package com.user.terra_script.runtime.task;

public final class RuntimeTaskHandle {
    public final String taskId;
    public final TaskExecutionMode executionMode;
    public final TaskState state;
    public final String message;
    public final RuntimeTaskProgress progress;
    public final boolean waitingForAi;
    public final String nextAction;
    public final long startedAt;
    public final long heartbeatAt;
    public final Long finishedAt;
    public final RuntimeTaskResult result;
    public final String error;

    public RuntimeTaskHandle(
            String taskId,
            TaskExecutionMode executionMode,
            TaskState state,
            String message,
            RuntimeTaskProgress progress,
            boolean waitingForAi,
            String nextAction,
            long startedAt,
            long heartbeatAt,
            Long finishedAt,
            RuntimeTaskResult result,
            String error
    ) {
        this.taskId = taskId;
        this.executionMode = executionMode;
        this.state = state;
        this.message = message == null ? "" : message;
        this.progress = progress;
        this.waitingForAi = waitingForAi;
        this.nextAction = nextAction == null ? "" : nextAction;
        this.startedAt = startedAt;
        this.heartbeatAt = heartbeatAt;
        this.finishedAt = finishedAt;
        this.result = result;
        this.error = error;
    }
}
