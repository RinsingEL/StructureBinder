package com.user.terra_script.core.workflow;

import com.user.terra_script.core.stage.Stage;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.core.stage.StageResult;

import java.util.HashSet;
import java.util.Set;

public final class WorkflowEngine {
    private final StageRegistry registry;

    public WorkflowEngine(StageRegistry registry) {
        this.registry = registry;
    }

    public StageResult runStage(String stageId, StageContext ctx) throws Exception {
        Set<String> visiting = new HashSet<>();
        runDeps(stageId, ctx, visiting);
        return registry.get(stageId).run(ctx);
    }

    private void runDeps(String stageId, StageContext ctx, Set<String> visiting) throws Exception {
        if (!visiting.add(stageId)) throw new IllegalStateException("Circular dependency: " + stageId);

        Stage s = registry.get(stageId);
        for (String dep : s.dependsOn()) {
            runDeps(dep, ctx, visiting);
            registry.get(dep).run(ctx);
        }
        visiting.remove(stageId);
    }
}
