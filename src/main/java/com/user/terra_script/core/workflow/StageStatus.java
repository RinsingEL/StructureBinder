package com.user.terra_script.core.workflow;

public final class StageStatus {
    public enum State { NOT_STARTED, RUNNING, DONE, FAILED }

    public final State state;
    public final String message;
    public final long lastUpdatedMillis;

    public StageStatus(State state, String message, long lastUpdatedMillis) {
        this.state = state;
        this.message = message;
        this.lastUpdatedMillis = lastUpdatedMillis;
    }
}
