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
        TerritoryManager.runExpansion();
        var allResults = TerritoryManager.getAllResults();
        int exported = TerritoryResultRepository.exportAllT3(ctx.server, allResults);
        if (exported <= 0) {
            throw new IllegalStateException("No territory results available for T3 export");
        }

        long claimedTotal = 0L;
        long wildTotal = 0L;
        for (var result : allResults) {
            if (result == null) continue;
            claimedTotal += result.claimedChunks != null ? result.claimedChunks.size() : 0;
            wildTotal += result.wildChunks != null ? result.wildChunks.size() : 0;
            if (result.config != null && result.config.id != null) {
                JsonObject territory = new JsonObject();
                territory.addProperty("territory_id", result.config.id);
                territory.addProperty("continent_id", result.config.regionId);
                territory.addProperty("claimed_chunks", result.claimedChunks != null ? result.claimedChunks.size() : 0);
                territory.addProperty("wild_chunks", result.wildChunks != null ? result.wildChunks.size() : 0);
                TStageTraceLogger.territory(ctx, "T3", result.config.id, "territory_expanded", territory);
            }
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
