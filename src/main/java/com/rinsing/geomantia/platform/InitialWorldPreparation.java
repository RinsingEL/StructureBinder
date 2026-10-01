package com.rinsing.geomantia.platform;

import com.rinsing.geomantia.systems.gis.GisClassifierConfig;
import com.rinsing.geomantia.systems.gis.adapter.minecraft.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.systems.gis.application.refresh.SampleMode;
import com.rinsing.geomantia.systems.gis.application.sample.AtlasSampler;
import com.rinsing.geomantia.systems.realm_planning.*;
import com.rinsing.geomantia.systems.realm_planning.adapter.minecraft.MinecraftTerrainPreviewProviderFactory;
import com.rinsing.geomantia.systems.realm_planning.application.terrain.*;
import com.rinsing.geomantia.systems.realm_planning.application.access.*;
import com.rinsing.geomantia.api.regions.StarterRealmReadyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** World-owned startup work, independent of HTTP, optional maps and AI credentials. */
@Mod.EventBusSubscriber(modid="geomantia")
public final class InitialWorldPreparation {
    private static final Map<MinecraftServer,State> STATES=new ConcurrentHashMap<>();
    private static final String ARRIVED="geomantiaStarterContinentArrived";
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void start(ServerStartedEvent event) {
        MinecraftServer server=event.getServer();
        // The regular GameTest suite creates tiny synthetic worlds with its own survey fixtures.
        if(server instanceof net.minecraft.gametest.framework.GameTestServer) return;
        State state=new State(server);
        if(STATES.putIfAbsent(server,state)!=null) { state.worker.shutdownNow(); return; }
        state.worker.submit(state::prepare);
    }
    public static boolean busy(MinecraftServer server) {
        State state=STATES.get(server); return state!=null && !state.ready;
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) {
        State state=STATES.remove(event.getServer());
        if(state!=null) { state.closed=true; state.worker.shutdownNow(); state.bar.removeAllPlayers(); }
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END) return;
        State state=STATES.get(event.getServer());
        if(state!=null && event.getServer().getTickCount()%10==0) state.tick();
    }
    @SubscribeEvent public static void clonePlayer(PlayerEvent.Clone event) {
        var original=event.getOriginal().getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        var current=event.getEntity().getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if(original.getBoolean(ARRIVED)) { current.putBoolean(ARRIVED,true); event.getEntity().getPersistentData().put(Player.PERSISTED_NBT_TAG,current); }
    }
    /** Hold first-time players at the safe waiting spawn; the ordinary boundary handler stays separate. */
    public static boolean hold(ServerPlayer player) {
        State state=STATES.get(player.server);
        if(state==null || state.ready || player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getBoolean(ARRIVED)) return false;
        BlockPos spawn=player.server.overworld().getSharedSpawnPos();
        player.stopRiding();
        if(player.serverLevel()!=player.server.overworld() || player.distanceToSqr(spawn.getX()+0.5,spawn.getY(),spawn.getZ()+0.5)>1)
            player.teleportTo(player.server.overworld(),spawn.getX()+0.5,spawn.getY(),spawn.getZ()+0.5,player.getYRot(),player.getXRot());
        player.setDeltaMovement(Vec3.ZERO); player.fallDistance=0;
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,40,4,true,false));
        return true;
    }
    private static final class State {
        final MinecraftServer server;
        final ExecutorService worker=Executors.newSingleThreadExecutor(r->{ Thread t=new Thread(r,"Geomantia-Initial-W");t.setDaemon(true);return t; });
        final ServerBossEvent bar=new ServerBossEvent(Component.literal("正在读取世界扫描…"),BossEvent.BossBarColor.BLUE,BossEvent.BossBarOverlay.PROGRESS);
        volatile boolean ready,closed;
        volatile boolean supplementingClimate;
        volatile String failure="";
        volatile WorldSurveyRunner.ProgressUpdate progress;
        BlockPos destination;
        final Map<UUID,Integer> arrivalDue=new HashMap<>();
        State(MinecraftServer server) { this.server=server; }
        void prepare() {
            try {
                Path root=WorldScopedPlanningPaths.realmDebugRoot(server);
                var access=PlanningAreaAccessConfig.loadOrCreate(server.getServerDirectory().toPath().resolve("config/geomantia/planning_area_access.json"));
                if(!access.enabled() || !access.managedDimensions().contains("minecraft:overworld")) { ready=true; return; }
                var settings=WorldSurveySettingsConfig.loadOrCreate(server.getServerDirectory().toPath().resolve("config/geomantia/world_survey.json"));
                Path run=WorldEntrySurvey.prepare(root,server.overworld().getSeed(),settings.planningRadiusBlocks(),runId->{
                    var frozenSettings=new WorldSurveySettingsConfig(WorldEntrySurvey.savedRadius(root,settings.planningRadiusBlocks()));
                    Prepared prepared=server.submit(()->prepareSampler(runId,frozenSettings)).get();
                    var runner=new WorldSurveyRunner(root,GisClassifierConfig.defaults());
                    // A sealed sampler result can survive an interrupted W export without resampling.
                    WorldSurveyResult result;
                    if(WorldEntrySurvey.samplesSealed(root.resolve(runId)))
                        result=runner.loadSealedResult(runId);
                    else result=runner.run(prepared.config,prepared.sampler,update->progress=update,()->closed);
                    if(closed) throw new CancellationException();
                    new RealmPlanningService(root,access).runW(result,null);
                });
                if(closed) return;
                Prepared climatePrepared=server.submit(()->prepareSampler(run.getFileName().toString(),settings)).get();
                if(climatePrepared.sampler instanceof TerrainClimateSampler climate && climate.climateAvailable()) {
                    supplementingClimate=true;
                    try {
                        WorldClimateSurvey.supplement(run,Long.toString(server.overworld().getSeed()),
                                "minecraft:overworld",climatePrepared.config.terrainProvider(),climate,()->closed);
                    } finally { supplementingClimate=false; }
                }
                if(closed) return;
                server.submit(()->{
                    if(closed) return;
                    try {
                        destination=StarterRealmBootstrap.completeSurvey(server.overworld(),run,access);
                        WorldEntrySurvey.markReady(root); ready=true;
                    }
                    catch(Exception e) { fail(e); }
                }).get();
            } catch(InterruptedException|CancellationException stopped) { Thread.currentThread().interrupt(); }
            catch(Exception error) { if(!closed) fail(error); }
        }
        Prepared prepareSampler(String runId,WorldSurveySettingsConfig settings) {
            var level=server.overworld();
            var fallback=new MinecraftPriorAtlasSampler(level);
            String fingerprint=String.join("|","minecraft_prior",level.dimension().location().toString(),Long.toString(level.getSeed()),level.getChunkSource().getGenerator().getClass().getName());
            var selection=MinecraftTerrainPreviewProviderFactory.createSelector(level,fallback,fingerprint).select(true);
            var provenance=TerrainSamplingProvenance.fromSelection(true,selection);
            var config=new WorldSurveyRunner.Config(runId,level.dimension().location().toString(),Long.toString(level.getSeed()),
                    level.getWorldBorder().getSize(),0,0,settings.planningRadiusBlocks(),WorldSurveyRunner.DEFAULT_CELL_STEP_BLOCKS,
                    RealmPlanningService.DEFAULT_MICRO_SAMPLE_STRIDE_BLOCKS,WorldSurveyRunner.DEFAULT_LOCAL_SLOPE_RADIUS_BLOCKS,
                    SampleMode.PRIOR,WorldSurveyRunner.ResumePolicy.USE_CACHE,provenance);
            return new Prepared(config,selection.fastPath()?new TerrainPreviewAtlasSampler(selection):fallback);
        }
        void fail(Exception e) {
            failure="世界准备失败，请查看日志；重新进入可继续扫描";
            org.slf4j.LoggerFactory.getLogger(InitialWorldPreparation.class).error("Initial world preparation failed",e);
        }
        void tick() {
            for(var player:server.getPlayerList().getPlayers()) {
                if(!ready) bar.addPlayer(player);
                else {
                    bar.removePlayer(player);
                    if(destination==null || player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getBoolean(ARRIVED)) continue;
                    // Delay the map until the login/respawn and teleport packets have been applied.
                    Integer due=arrivalDue.get(player.getUUID());
                    if(due==null) {
                        player.stopRiding(); player.setDeltaMovement(Vec3.ZERO);
                        player.teleportTo(server.overworld(),destination.getX()+0.5,destination.getY(),destination.getZ()+0.5,player.getYRot(),player.getXRot());
                        player.setRespawnPosition(server.overworld().dimension(),destination,0,true,false);
                        player.fallDistance=0; PlanningAreaAccessRuntime.forget(player);
                        arrivalDue.put(player.getUUID(),server.getTickCount()+20);
                    } else if(server.getTickCount()>=due) {
                        var data=player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
                        data.putBoolean(ARRIVED,true); player.getPersistentData().put(Player.PERSISTED_NBT_TAG,data);
                        arrivalDue.remove(player.getUUID());
                        player.sendSystemMessage(Component.literal("初始大陆及近海已开放，世界扫描已保存。"));
                        MinecraftForge.EVENT_BUS.post(new StarterRealmReadyEvent(player));
                    }
                }
            }
            arrivalDue.keySet().removeIf(id->server.getPlayerList().getPlayer(id)==null);
            if(ready) return;
            if(!failure.isBlank()) { bar.setName(Component.literal(failure)); bar.setColor(BossEvent.BossBarColor.RED); return; }
            if(supplementingClimate) { bar.setName(Component.literal("正在补采 RTF 原生温湿度…")); bar.setProgress(0); return; }
            var update=progress;
            if(update==null) return;
            String phase=switch(update.phase()) { case "micro_sampling"->"地貌采样"; case "complete"->"整理大陆轮廓"; default->"世界扫描"; };
            bar.setName(Component.literal(phase+" · "+String.format(Locale.ROOT,"%.1f%%",update.phaseProgressPercent())));
            bar.setProgress((float)Math.max(0,Math.min(1,update.phaseProgressPercent()/100)));
        }
    }
    private record Prepared(WorldSurveyRunner.Config config,AtlasSampler sampler) {}
}
