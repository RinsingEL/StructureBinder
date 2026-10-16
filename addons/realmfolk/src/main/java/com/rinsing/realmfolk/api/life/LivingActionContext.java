package com.rinsing.realmfolk.api.life;

import com.rinsing.realmfolk.entity.CitizenEntity;
import com.rinsing.realmfolk.life.LivingSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** A reserved, physically verified facility. Framework ownership is released after action cleanup. */
public record LivingActionContext(CitizenEntity citizen, LivingSite site) {
    public ServerLevel level() { return (ServerLevel)citizen.level(); }
    public BlockPos block() { return new BlockPos(site.object.x(),site.object.y(),site.object.z()); }
    public BlockPos approach() { return new BlockPos(site.approach.x(),site.approach.y(),site.approach.z()); }
    public Direction facing() {
        Direction d=Direction.byName(site.facing);
        return d!=null&&d.getAxis().isHorizontal()?d:Direction.NORTH;
    }
    public Vec3 position(double height) {
        double[] o=site.useOffset;
        return new Vec3(site.object.x()+.5+o[0],site.object.y()+height+o[1],site.object.z()+.5+o[2]);
    }
}
