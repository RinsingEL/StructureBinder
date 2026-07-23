package com.user.terra_script.core.stage;

import java.util.List;

public interface Stage {
    String id();
    List<String> dependsOn();
    StageResult run(StageContext ctx) throws Exception;
}
