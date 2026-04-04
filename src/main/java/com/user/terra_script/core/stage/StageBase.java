package com.user.terra_script.core.stage;

import com.user.terra_script.core.workflow.StageStatusStore;

public abstract class StageBase implements Stage {

    protected abstract void execute(StageContext ctx) throws Exception;

    protected boolean canSkip(StageContext ctx) { return false; }

    @Override
    public final StageResult run(StageContext ctx) throws Exception {
        if (canSkip(ctx)) return StageResult.skip(id() + " skipped");

        StageStatusStore store = ctx.statusStore;
        if (store != null) store.markRunning(id(), ctx);

        try {
            execute(ctx);
            if (store != null) store.markDone(id(), ctx);
            return StageResult.done(id() + " done");
        } catch (Exception e) {
            if (store != null) store.markFailed(id(), ctx, e);
            throw e;
        }
    }
}
