package com.user.terra_script.runtime.task;

public final class RuntimeTaskProgress {
    public final int percent;
    public final long current;
    public final long total;
    public final String activeOperation;

    public RuntimeTaskProgress(int percent, long current, long total, String activeOperation) {
        this.percent = percent;
        this.current = current;
        this.total = total;
        this.activeOperation = activeOperation == null ? "" : activeOperation;
    }
}
