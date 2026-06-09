package com.user.terra_script.core.workflow;

import com.user.terra_script.core.stage.Stage;

import java.util.HashMap;
import java.util.Map;

public final class StageRegistry {
    private final Map<String, Stage> stages = new HashMap<>();

    public void register(Stage s) {
        stages.put(s.id(), s);
    }

    public Stage get(String stageId) {
        Stage s = stages.get(stageId);
        if (s == null) throw new IllegalArgumentException("Unknown stage: " + stageId);
        return s;
    }
}
