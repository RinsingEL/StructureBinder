package com.user.terra_script.core.stage;

import java.time.Instant;
import java.util.Map;

public final class StageResult {
    public enum Status { DONE, SKIPPED, FAILED }

    public final Status status;
    public final String message;
    public final Instant finishedAt;
    public final Map<String, Object> metrics;

    private StageResult(Status status, String message, Map<String, Object> metrics) {
        this.status = status;
        this.message = message;
        this.metrics = metrics;
        this.finishedAt = Instant.now();
    }

    public static StageResult done(String msg) { return new StageResult(Status.DONE, msg, Map.of()); }
    public static StageResult skip(String msg) { return new StageResult(Status.SKIPPED, msg, Map.of()); }
    public static StageResult fail(String msg) { return new StageResult(Status.FAILED, msg, Map.of()); }
}
