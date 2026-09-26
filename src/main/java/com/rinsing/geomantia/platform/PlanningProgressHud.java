package com.rinsing.geomantia.platform;

import com.rinsing.geomantia.systems.provider.application.PlanningHost;
import com.rinsing.geomantia.systems.provider.application.PlanningProgress;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.Map;
import java.util.concurrent.*;

/** Same vanilla HUD surface as initial W; disk discovery never runs on the server tick. */
@Mod.EventBusSubscriber(modid="geomantia")
public final class PlanningProgressHud {
    private static final Map<MinecraftServer,State> STATES=new ConcurrentHashMap<>();
    @SubscribeEvent public static void start(ServerStartedEvent event) {
        if(event.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return;
        STATES.computeIfAbsent(event.getServer(),ignored->new State());
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) {
        State state=STATES.remove(event.getServer());
        if(state!=null) { state.worker.shutdownNow(); state.bar.removeAllPlayers(); }
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || event.getServer().getTickCount()%10!=0) return;
        State state=STATES.get(event.getServer());
        if(state!=null) state.tick(event.getServer());
    }
    private static final class State {
        final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
            Thread thread=new Thread(r,"Geomantia-Planning-HUD");thread.setDaemon(true);return thread;
        });
        final ServerBossEvent bar=new ServerBossEvent(Component.literal("规划进度"),BossEvent.BossBarColor.BLUE,BossEvent.BossBarOverlay.PROGRESS);
        Future<PlanningProgress> pending;
        PlanningProgress progress=PlanningProgress.hidden();
        int nextRead, completionUntil;
        boolean observedActive;
        void tick(MinecraftServer server) {
            if(InitialWorldPreparation.busy(server) || PlanningHost.session()==null) {
                bar.removeAllPlayers(); return;
            }
            int now=server.getTickCount();
            if(pending!=null && pending.isDone()) {
                try {
                    var update=pending.get();
                    if(update.complete() && !progress.complete() && observedActive) completionUntil=now+100;
                    progress=update;
                } catch(Exception error) {
                    progress=PlanningProgress.unavailable();
                }
                pending=null;
            }
            if(pending==null && now>=nextRead) {
                var session=PlanningHost.session();
                pending=worker.submit(()->PlanningProgress.from(session.snapshot()));
                nextRead=now+40;
            }
            if(!progress.visible() || progress.complete() && now>=completionUntil) { bar.removeAllPlayers(); return; }
            if(!progress.complete()) observedActive=true;
            bar.setName(Component.literal(progress.title()));
            bar.setProgress(progress.fraction());
            bar.setColor(switch(progress.tone()) {
                case ERROR -> BossEvent.BossBarColor.RED;
                case WAITING -> BossEvent.BossBarColor.YELLOW;
                case COMPLETE -> BossEvent.BossBarColor.GREEN;
                default -> BossEvent.BossBarColor.BLUE;
            });
            for(var old:java.util.List.copyOf(bar.getPlayers()))
                if(!server.getPlayerList().getPlayers().contains(old)) bar.removePlayer(old);
            for(var player:server.getPlayerList().getPlayers()) bar.addPlayer(player);
        }
    }
}
