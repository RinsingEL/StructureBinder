package com.rinsing.geomantia.api.planning;

/** Stable lifecycle points. More hooks may be added in future API versions. */
public enum PlanningHook {
    /** City blueprint has been compiled to waiting_for_generation; before the next design task. */
    AFTER_CITY_PLANNING
}
