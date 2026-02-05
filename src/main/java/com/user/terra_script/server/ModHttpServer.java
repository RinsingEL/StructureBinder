package com.user.terra_script.server;

import com.sun.net.httpserver.HttpServer;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.server.mcp.CityController;
import com.user.terra_script.server.mcp.TerritoryController;
import com.user.terra_script.server.mcp.WorldController;
import com.user.terra_script.server.mcp.WorkflowController;
import com.user.terra_script.util.ScanDataIO;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber(modid = "terra_script")
public class ModHttpServer {

    private static HttpServer server;
    private static MinecraftServer mcServer;
    private static final int PORT = 5000;

    @SubscribeEvent
    public static void onServerStart(ServerStartedEvent event) {
        mcServer = event.getServer();
        ScanDataIO.setWorldRoot(mcServer.getWorldPath(LevelResource.ROOT));
        ScanDataIO.loadInto(ScanResultHolder.get());
        startHttpServer();
    }

    @SubscribeEvent
    public static void onServerStop(ServerStoppingEvent event) {
        stopHttpServer();
    }

    private static void startHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);

            WorldController worldController = new WorldController(mcServer);
            TerritoryController territoryController = new TerritoryController();
            WorkflowController workflowController = new WorkflowController();
            CityController cityController = new CityController();

            server.createContext("/continents", worldController::handleContinents);
            server.createContext("/world_atlas", worldController::handleWorldAtlas);
            server.createContext("/world_summary", worldController::handleWorldSummary);
            server.createContext("/terrain_summary", worldController::handleTerrainSummary);
            server.createContext("/structures", worldController::handleStructures);
            server.createContext("/query_region", worldController::handleQueryRegion);
            server.createContext("/place", worldController::handlePlace);

            server.createContext("/t1_blueprint", exchange -> territoryController.handleT1Blueprint(exchange, mcServer));
            server.createContext("/create_territory", territoryController::handleCreateTerritory);
            server.createContext("/territory_status", territoryController::handleTerritoryStatus);

            server.createContext("/freeze_status", workflowController::handleFreezeStatus);
            server.createContext("/freeze_project", workflowController::handleFreezeProject);

            server.createContext("/city_heightmap", cityController::handleCityHeightmap);
            server.createContext("/city_stage1_data", cityController::handleCityStage1Data);
            server.createContext("/city_stage2_data", cityController::handleCityStage2Data);
            server.createContext("/city_forbidden", cityController::handleCityForbidden);
            server.createContext("/city_buildable_groups", cityController::handleCityBuildableGroups);
            server.createContext("/create_city", cityController::handleCreateCity);

            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("TerraScript-API");
                return t;
            }));
            server.start();
            System.out.println("[TerraScript] API Server started on port " + PORT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void stopHttpServer() {
        if (server != null) server.stop(0);
    }
}
