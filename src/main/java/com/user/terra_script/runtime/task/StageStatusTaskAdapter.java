package com.user.terra_script.runtime.task;

import com.user.terra_script.core.workflow.StageStatus;

public final class StageStatusTaskAdapter {
    private StageStatusTaskAdapter() {}

    public static RuntimeTaskHandle toHandle(String taskId, StageStatus status, RuntimeTaskResult result) {
        StageStatus safeStatus = status == null
                ? new StageStatus(StageStatus.State.NOT_STARTED, "", System.currentTimeMillis())
                : status;
        TaskState state = mapState(safeStatus);
        Long finishedAt = state == TaskState.SUCCEEDED || state == TaskState.FAILED || state == TaskState.CANCELLED
                ? safeStatus.lastUpdatedMillis
                : null;
        return new RuntimeTaskHandle(
                taskId,
                TaskExecutionMode.START_AND_POLL,
                state,
                safeStatus.message,
                new RuntimeTaskProgress(
                        safeStatus.progressPercent,
                        safeStatus.progressCurrent,
                        safeStatus.progressTotal,
                        safeStatus.activeOperation
                ),
                safeStatus.waitingForAi,
                safeStatus.nextAction,
                safeStatus.startedAtMillis,
                safeStatus.heartbeatAtMillis,
                finishedAt,
                result,
                state == TaskState.FAILED ? safeStatus.message : null
        );
    }

    private static TaskState mapState(StageStatus status) {
        if (status == null) return TaskState.ACCEPTED;
        if (status.waitingForAi) return TaskState.WAITING_FOR_AI;
        return switch (status.state) {
            case RUNNING -> TaskState.RUNNING;
            case DONE -> TaskState.SUCCEEDED;
            case FAILED -> TaskState.FAILED;
            case NOT_STARTED -> TaskState.ACCEPTED;
        };
    }
}
