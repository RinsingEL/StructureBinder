package com.user.terra_script.world.city.execution;

public final class TaskExecutionResult {
    public enum Outcome {
        COMPLETED,
        BLOCKED,
        RETRIED,
        SKIPPED
    }

    private final Outcome outcome;
    private final String errorCode;
    private final TerrainPreparationResult terrainPreparation;

    private TaskExecutionResult(Outcome outcome, String errorCode, TerrainPreparationResult terrainPreparation) {
        this.outcome = outcome;
        this.errorCode = errorCode;
        this.terrainPreparation = terrainPreparation;
    }

    public static TaskExecutionResult completed(TerrainPreparationResult terrainPreparation) {
        return new TaskExecutionResult(Outcome.COMPLETED, null, terrainPreparation);
    }

    public static TaskExecutionResult blocked(String errorCode, TerrainPreparationResult terrainPreparation) {
        return new TaskExecutionResult(Outcome.BLOCKED, errorCode, terrainPreparation);
    }

    public static TaskExecutionResult retried(String errorCode, TerrainPreparationResult terrainPreparation) {
        return new TaskExecutionResult(Outcome.RETRIED, errorCode, terrainPreparation);
    }

    public static TaskExecutionResult skipped(String errorCode) {
        return new TaskExecutionResult(Outcome.SKIPPED, errorCode, null);
    }

    public Outcome outcome() {
        return outcome;
    }

    public String errorCode() {
        return errorCode;
    }

    public TerrainPreparationResult terrainPreparation() {
        return terrainPreparation;
    }
}
