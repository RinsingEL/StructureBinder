package com.rinsing.realmfolk.api.life;

import com.google.gson.JsonObject;

/** Server-thread action extension. Instances belong to one resident and one behavior execution. */
public interface LivingAction {
    /** Reject invalid parameters when loading configuration, before it replaces the active catalogue. */
    void validate(JsonObject parameters);
    /** Allocate state only; put side effects in tick so cleanup is guaranteed after a failed step. */
    Execution create(LivingActionContext context, JsonObject parameters);

    interface Execution {
        /** Called at the resident's routine cadence (currently once per second). */
        Result tick();
        /** Always called once, in reverse order, including after a completed step or failed execution. */
        void stop();
    }
    enum Result { RUNNING, COMPLETE, FAILED }
}
