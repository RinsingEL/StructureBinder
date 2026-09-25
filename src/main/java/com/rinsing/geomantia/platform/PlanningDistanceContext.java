package com.rinsing.geomantia.platform;

import net.minecraft.server.level.ServerLevel;

/** Dimension ownership for vanilla's nested player ticket tracker. */
public interface PlanningDistanceContext {
    ServerLevel geomantia$level();
    void geomantia$bind(ServerLevel level);
    boolean geomantia$hasPlayerTicket(long position);
}
