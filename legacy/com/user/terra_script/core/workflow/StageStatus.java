package com.user.terra_script.core.workflow;

public final class StageStatus {
    public enum State { NOT_STARTED, RUNNING, DONE, FAILED }

    public final State state;
    public final String message;
    public final long lastUpdatedMillis;
    public final long startedAtMillis;
    public final long heartbeatAtMillis;
    public final int progressPercent;
    public final long progressCurrent;
    public final long progressTotal;
    public final boolean waitingForAi;
    public final String nextAction;
    public final String activeOperation;

    public StageStatus(State state, String message, long lastUpdatedMillis) {
        this(state, message, lastUpdatedMillis, 0L, lastUpdatedMillis, -1, -1L, -1L, false, "", "");
    }

    public StageStatus(
            State state,
            String message,
            long lastUpdatedMillis,
            long startedAtMillis,
            long heartbeatAtMillis,
            int progressPercent,
            long progressCurrent,
            long progressTotal,
            boolean waitingForAi,
            String nextAction,
            String activeOperation
    ) {
        this.state = state;
        this.message = message;
        this.lastUpdatedMillis = lastUpdatedMillis;
        this.startedAtMillis = startedAtMillis;
        this.heartbeatAtMillis = heartbeatAtMillis;
        this.progressPercent = progressPercent;
        this.progressCurrent = progressCurrent;
        this.progressTotal = progressTotal;
        this.waitingForAi = waitingForAi;
        this.nextAction = nextAction;
        this.activeOperation = activeOperation;
    }
}
