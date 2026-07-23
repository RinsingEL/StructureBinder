package com.user.terra_script.world.city.execution;

import com.user.terra_script.event.ServerTickTracker;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;

public final class FailureDecision {
    private final TaskExecutionResult.Outcome outcome;
    private final String errorCode;
    private final String localizedMessage;

    private FailureDecision(TaskExecutionResult.Outcome outcome, String errorCode, String localizedMessage) {
        this.outcome = outcome;
        this.errorCode = errorCode;
        this.localizedMessage = localizedMessage;
    }

    public static FailureDecision fromError(CityC9BuildQueue.BuildTask task, String errorCode) {
        String code = errorCode == null || errorCode.isBlank() ? "unknown_execution_error" : errorCode;
        int nextRetry = task != null ? task.retry_count + 1 : 1;
        boolean blocked = nextRetry >= CityC9BuildQueue.MAX_RETRIES || isTerminalError(code);
        return new FailureDecision(
                blocked ? TaskExecutionResult.Outcome.BLOCKED : TaskExecutionResult.Outcome.RETRIED,
                code,
                localizedError(code)
        );
    }

    public TaskExecutionResult.Outcome outcome() {
        return outcome;
    }

    public String errorCode() {
        return errorCode;
    }

    public String localizedMessage() {
        return localizedMessage;
    }

    public void apply(CityC9BuildQueue.BuildTask task) {
        if (task == null) return;
        task.retry_count++;
        task.last_error = errorCode;
        task.last_error_message = localizedMessage;
        task.updated_at_tick = ServerTickTracker.currentTick();
        task.status = outcome == TaskExecutionResult.Outcome.BLOCKED
                ? CityC9BuildQueue.Status.BLOCKED.name()
                : CityC9BuildQueue.Status.READY.name();
    }

    private static boolean isTerminalError(String error) {
        return "missing_parent_task".equals(error);
    }

    private static String localizedError(String error) {
        if ("missing_parent_task".equals(error)) return "父节点任务不存在，当前节点无法继续施工。";
        if ("waiting_for_parent".equals(error)) return "父节点尚未完成，当前节点需要继续等待。";
        if ("runtime_footprint_collision".equals(error)) return "当前节点与已落地结构发生运行时碰撞。";
        if ("placement_bounds_unavailable".equals(error)) return "无法计算当前结构的落地范围，请检查模板和旋转。";
        if ("structure_place_failed".equals(error)) return "结构写入世界失败，请检查模板和目标位置。";
        return "当前节点运行时执行失败，请查看错误码和上下文。";
    }
}
