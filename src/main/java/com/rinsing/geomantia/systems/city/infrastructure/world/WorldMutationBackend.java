package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.domain.model.BuildOperationPlan;
import com.rinsing.geomantia.systems.city.domain.model.WorldMutationReport;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;

public interface WorldMutationBackend {
    WorldMutationReport execute(ServerLevel level, BuildOperationPlan plan, Path serverRoot);
}
