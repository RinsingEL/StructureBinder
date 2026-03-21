package com.user.terra_script.server;

import com.sun.net.httpserver.HttpServer;
import com.user.terra_script.client.data.ScanResultHolder;
import com.user.terra_script.server.mcp.CityController;
import com.user.terra_script.server.mcp.TerritoryController;
import com.user.terra_script.server.mcp.WorldAutomationController;
import com.user.terra_script.server.mcp.WorldController;
import com.user.terra_script.server.mcp.WorkflowController;
import com.user.terra_script.util.ScanDataIO;
import com.user.terra_script.domain.world.scan.service.SatelliteScanner;
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
        ScanDataIO.onServerStarted();
        ScanDataIO.loadInto(ScanResultHolder.get());
        startHttpServer();
    }

    @SubscribeEvent
    public static void onServerStop(ServerStoppingEvent event) {
        SatelliteScanner.stopScanning();
        ScanDataIO.onServerStopping();
        stopHttpServer();
    }

    private static void startHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);

            WorldController worldController = new WorldController(mcServer);
            WorldAutomationController worldAutomationController = new WorldAutomationController(mcServer);
            TerritoryController territoryController = new TerritoryController();
            WorkflowController workflowController = new WorkflowController(mcServer);
            CityController cityController = new CityController(mcServer);

            server.createContext("/continents", worldController::handleContinents);
            server.createContext("/world_atlas", worldController::handleWorldAtlas);
            server.createContext("/world_summary", worldController::handleWorldSummary);
            server.createContext("/t1_preview_maps", worldController::handleT1PreviewMaps);
            server.createContext("/terrain_summary", worldController::handleTerrainSummary);
            server.createContext("/structures", worldController::handleStructures);
            server.createContext("/query_region", worldController::handleQueryRegion);
            server.createContext("/query_region_pick", worldController::handleQueryRegionPick);
            server.createContext("/place", worldController::handlePlace);
            server.createContext("/world/scan/start", worldAutomationController::handleWorldScanStart);
            server.createContext("/world/scan/status", worldAutomationController::handleWorldScanStatus);
            server.createContext("/world/scan/cancel", worldAutomationController::handleWorldScanCancel);
            server.createContext("/world/w3/cluster", worldAutomationController::handleW3Cluster);
            server.createContext("/world/w4/region_scan", worldAutomationController::handleW4RegionScan);
            server.createContext("/world/w4/region_status", worldAutomationController::handleW4RegionStatus);
            server.createContext("/world/w4/export", worldAutomationController::handleW4Export);

            server.createContext("/t1_blueprint", exchange -> territoryController.handleT1Blueprint(exchange, mcServer));
            server.createContext("/t1_candidates_for_continent", exchange -> territoryController.handleT1CandidatesForContinent(exchange, mcServer));
            server.createContext("/t1_select_cluster", exchange -> territoryController.handleT1SelectCluster(exchange, mcServer));
            server.createContext("/t2_direction_candidates", exchange -> territoryController.handleT2DirectionCandidates(exchange, mcServer));
            server.createContext("/t2_select_direction", exchange -> territoryController.handleT2SelectDirection(exchange, mcServer));
            server.createContext("/t3_run_continent", exchange -> territoryController.handleT3RunContinent(exchange, mcServer));
            server.createContext("/create_territory", exchange -> territoryController.handleCreateTerritory(exchange, mcServer));
            server.createContext("/territory_status", territoryController::handleTerritoryStatus);
            server.createContext("/territory/summary", exchange -> territoryController.handleTerritorySummary(exchange, mcServer));
            server.createContext("/territory/t4_window", exchange -> territoryController.handleTerritoryT4Window(exchange, mcServer));

            server.createContext("/freeze_status", workflowController::handleFreezeStatus);
            server.createContext("/freeze_project", workflowController::handleFreezeProject);
            server.createContext("/workflow/run", workflowController::handleWorkflowRun);
            server.createContext("/workflow/status", workflowController::handleWorkflowStatus);
            server.createContext("/task_status", workflowController::handleTaskStatus);

            server.createContext("/city_heightmap", cityController::handleCityHeightmap);
            server.createContext("/city_c1_generate", cityController::handleCreateCity);
            server.createContext("/city_c2_generate", cityController::handleCityC2Generate);
            server.createContext("/city_c2_data", cityController::handleCityStage1Data);
            server.createContext("/city_c3_generate", cityController::handleCityC3Generate);
            server.createContext("/city_c3_data", cityController::handleCityStage2Data);
            server.createContext("/city_stage1_data", cityController::handleCityStage1Data);
            server.createContext("/city_stage2_data", cityController::handleCityStage2Data);
            server.createContext("/city_forbidden", cityController::handleCityForbidden);
            server.createContext("/city_buildable_groups", cityController::handleCityBuildableGroups);
            server.createContext("/city_c4_whitelist_generate", cityController::handleCityC4WhitelistGenerate);
            server.createContext("/city_c4_whitelist_data", cityController::handleCityC4WhitelistData);
            server.createContext("/city_c4_generate", cityController::handleCityC4Generate);
            server.createContext("/city_c4_data", cityController::handleCityC4Data);
            server.createContext("/city_c5_generate", cityController::handleCityC5Generate);
            server.createContext("/city_c5_data", cityController::handleCityC5Data);
            server.createContext("/city_c6_generate", cityController::handleCityC6Generate);
            server.createContext("/city_c6_rect_prepare", cityController::handleCityC6RectPrepare);
            server.createContext("/city_c6_rect_submit", cityController::handleCityC6RectSubmit);
            server.createContext("/city_c6_data", cityController::handleCityC6Data);
            server.createContext("/city_c7_generate", cityController::handleCityC7Generate);
            server.createContext("/city_c7_data", cityController::handleCityC7Data);
            server.createContext("/city_c8_generate", cityController::handleCityC8Generate);
            server.createContext("/city_c8_data", cityController::handleCityC8Data);
            server.createContext("/city_c9_generate", cityController::handleCityC9Generate);
            server.createContext("/city_c9_data", cityController::handleCityC9Data);
            server.createContext("/city_c6_pave_stone", cityController::handleCityC6PaveStone);
            server.createContext("/create_city", cityController::handleCreateCity);

            server.setExecutor(Executors.newFixedThreadPool(6, r -> {
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

