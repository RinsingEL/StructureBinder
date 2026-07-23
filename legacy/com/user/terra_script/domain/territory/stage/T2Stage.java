package com.user.terra_script.domain.territory.stage;

import com.user.terra_script.core.stage.StageBase;
import com.user.terra_script.core.stage.StageContext;

import java.util.List;

public class T2Stage extends StageBase {
    @Override
    public String id() {
        return "T2";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("T1");
    }

    @Override
    protected void execute(StageContext ctx) throws Exception {
        TerritoryStageOrchestrator.runT2(ctx);
    }
}
