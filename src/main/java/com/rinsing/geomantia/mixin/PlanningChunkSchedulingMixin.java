package com.rinsing.geomantia.mixin;

import com.mojang.datafixers.util.Either;
import com.rinsing.geomantia.platform.PlanningAreaAccessRuntime;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

/** Reject before EMPTY disk load or any generation stage; no fake success or pending futures. */
@Mixin(ChunkMap.class)
public abstract class PlanningChunkSchedulingMixin {
    @Shadow @Final private ServerLevel level;
    @Inject(method="schedule", at=@At("HEAD"), cancellable=true)
    private void geomantia$protectUnreleasedRegion(ChunkHolder holder, ChunkStatus status,
            CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess,ChunkHolder.ChunkLoadingFailure>>> cir) {
        if(!PlanningAreaAccessRuntime.permitsChunk(level,holder.getPos().x,holder.getPos().z))
            cir.setReturnValue(ChunkHolder.UNLOADED_CHUNK_FUTURE);
    }
}
