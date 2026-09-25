package com.rinsing.geomantia.mixin;

import com.rinsing.geomantia.platform.PlanningDistanceContext;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

@Mixin(DistanceManager.class)
public abstract class PlanningDistanceManagerMixin implements PlanningDistanceContext {
    @Unique private ServerLevel geomantia$level;
    @Shadow @Final private Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> tickets;
    public ServerLevel geomantia$level() { return geomantia$level; }
    public void geomantia$bind(ServerLevel level) { geomantia$level=level; }
    public boolean geomantia$hasPlayerTicket(long position) {
        var values=tickets.get(position);
        return values!=null && values.stream().anyMatch(ticket->ticket.getType()==TicketType.PLAYER);
    }
}
