package com.rinsing.geomantia.systems.city.infrastructure.world;

import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner;
import com.rinsing.geomantia.systems.city.application.CityStructureMaterializationPlanner.StructureTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

public final class MinecraftCityWorldgenStatusInspector
        implements CityStructureMaterializationPlanner.ChunkStatusInspector {
    private final BiFunction<Integer,Integer,String> generatedStatusProbe;
    private final BiPredicate<StructureTask,ChunkPos> fragmentProof;

    public MinecraftCityWorldgenStatusInspector(ServerLevel level) {
        this((x,z) -> {
            if (level == null) return null;
            ChunkAccess chunk=level.getChunkSource().getChunk(x,z,ChunkStatus.EMPTY,false);
            return chunk!=null && chunk.getStatus().isOrAfter(ChunkStatus.FEATURES) ? chunk.getStatus().toString() : null;
        }, MinecraftCityWorldgenStatusInspector::recordedFragment);
    }

    MinecraftCityWorldgenStatusInspector(BiFunction<Integer,Integer,String> statusProbe,
                                         BiPredicate<StructureTask,ChunkPos> fragmentProof) {
        this.generatedStatusProbe=statusProbe;
        this.fragmentProof=fragmentProof;
    }

    private static boolean recordedFragment(StructureTask task,ChunkPos owner) {
        var planned=CityReservationMaskRegistry.findTemplatePlacement(task.anchorId(),task.templateRef(),
                task.templateHash(),task.anchorBlock());
        return planned.filter(p -> p.lockedActualFootprint().equals(task.footprint())
                && p.rotation().equals(task.rotation())
                && p.sourcePlan().has("mirror")
                && p.sourcePlan().get("mirror").getAsString().equals(task.mirror()))
                .map(p -> CityReservationMaskRegistry.hasTemplateFragmentProof(p,owner)).orElse(false);
    }

    @Override
    public CityStructureMaterializationPlanner.ChunkStatusResult inspect(
            StructureTask task) {
        int completed=0,total=0;
        var bounds=task.footprint();
        for(int x=Math.floorDiv(bounds.minX(),16);x<=Math.floorDiv(bounds.maxX(),16);x++) {
            for(int z=Math.floorDiv(bounds.minZ(),16);z<=Math.floorDiv(bounds.maxZ(),16);z++) {
                total++;
                // A finished anchor owner does not mean the remaining template missed worldgen.
                if(fragmentProof.test(task,new ChunkPos(x,z))) {completed++;continue;}
                String status=generatedStatusProbe.apply(x,z);
                if(status!=null) {
                    // Worldgen may have recorded the fragment between the two reads.
                    if(fragmentProof.test(task,new ChunkPos(x,z))) {completed++;continue;}
                    return CityStructureMaterializationPlanner.ChunkStatusResult.alreadyGenerated(
                            "Owner chunk ["+x+","+z+"] is at "+status
                                    +" without matching template fragment evidence; late paste is not allowed.");
                }
            }
        }
        return CityStructureMaterializationPlanner.ChunkStatusResult.plannedWorldgen(
                "Recorded "+completed+"/"+total+" owner fragments; waiting for remaining worldgen or the next complete-ledger observation.");
    }
}
