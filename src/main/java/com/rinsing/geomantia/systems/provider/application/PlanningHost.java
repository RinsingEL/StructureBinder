package com.rinsing.geomantia.systems.provider.application;

import java.nio.file.Path;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;

/** World-owned workflow. Optional consumers never own or close this service. */
public final class PlanningHost {
    private static volatile PlanningSessionService session;
    private PlanningHost() {}
    public static PlanningSessionService session() { return session; }
    public static synchronized void start(Path server, Path root, int port, long seed, PlanningExtensionRegistry extensions) {
        stop();
        session = new PlanningSessionService(server, root, port, seed, extensions);
        MinecraftForge.EVENT_BUS.post(new Started(server, root, port, seed, session));
    }
    public static synchronized void stop() {
        if (session == null) return;
        MinecraftForge.EVENT_BUS.post(new Stopping());
        session.close(); session = null;
    }
    public static final class Started extends Event {
        public final Path serverDirectory, debugRoot;
        public final int port;
        public final long seed;
        public final PlanningSessionService session;
        Started(Path server, Path root, int port, long seed, PlanningSessionService session) {
            this.serverDirectory=server; this.debugRoot=root; this.port=port; this.seed=seed; this.session=session;
        }
    }
    public static final class Stopping extends Event {}
}
