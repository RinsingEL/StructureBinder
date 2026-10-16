package com.rinsing.realmfolk.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.pathfinder.*;

/** Solid decorative plants must not be treated as empty walking space. */
final class CitizenNavigation extends GroundPathNavigation {
    CitizenNavigation(Mob mob, Level level) { super(mob, level); }

    @Override protected PathFinder createPathFinder(int maxVisitedNodes) {
        nodeEvaluator = new WalkNodeEvaluator() {
            @Override public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
                var pos = new BlockPos(x,y,z);
                var state = level.getBlockState(pos);
                // Vanilla's non-full-cube walkability admits azalea even though its canopy blocks the body.
                if (state.getBlock() instanceof BushBlock && !state.getCollisionShape(level,pos).isEmpty())
                    return BlockPathTypes.BLOCKED;
                return super.getBlockPathType(level,x,y,z);
            }
        };
        nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(nodeEvaluator,maxVisitedNodes);
    }
}
