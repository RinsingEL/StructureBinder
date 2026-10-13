package com.rinsing.geomantia.api.regions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraftforge.eventbus.api.*;

/** Synchronous worldgen-thread hooks. Listeners must not load chunks or perform filesystem IO. */
public abstract class StructureDistributionEvent extends Event {
    public final ServerLevel level;
    protected StructureDistributionEvent(ServerLevel level) { this.level=level; }
    @Cancelable public static final class Attempt extends StructureDistributionEvent {
        public final Structure structure; public final net.minecraft.world.level.ChunkPos chunk;
        public Attempt(ServerLevel level,Structure structure,net.minecraft.world.level.ChunkPos chunk) {super(level);this.structure=structure;this.chunk=chunk;}
    }
    @Cancelable public static final class Candidate extends StructureDistributionEvent {
        public final Structure structure; public final StructureStart start;
        private final java.util.List<Runnable> accepted=new java.util.ArrayList<>(),rejected=new java.util.ArrayList<>();
        public Candidate(ServerLevel l,Structure s,StructureStart start) { super(l);this.structure=s;this.start=start; }
        /** Defer side effects until all listeners have voted and the chunk has accepted its start. */
        public void afterDecision(Runnable onAccepted,Runnable onRejected) {accepted.add(onAccepted);rejected.add(onRejected);}
        public void finish(boolean success) {var actions=java.util.List.copyOf(success?accepted:rejected);accepted.clear();rejected.clear();actions.forEach(Runnable::run);}
    }
    public static final class Prepare extends StructureDistributionEvent {
        public final ChunkGenerator generator; public final ChunkGeneratorStructureState state;
        public final ChunkAccess chunk; public final StructureTemplateManager templates;
        public Prepare(ServerLevel l,ChunkGenerator g,ChunkGeneratorStructureState s,ChunkAccess c,StructureTemplateManager t) {
            super(l);generator=g;state=s;chunk=c;templates=t;
        }
    }
}
