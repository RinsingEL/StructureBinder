package com.user.terra_script.domain.territory.stage;

import com.user.terra_script.core.stage.StageBase;
import com.user.terra_script.core.stage.StageContext;

import java.util.List;

public class T1Stage extends StageBase {
    @Override
    public String id() {
        return "T1";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("W4");
    }

    @Override
    protected void execute(StageContext ctx) throws Exception {
        TerritoryStageOrchestrator.runT1(ctx);
    }
}
