package com.rinsing.realmfolk.quest;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Optional read-only bridge, verified against Ars Nouveau 1.20.1-4.12.7.
 * Author targets are glyph ITEM IDs; learning is read from Ars capability, never inventory.
 */
public final class ArsGlyphAdapter {
    private static final String ROOT="com.hollingsworth.arsnouveau.";
    public static boolean installed(){return ModList.get().isLoaded("ars_nouveau");}
    public static boolean glyph(String item){
        if(!installed())return true; // Disabled pack retained; availability is checked before offering quests.
        try{return Class.forName(ROOT+"common.items.Glyph").isInstance(ForgeRegistries.ITEMS.getValue(new ResourceLocation(item)));}
        catch(ReflectiveOperationException|LinkageError e){throw new IllegalStateException("Ars 符文适配版本不兼容",e);}
    }
    public static Set<String> known(ServerPlayer player,Collection<String> candidates){
        if(!installed())throw new IllegalStateException("未安装 Ars Nouveau，无法读取已学符文");
        try{
            var registry=Class.forName(ROOT+"setup.registry.CapabilityRegistry");
            var optional=(LazyOptional<?>)registry.getMethod("getPlayerDataCap",LivingEntity.class).invoke(null,player);
            Object cap=optional.resolve().orElseThrow(()->new IllegalStateException("Ars 玩家能力尚未就绪"));
            var known=(Collection<?>)Class.forName(ROOT+"common.capability.IPlayerCap").getMethod("getKnownGlyphs").invoke(cap);
            var initial=(Collection<?>)Class.forName(ROOT+"api.registry.GlyphRegistry").getMethod("getDefaultStartingSpells").invoke(null);
            var glyphClass=Class.forName(ROOT+"common.items.Glyph");var part=glyphClass.getField("spellPart");
            Set<String> result=new TreeSet<>();
            for(String id:candidates){Object item=ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
                if(!glyphClass.isInstance(item))throw new IllegalStateException("无效符文物品: "+id);
                Object spell=part.get(item);if(known.contains(spell)||initial.contains(spell))result.add(id);
            }return result;
        }catch(ReflectiveOperationException|LinkageError e){throw new IllegalStateException("Ars 已学符文接口不兼容，任务保留",e);}
    }
}
