package com.user.terra_script.domain.territory.stage;

import com.user.terra_script.core.stage.StageBase;
import com.user.terra_script.core.stage.StageContext;
import com.user.terra_script.territory.io.TerritoryResultRepository;
import com.user.terra_script.world.TerritoryManager;
import com.google.gson.JsonObject;

import java.util.List;

public class T3Stage extends StageBase {
    @Override
    public String id() {
        return "T3";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("T2");
    }

    @Override
    protected void execute(StageContext ctx) throws Exception {
        TerritoryStageOrchestrator.assertT3Ready(ctx);
        JsonObject stageStart = new JsonObject();
        stageStart.addProperty("territory_count", TerritoryManager.getRegisteredFactions().size());
        TStageTraceLogger.stage(ctx, "T3", "expansion_started", stageStart);

        long claimedTotal = 0L;
        long wildTotal = 0L;
        int exported = 0;
        java.util.Set<Integer> continents = new java.util.LinkedHashSet<>();
        for (TerritoryManager.TerritoryConfig cfg : TerritoryManager.getRegisteredFactions()) {
            if (cfg != null && cfg.selectedContinentId > 0) continents.add(cfg.selectedContinentId);
        }
        for (Integer continentId : continents) {
            exported += TerritoryStageOrchestrator.runT3ForContinent(ctx, continentId).size();
        }
        for (var result : TerritoryManager.getAllResults()) {
            if (result == null) continue;
            claimedTotal += result.claimedChunks != null ? result.claimedChunks.size() : 0;
            wildTotal += result.wildChunks != null ? result.wildChunks.size() : 0;
        }
        JsonObject stageDone = new JsonObject();
        stageDone.addProperty("exported_count", exported);
        stageDone.addProperty("claimed_total", claimedTotal);
        stageDone.addProperty("wild_total", wildTotal);
        TStageTraceLogger.stage(ctx, "T3", "expansion_completed", stageDone);
        if (claimedTotal <= 0 && wildTotal <= 0) {
            throw new IllegalStateException(
                    "T3 produced zero claimed chunks for all territories. " +
                    "Likely causes: missing/invalid W3-W4 scan cache, missing cluster map, " +
                    "or territory region_id does not match current W3 continent ids."
            );
        }
    }
}
