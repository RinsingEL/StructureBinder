package com.rinsing.geomantia.platform;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.rinsing.geomantia.GeomantiaMod;
import com.rinsing.geomantia.world.atlas.GisClassifierConfig;
import com.rinsing.geomantia.world.atlas.GisSampleConfig;
import com.rinsing.geomantia.world.atlas.refresh.GisRefreshService;
import com.rinsing.geomantia.world.atlas.refresh.RefreshPriority;
import com.rinsing.geomantia.world.atlas.refresh.RefreshResult;
import com.rinsing.geomantia.world.atlas.refresh.SampleMode;
import com.rinsing.geomantia.world.atlas.region.AtlasRegionStore;
import com.rinsing.geomantia.world.atlas.sample.MinecraftPriorAtlasSampler;
import com.rinsing.geomantia.world.atlas.test.GisTestCase;
import com.rinsing.geomantia.world.atlas.test.GisTestRunner;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;

@Mod.EventBusSubscriber(modid = GeomantiaMod.MOD_ID)
public final class GisPlatformEvents {
    private GisPlatformEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("geomantia")
                .then(Commands.literal("gis")
                        .then(Commands.literal("refresh")
                                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> refresh(ctx, SampleMode.PRIOR))
                                        .then(Commands.argument("sampleMode", StringArgumentType.word())
                                                .executes(ctx -> refresh(ctx,
                                                        SampleMode.fromContractName(StringArgumentType.getString(ctx,
                                                                "sampleMode")))))))
                        .then(Commands.literal("test_run")
                                .then(Commands.argument("caseId", StringArgumentType.word())
                                        .executes(GisPlatformEvents::testRun)))));
    }

    private static int refresh(CommandContext<CommandSourceStack> ctx, SampleMode sampleMode) {
        try {
            CommandSourceStack source = ctx.getSource();
            ServerLevel level = source.getLevel();
            int radiusChunks = IntegerArgumentType.getInteger(ctx, "radiusChunks");
            Path debugRoot = debugRoot(source.getServer());
            GisSampleConfig sampleConfig = GisSampleConfig.defaults();
            GisRefreshService service = new GisRefreshService(sampleConfig, GisClassifierConfig.defaults(),
                    new AtlasRegionStore(sampleConfig), new MinecraftPriorAtlasSampler(level));
            BlockPos center = BlockPos.containing(source.getPosition());
            RefreshResult result = service.refresh(level.dimension().location().toString(),
                    center.getX(), center.getZ(), radiusChunks, sampleMode,
                    RefreshPriority.DEBUG, debugRoot);
            source.sendSuccess(() -> Component.literal("GIS refresh completed: "
                    + result.runDirectory().toAbsolutePath()), false);
            return result.job().status().contractName().equals("completed") ? 1 : 0;
        } catch (Exception ex) {
            ctx.getSource().sendFailure(Component.literal("GIS refresh failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static int testRun(CommandContext<CommandSourceStack> ctx) {
        try {
            String caseId = StringArgumentType.getString(ctx, "caseId");
            GisTestRunner runner = new GisTestRunner(GisSampleConfig.defaults(), GisClassifierConfig.defaults());
            var report = runner.runCase(GisTestCase.byId(caseId), debugRoot(ctx.getSource().getServer()));
            ctx.getSource().sendSuccess(() -> Component.literal("GIS test " + caseId + " passed="
                    + report.passed() + " runId=" + report.runId()), false);
            return report.passed() ? 1 : 0;
        } catch (Exception ex) {
            ctx.getSource().sendFailure(Component.literal("GIS test failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static Path debugRoot(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("gis_debug");
    }
}
