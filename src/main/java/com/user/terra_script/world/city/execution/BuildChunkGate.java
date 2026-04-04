package com.user.terra_script.world.city.execution;

import com.user.terra_script.event.ServerTickTracker;
import com.user.terra_script.world.city.stage.c9.CityC9BuildQueue;

public final class BuildChunkGate {
    public GateDecision evaluate(BuildExecutionContext context) {
        if (context == null || context.world() == null || context.task() == null) {
            return GateDecision.skip("missing_execution_context");
        }
        BuildWorldAccess world = context.world();
        CityC9BuildQueue.BuildTask task = context.task();
        if (!world.isServerRunning()) {
            return GateDecision.skip("server_not_running");
        }
        if (!world.hasChunk(task.chunk_x, task.chunk_z)) {
            task.status = CityC9BuildQueue.Status.PLANNED.name();
            task.updated_at_tick = ServerTickTracker.currentTick();
            context.persist();
            return GateDecision.skip("chunk_not_loaded");
        }
        if (!world.isChunkFull(task.chunk_x, task.chunk_z)) {
            task.status = CityC9BuildQueue.Status.READY.name();
            task.updated_at_tick = ServerTickTracker.currentTick();
            context.persist();
            return GateDecision.skip("chunk_not_full");
        }
        return GateDecision.proceed();
    }

    public static final class GateDecision {
        private final boolean proceed;
        private final String reasonCode;

        private GateDecision(boolean proceed, String reasonCode) {
            this.proceed = proceed;
            this.reasonCode = reasonCode;
        }

        public static GateDecision proceed() {
            return new GateDecision(true, null);
        }

        public static GateDecision skip(String reasonCode) {
            return new GateDecision(false, reasonCode);
        }

        public boolean shouldProceed() {
            return proceed;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }
}
