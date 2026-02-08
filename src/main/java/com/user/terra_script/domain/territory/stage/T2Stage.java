package com.user.terra_script.domain.territory.stage;

import com.user.terra_script.core.stage.StageBase;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.territory.io.TerritoryResultRepository;
import com.user.terra_script.world.TerritoryManager;

import java.util.List;

public class T2Stage extends StageBase {
    @Override
    public String id() {
        return "T2";
    }

    @Override
    public List<String> dependsOn() {
        return List.of();
    }

    @Override
    protected void execute(StageContext ctx) throws Exception {
        TerritoryManager.ensureLoaded();
        int exported = TerritoryResultRepository.exportAllT2Capital(ctx.server, TerritoryManager.getRegisteredFactions());
        if (exported <= 0) {
            throw new IllegalStateException("No territory configs available for T2 export");
        }
    }
}
