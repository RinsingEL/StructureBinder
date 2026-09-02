package com.rinsing.geomantia.systems.realm_planning.adapter.minecraft;

import com.rinsing.geomantia.platform.registry.GeomantiaItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Grants each player one Adventurer Map on their first login to a world. */
public final class AdventurerMapStarterGrant {
    private static final String GRANTED_KEY = "geomantiaAdventurerMapGranted";

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompoundTag persisted = persistedData(player);
        if (persisted.getBoolean(GRANTED_KEY)) {
            return;
        }

        ItemStack map = new ItemStack(GeomantiaItems.ADVENTURER_MAP.get());
        if (!player.getInventory().contains(map) && !player.addItem(map)) {
            player.drop(map, false);
        }
        persisted.putBoolean(GRANTED_KEY, true);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        CompoundTag original = persistedData(event.getOriginal());
        if (!original.getBoolean(GRANTED_KEY)) {
            return;
        }
        CompoundTag replacement = persistedData(event.getEntity());
        replacement.putBoolean(GRANTED_KEY, true);
        event.getEntity().getPersistentData().put(Player.PERSISTED_NBT_TAG, replacement);
    }

    private static CompoundTag persistedData(Player player) {
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
    }
}
