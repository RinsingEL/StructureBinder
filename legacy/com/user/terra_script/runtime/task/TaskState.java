package com.user.terra_script.runtime.task;

public enum TaskState {
    ACCEPTED,
    RUNNING,
    WAITING_FOR_AI,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
