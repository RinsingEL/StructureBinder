package com.user.terra_script.event;

import com.user.terra_script.world.city.CityInstance;
import com.user.terra_script.world.city.CityManager;
import com.user.terra_script.world.city.RoadInjector;
import net.minecraft.server.level.ServerLevel;
import com.user.terra_script.world.NationGenManager;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "terra_script")
public class DevCommandHandler {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("dev")
                        .then(Commands.literal("rebuild_road")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> {
                                    CityManager mgr = CityManager.get();
                                    for (CityInstance city : mgr.getAllCities()) {
                                        city.isRoadsGenerated = false;
                                        city.roadBlocks = null;
                                    }
                                    for (CityInstance city : mgr.getAllCities()) {
                                        mgr.ensureRoadsGenerated(city.id);
                                    }
                                    RoadInjector.resetProcessing();
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("Roads rebuilt. Reload chunks to apply."), false);
                                    return 1;
                                }))
                        .then(Commands.literal("stage1")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("city_id", StringArgumentType.string())
                                        .executes(ctx -> {
                                            String cityId = StringArgumentType.getString(ctx, "city_id");
                                            ServerLevel level = ctx.getSource().getLevel();
                                            try {
                                                var result = NationGenManager.Stage1Manager.computeAndSave(level, cityId);
                                                if (result == null) {
                                                    ctx.getSource().sendFailure(Component.literal("City not found: " + cityId));
                                                    return 0;
                                                }
                                                ctx.getSource().sendSuccess(() ->
                                                        Component.literal("Stage1 computed for " + cityId), false);
                                                return 1;
                                            } catch (Exception e) {
                                                ctx.getSource().sendFailure(Component.literal("Stage1 failed: " + e.getMessage()));
                                                return 0;
                                            }
                                        })))
                        .then(Commands.literal("stage2")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("city_id", StringArgumentType.string())
                                        .executes(ctx -> {
                                            String cityId = StringArgumentType.getString(ctx, "city_id");
                                            try {
                                                var result = NationGenManager.Stage2Manager.computeAndSave(cityId);
                                                if (result == null) {
                                                    ctx.getSource().sendFailure(Component.literal("Stage1 not found for: " + cityId));
                                                    return 0;
                                                }
                                                ctx.getSource().sendSuccess(() ->
                                                        Component.literal("Stage2 computed for " + cityId), false);
                                                return 1;
                                            } catch (Exception e) {
                                                ctx.getSource().sendFailure(Component.literal("Stage2 failed: " + e.getMessage()));
                                                return 0;
                                            }
                                        })))
        );
    }
}

