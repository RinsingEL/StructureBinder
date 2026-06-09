package com.rinsing.geomantia.platform.http;

import com.mojang.logging.LogUtils;
import com.rinsing.geomantia.GeomantiaMod;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class GeomantiaHttpServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_PORT = 5000;
    private static HttpServer httpServer;

    private GeomantiaHttpServer() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        stop();
    }

    private static void start(MinecraftServer minecraftServer) {
        if (httpServer != null) {
            return;
        }
        int port = Integer.getInteger("geomantia.apiPort", DEFAULT_PORT);
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            GisHttpController controller = new GisHttpController(minecraftServer);
            httpServer.createContext("/gis/status", controller::handleStatus);
            httpServer.createContext("/gis/refresh", controller::handleRefresh);
            httpServer.createContext("/gis/test_run", controller::handleTestRun);
            httpServer.setExecutor(Executors.newFixedThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                thread.setName("Geomantia-API");
                return thread;
            }));
            httpServer.start();
            LOGGER.info("Geomantia GIS API server started on 127.0.0.1:{}.", port);
        } catch (IOException ex) {
            LOGGER.error("Failed to start Geomantia GIS API server.", ex);
            httpServer = null;
        }
    }

    private static void stop() {
        if (httpServer == null) {
            return;
        }
        httpServer.stop(0);
        httpServer = null;
        LOGGER.info("Geomantia GIS API server stopped.");
    }
}
