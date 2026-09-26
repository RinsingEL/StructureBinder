package com.rinsing.geomantia.systems.provider.application;

/** Transport-assigned role; never taken from a model tool argument. */
public enum PlanningRole {
    ADVANCED, FLASH;
    public static PlanningRole forStep(ProviderPlanningDiscovery.PlanningStep step) {
        return switch (step.stage()) {
            case T1, T2, EXTENSION -> FLASH;
            default -> ADVANCED;
        };
    }
}
