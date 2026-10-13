package com.rinsing.realmfolk.mixin;

import com.rinsing.realmfolk.server.ContainerWatch;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuMixin {
    @Unique private Map<String,ItemStack> realmfolk$before;
    @Inject(method="clicked",at=@At("HEAD"))
    private void realmfolk$beforeClick(int slot,int button,ClickType type,Player player,CallbackInfo ci) {
        realmfolk$before=player instanceof ServerPlayer p?ContainerWatch.snapshot(p,(AbstractContainerMenu)(Object)this):null;
    }
    @Inject(method="clicked",at=@At("RETURN"))
    private void realmfolk$afterClick(int slot,int button,ClickType type,Player player,CallbackInfo ci) {
        if(player instanceof ServerPlayer p)ContainerWatch.after(p,(AbstractContainerMenu)(Object)this,realmfolk$before);
        realmfolk$before=null;
    }
}
