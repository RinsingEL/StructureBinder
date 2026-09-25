package com.rinsing.geomantia.systems.realm_planning.testsupport;

import com.mojang.authlib.GameProfile;
import com.rinsing.geomantia.platform.*;
import com.rinsing.geomantia.systems.realm_planning.application.access.*;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import java.nio.file.*;
import java.util.*;

/** Synthetic players are attached only to DistanceManager, exercising vanilla aggregate demand and throttling. */
@GameTestHolder("geomantia_boundary")
public final class BoundaryTicketGameTests {
    @GameTest(template="empty",timeoutTicks=120000)
    public static void clipsViewDemandAndRestoresItWithoutMovement(GameTestHelper helper) {
        var level=helper.getLevel(); var server=level.getServer(); var chunks=level.getChunkSource();
        var manager=chunks.chunkMap.getDistanceManager();
        var diagnostics=(PlanningDistanceContext)manager;
        Path config=server.getServerDirectory().toPath().resolve("config/geomantia/planning_area_access.json");
        Path starter=WorldScopedPlanningPaths.realmDebugRoot(server).getParent().resolve("geomantia_starter_realm.json");
        Runnable close=()->write(config,starter,1024,server);
        Runnable open=()->write(config,starter,2048,server);
        close.run();
        var first=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"BoundaryA"));
        var second=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"BoundaryB"));
        first.setPos(1160,80,8); second.setPos(1160,80,24);
        var a=SectionPos.of(first.blockPosition()); var b=SectionPos.of(second.blockPosition());
        chunks.setViewDistance(12);
        manager.addPlayer(a,first); manager.addPlayer(b,second);
        long legal=ChunkPos.asLong(62,0),closed=ChunkPos.asLong(80,0);
        helper.startSequence()
            .thenWaitUntil(()->helper.assertTrue(diagnostics.geomantia$hasPlayerTicket(legal)
                    && chunks.getChunkNow(62,0)!=null,"Legal edge demand must reach FULL"))
            .thenExecute(()->chunks.setViewDistance(32))
            .thenIdle(80)
            .thenExecute(()->{
                helper.assertTrue(!diagnostics.geomantia$hasPlayerTicket(closed),"View 32 must not enqueue a forbidden PLAYER ticket");
                helper.assertTrue(chunks.getChunkNow(80,0)==null,"Forbidden chunk must not reach FULL");
                manager.removePlayer(a,first);
                helper.assertTrue(diagnostics.geomantia$hasPlayerTicket(legal),"Second player retains shared demand");
                chunks.setViewDistance(12);
                open.run();
            })
            .thenWaitUntil(()->helper.assertTrue(diagnostics.geomantia$hasPlayerTicket(closed),"Stationary unlock must acquire a ticket"))
            .thenWaitUntil(()->helper.assertTrue(chunks.getChunkNow(80,0)!=null,"Restored stationary demand must complete FULL"))
            .thenExecute(close)
            .thenWaitUntil(()->helper.assertTrue(!diagnostics.geomantia$hasPlayerTicket(closed),"Revocation removes PLAYER ticket"))
            .thenExecute(()->{manager.removePlayer(b,second);chunks.setViewDistance(12);})
            .thenWaitUntil(()->helper.assertTrue(!diagnostics.geomantia$hasPlayerTicket(legal),"Last player leaving releases shared demand"))
            .thenSucceed();
    }
    private static void write(Path config,Path starter,int radius,net.minecraft.server.MinecraftServer server) {
        try {
            WorldEntrySurvey.writeAtomic(config,new PlanningAreaAccessConfig(true,radius,3072,Set.of("minecraft:overworld")).asJson());
            WorldEntrySurvey.writeAtomic(starter,new InitialExplorationArea(0,0,radius).description());
            PlanningAreaAccessRuntime.invalidate(server);
        } catch(Exception ex) { throw new RuntimeException(ex); }
    }
}
