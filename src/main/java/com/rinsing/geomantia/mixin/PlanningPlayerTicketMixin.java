package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.platform.*;
import com.rinsing.geomantia.systems.realm_planning.application.access.PlayerChunkDemand;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Filter before the vanilla throttler takes a slot; use vanilla add/remove for every accepted transition. */
@Mixin(targets="net.minecraft.server.level.DistanceManager$PlayerTicketTracker")
public abstract class PlanningPlayerTicketMixin {
    @Unique private DistanceManager geomantia$owner;
    @Inject(method="<init>",at=@At("RETURN"))
    private void geomantia$owner(DistanceManager owner,int distance,CallbackInfo ci) { geomantia$owner=owner; }
    @Shadow private void onLevelChange(long pos,int distance,boolean before,boolean after) { throw new AssertionError(); }
    @Unique private final PlayerChunkDemand geomantia$demand=new PlayerChunkDemand();
    @Unique private boolean geomantia$replaying;
    @Unique private Object geomantia$revision;

    @Unique private ServerLevel geomantia$level() { return ((PlanningDistanceContext)geomantia$owner).geomantia$level(); }
    @Unique private boolean geomantia$allowed(long pos) {
        return PlanningAreaAccessRuntime.permitsPlayerTicket(geomantia$level(),ChunkPos.getX(pos),ChunkPos.getZ(pos));
    }
    @Inject(method="onLevelChange(JIZZ)V",at=@At("HEAD"),cancellable=true)
    private void geomantia$filter(long pos,int distance,boolean before,boolean after,CallbackInfo ci) {
        if(geomantia$replaying || geomantia$level()==null) return;
        var transition=geomantia$demand.update(pos,distance,after,this::geomantia$allowed);
        geomantia$emit(transition);
        ci.cancel();
    }
    @Unique private void geomantia$emit(PlayerChunkDemand.Transition transition) {
        if(transition.before()==transition.after()) return;
        geomantia$replaying=true;
        try { onLevelChange(transition.position(),transition.distance(),transition.before(),transition.after()); }
        finally { geomantia$replaying=false; }
    }
    @Inject(method="runAllUpdates",at=@At("TAIL"))
    private void geomantia$reconcile(CallbackInfo ci) {
        if(geomantia$level()==null) return;
        Object revision=PlanningAreaAccessRuntime.policySnapshot(geomantia$level());
        if(revision==geomantia$revision) return;
        geomantia$revision=revision;
        for(var transition:geomantia$demand.reconcile(this::geomantia$allowed)) geomantia$emit(transition);
    }
}
