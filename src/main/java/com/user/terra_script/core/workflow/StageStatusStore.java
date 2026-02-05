package com.user.terra_script.core.workflow;

import com.user.terra_script.core.stage.StageContext;

public interface StageStatusStore {
    void markRunning(String stageId, StageContext ctx) throws Exception;
    void markDone(String stageId, StageContext ctx) throws Exception;
    void markFailed(String stageId, StageContext ctx, Exception e) throws Exception;
    StageStatus getStatus(String stageId, StageContext ctx) throws Exception;
}
